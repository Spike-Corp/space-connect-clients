using System.Net;
using System.Net.Sockets;
using NAudio.CoreAudioApi;
using NAudio.Wave;

namespace SpaceConnectMicBridge;

// Space Connect Mic Bridge
//
// Small companion program for the "Space Connect" Moonlight client. The Android app captures the
// phone's microphone and sends raw PCM audio over UDP to this program, which plays it into a
// virtual audio "cable" (e.g. VB-Audio Virtual Cable's "CABLE Input" playback device). Windows
// then exposes the cable's matching recording endpoint ("CABLE Output") as if it were a real
// microphone, which any app (Discord, a game, Windows itself) can select as its input device.
//
// This does NOT touch the Moonlight/GameStream protocol at all - it's a fully separate, parallel
// UDP side-channel on the same local network, because the streaming protocol itself has no
// client-to-host audio channel (confirmed - see repo notes).
internal static class Program
{
    // Must match the header written by the Android client's mic-forwarding feature.
    private static readonly byte[] MagicHeader = { (byte)'S', (byte)'C', (byte)'M', (byte)'B' };
    private const int HeaderSize = 12; // magic(4) + version(1) + channels(1) + reserved(2) + sampleRate(4)
    private const int ProtocolVersion = 1;

    private const int DefaultPort = 48100;
    private const string DefaultDeviceNameFilter = "CABLE Input";

    private static async Task<int> Main(string[] args)
    {
        int port = DefaultPort;
        string deviceNameFilter = DefaultDeviceNameFilter;

        for (int i = 0; i < args.Length; i++)
        {
            if (args[i] == "--port" && i + 1 < args.Length && int.TryParse(args[i + 1], out int parsedPort))
            {
                port = parsedPort;
            }
            else if (args[i] == "--device" && i + 1 < args.Length)
            {
                deviceNameFilter = args[i + 1];
            }
        }

        Console.WriteLine("Space Connect Mic Bridge");
        Console.WriteLine("=========================");

        MMDevice? outputDevice = FindOutputDevice(deviceNameFilter);
        if (outputDevice == null)
        {
            Console.WriteLine($"ERROR: Could not find a playback device matching \"{deviceNameFilter}\".");
            Console.WriteLine();
            Console.WriteLine("This program needs a virtual audio cable installed first, e.g. VB-Audio");
            Console.WriteLine("Virtual Cable (free): https://vb-audio.com/Cable/");
            Console.WriteLine("After installing it, re-run this program. In Discord/your game, select");
            Console.WriteLine("\"CABLE Output\" as your microphone.");
            return 1;
        }

        Console.WriteLine($"Output device: {outputDevice.FriendlyName}");
        Console.WriteLine($"Listening for mic audio on UDP port {port}...");
        Console.WriteLine("Press Ctrl+C to stop.");
        Console.WriteLine();

        using var udpClient = new UdpClient(port);
        WasapiOut? wasapiOut = null;
        BufferedWaveProvider? bufferedWaveProvider = null;
        IPEndPoint? lastRemoteEndPoint = null;
        long packetCount = 0;

        var cts = new CancellationTokenSource();
        Console.CancelKeyPress += (_, e) =>
        {
            e.Cancel = true;
            cts.Cancel();
        };

        try
        {
            while (!cts.IsCancellationRequested)
            {
                UdpReceiveResult result;
                try
                {
                    result = await udpClient.ReceiveAsync(cts.Token);
                }
                catch (OperationCanceledException)
                {
                    break;
                }

                byte[] packet = result.Buffer;
                if (packet.Length <= HeaderSize || !HasValidMagic(packet))
                {
                    continue;
                }

                byte version = packet[4];
                byte channels = packet[5];
                int sampleRate = BitConverter.ToInt32(packet, 8);

                if (version != ProtocolVersion || channels < 1 || channels > 2 || sampleRate <= 0)
                {
                    continue;
                }

                if (!result.RemoteEndPoint.Equals(lastRemoteEndPoint))
                {
                    Console.WriteLine($"Receiving mic audio from {result.RemoteEndPoint} ({sampleRate} Hz, {channels}ch)");
                    lastRemoteEndPoint = result.RemoteEndPoint;
                }

                if (wasapiOut == null)
                {
                    var waveFormat = new WaveFormat(sampleRate, 16, channels);
                    bufferedWaveProvider = new BufferedWaveProvider(waveFormat)
                    {
                        DiscardOnBufferOverflow = true,
                        BufferDuration = TimeSpan.FromSeconds(1),
                    };

                    wasapiOut = new WasapiOut(outputDevice, AudioClientShareMode.Shared, true, 50);
                    wasapiOut.Init(bufferedWaveProvider);
                    wasapiOut.Play();
                }

                bufferedWaveProvider!.AddSamples(packet, HeaderSize, packet.Length - HeaderSize);

                packetCount++;
                if (packetCount % 500 == 0)
                {
                    Console.WriteLine($"...{packetCount} packets relayed");
                }
            }
        }
        finally
        {
            wasapiOut?.Stop();
            wasapiOut?.Dispose();
        }

        Console.WriteLine("Stopped.");
        return 0;
    }

    private static bool HasValidMagic(byte[] packet)
    {
        for (int i = 0; i < MagicHeader.Length; i++)
        {
            if (packet[i] != MagicHeader[i])
            {
                return false;
            }
        }
        return true;
    }

    private static MMDevice? FindOutputDevice(string nameFilter)
    {
        using var enumerator = new MMDeviceEnumerator();
        MMDeviceCollection devices = enumerator.EnumerateAudioEndPoints(DataFlow.Render, DeviceState.Active);

        foreach (MMDevice device in devices)
        {
            if (device.FriendlyName.Contains(nameFilter, StringComparison.OrdinalIgnoreCase))
            {
                return device;
            }
        }

        return null;
    }
}
