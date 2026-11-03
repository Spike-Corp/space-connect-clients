using System.IO.Pipes;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Text;
using System.Text.Json;
using NAudio.CoreAudioApi;
using NAudio.Wave;

namespace SpaceConnectMicBridge;

internal static class Program
{
    private const string ControlPipe = "SpaceConnectMicBridge.Control.v2";
    private static readonly object Gate = new();
    private static MicSession? session;
    private static DateTimeOffset lastPacket;
    private static long packets;
    private static long recordedSamples;
    private static float recordedPeak;

    private static async Task<int> Main(string[] args)
    {
        if (args.Contains("--version"))
        {
            Console.WriteLine("SpaceConnectMicBridge 0.3.49 protocol=2");
            return 0;
        }
        int port = 48100;
        for (int i = 0; i < args.Length; i++)
            if (args[i] == "--port" && i + 1 < args.Length) port = int.Parse(args[++i]);
        using var shutdown = new CancellationTokenSource();
        Console.CancelKeyPress += (_, e) => { e.Cancel = true; shutdown.Cancel(); };
        try
        {
            using MMDevice output = FindDevice(DataFlow.Render, "CABLE Input");
            using MMDevice input = FindDevice(DataFlow.Capture, "CABLE Output");
            output.AudioEndpointVolume.Mute = false;
            input.AudioEndpointVolume.Mute = false;
            if (output.AudioEndpointVolume.MasterVolumeLevelScalar == 0) output.AudioEndpointVolume.MasterVolumeLevelScalar = 1;
            if (input.AudioEndpointVolume.MasterVolumeLevelScalar == 0) input.AudioEndpointVolume.MasterVolumeLevelScalar = 1;
            var buffer = new BufferedWaveProvider(new WaveFormat(16000, 16, 1))
            {
                BufferDuration = TimeSpan.FromMilliseconds(160),
                ReadFully = true,
            };
            using var render = new WasapiOut(output, AudioClientShareMode.Shared, true, 30);
            render.Init(buffer);
            using var capture = new WasapiCapture(input);
            capture.DataAvailable += (_, e) => Measure(capture.WaveFormat, e.Buffer, e.BytesRecorded);
            capture.RecordingStopped += (_, e) =>
            {
                if (e.Exception != null)
                {
                    Console.Error.WriteLine("Virtual microphone capture failed: " + e.Exception.Message);
                    shutdown.Cancel();
                }
            };
            render.PlaybackStopped += (_, e) =>
            {
                if (e.Exception != null)
                {
                    Console.Error.WriteLine("Virtual microphone playback failed: " + e.Exception.Message);
                    shutdown.Cancel();
                }
            };
            render.Play();
            capture.StartRecording();
            bool ipv6 = Socket.OSSupportsIPv6;
            using var udp = new UdpClient(ipv6 ? AddressFamily.InterNetworkV6 : AddressFamily.InterNetwork);
            if (ipv6) udp.Client.DualMode = true;
            udp.ExclusiveAddressUse = true;
            udp.Client.Bind(new IPEndPoint(ipv6 ? IPAddress.IPv6Any : IPAddress.Any, port));
            Console.WriteLine($"SpaceConnectMicBridge 0.3.49 ready; authenticated UDP {port}; {output.FriendlyName} -> {input.FriendlyName}");
            Task control = ControlLoop(shutdown.Token);
            Task audio = ReceiveLoop(udp, buffer, shutdown.Token);
            try
            {
                await await Task.WhenAny(control, audio);
            }
            finally
            {
                shutdown.Cancel();
                try { await Task.WhenAll(control, audio); }
                catch (OperationCanceledException) when (shutdown.IsCancellationRequested) { }
                capture.StopRecording();
                render.Stop();
                lock (Gate) { session?.Dispose(); session = null; }
            }
            return 0;
        }
        catch (OperationCanceledException) when (shutdown.IsCancellationRequested) { return 0; }
        catch (Exception error) when (error is IOException or SocketException or COMException
                or InvalidOperationException or UnauthorizedAccessException or ArgumentException)
        {
            Console.Error.WriteLine($"MicBridge failed ({error.GetType().Name}): {error.Message}");
            return 1;
        }
    }

    private static async Task ReceiveLoop(UdpClient udp, BufferedWaveProvider buffer, CancellationToken cancellation)
    {
        while (!cancellation.IsCancellationRequested)
        {
            UdpReceiveResult received = await udp.ReceiveAsync(cancellation);
            byte[]? acknowledgement = null;
            lock (Gate)
            {
                DateTimeOffset now = DateTimeOffset.UtcNow;
                if (session != null && now - lastPacket > TimeSpan.FromMinutes(2))
                {
                    session.Dispose();
                    session = null;
                }
                if (session == null || !session.TryRead(received.Buffer, now, out byte[] pcm)) continue;
                lastPacket = now;
                if (pcm.Length > 0)
                {
                    if (buffer.BufferedDuration > TimeSpan.FromMilliseconds(100)) buffer.ClearBuffer();
                    buffer.AddSamples(pcm, 0, pcm.Length);
                }
                packets++;
                if (pcm.Length == 0 || packets % 10 == 1)
                    acknowledgement = session.Acknowledge(received.Buffer);
            }
            if (acknowledgement != null)
                await udp.SendAsync(acknowledgement, received.RemoteEndPoint, cancellation);
        }
    }

    private static async Task ControlLoop(CancellationToken cancellation)
    {
        var security = new PipeSecurity();
        security.SetAccessRuleProtection(true, false);
        security.AddAccessRule(new PipeAccessRule(new SecurityIdentifier(WellKnownSidType.NetworkSid, null),
            PipeAccessRights.FullControl, AccessControlType.Deny));
        foreach (var sid in new[] {
            WindowsIdentity.GetCurrent().User!,
            new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null),
            new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null),
        })
            security.AddAccessRule(new PipeAccessRule(sid, PipeAccessRights.FullControl, AccessControlType.Allow));
        while (!cancellation.IsCancellationRequested)
        {
            using var pipe = NamedPipeServerStreamAcl.Create(ControlPipe, PipeDirection.InOut, 1,
                PipeTransmissionMode.Byte, PipeOptions.Asynchronous, 4096, 4096, security);
            await pipe.WaitForConnectionAsync(cancellation);
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
            timeout.CancelAfter(TimeSpan.FromSeconds(5));
            try
            {
                byte[] command = new byte[1024];
                int length = 0;
                while (length < command.Length)
                {
                    int count = await pipe.ReadAsync(command.AsMemory(length, 1), timeout.Token);
                    if (count == 0) throw new IOException("Incomplete control command");
                    if (command[length++] == '\n') break;
                }
                if (command[length - 1] != '\n') throw new IOException("Control command too large");
                using JsonDocument document = JsonDocument.Parse(command.AsMemory(0, length));
                object response;
                lock (Gate)
                {
                    if (document.RootElement.TryGetProperty("status", out var status) && status.ValueKind == JsonValueKind.True)
                    {
                        response = new { ready = true, protocol = 2, packets, recordedSamples, recordedPeak };
                    }
                    else
                    {
                        string id = document.RootElement.GetProperty("sessionId").GetString()!;
                        string key = document.RootElement.GetProperty("key").GetString()!;
                        long expires = document.RootElement.GetProperty("expiresAt").GetInt64();
                        var expiry = DateTimeOffset.FromUnixTimeSeconds(expires);
                        if (expiry <= DateTimeOffset.UtcNow || expiry > DateTimeOffset.UtcNow.AddHours(24))
                            throw new ArgumentException("Invalid session expiration");
                        var replacement = new MicSession(id, key, expiry);
                        session?.Dispose();
                        session = replacement;
                        lastPacket = DateTimeOffset.UtcNow;
                        packets = 0;
                        recordedSamples = 0;
                        recordedPeak = 0;
                        response = new { ready = true, protocol = 2, sessionId = id };
                    }
                }
                byte[] encoded = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(response) + "\n");
                await pipe.WriteAsync(encoded, timeout.Token);
                await pipe.FlushAsync(timeout.Token);
            }
            catch (Exception error) when (error is IOException or JsonException or ArgumentException or FormatException
                    or InvalidOperationException or KeyNotFoundException or OperationCanceledException)
            {
                if (cancellation.IsCancellationRequested) return;
                Console.Error.WriteLine("Mic control rejected: " + error.GetType().Name);
            }
        }
    }

    private static MMDevice FindDevice(DataFlow flow, string name)
    {
        using var enumerator = new MMDeviceEnumerator();
        foreach (MMDevice device in enumerator.EnumerateAudioEndPoints(flow, DeviceState.Active))
        {
            if (device.FriendlyName.Contains(name, StringComparison.OrdinalIgnoreCase)) return device;
            device.Dispose();
        }
        throw new InvalidOperationException("Virtual audio endpoint not available: " + name);
    }

    private static void Measure(WaveFormat format, byte[] data, int length)
    {
        int bytesPerSample = format.BitsPerSample / 8;
        if (bytesPerSample != 4 && bytesPerSample != 2) return;
        float peak = 0;
        for (int i = 0; i + bytesPerSample <= length; i += bytesPerSample)
        {
            float value = bytesPerSample == 4 ? BitConverter.ToSingle(data, i) : BitConverter.ToInt16(data, i) / 32768f;
            if (float.IsFinite(value)) peak = Math.Max(peak, Math.Abs(value));
        }
        lock (Gate)
        {
            recordedSamples += length / bytesPerSample;
            recordedPeak = Math.Max(recordedPeak, peak);
        }
    }
}
