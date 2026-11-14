using System.Buffers.Binary;
using System.Security.Cryptography;

namespace SpaceConnectMicBridge;

internal sealed class MicSession : IDisposable
{
    private readonly byte[] id;
    private readonly byte[] key;
    private readonly AesGcm cipher;
    private readonly DateTimeOffset expiresAt;
    private ulong sequence;
    private uint? noncePrefix;

    public MicSession(string sessionId, string key, DateTimeOffset expiresAt)
    {
        id = Convert.FromBase64String(sessionId);
        this.key = Convert.FromBase64String(key);
        if (id.Length != 16 || this.key.Length != 32)
            throw new ArgumentException("Invalid microphone session");
        cipher = new AesGcm(this.key, 16);
        this.expiresAt = expiresAt;
    }

    public bool TryRead(byte[] packet, DateTimeOffset now, out byte[] pcm)
    {
        pcm = Array.Empty<byte>();
        if (now >= expiresAt || packet.Length < 48 || packet.Length > 688
                || packet.Length % 2 != 0 || !packet.AsSpan(0, 4).SequenceEqual("SCM2"u8)
                || !CryptographicOperations.FixedTimeEquals(packet.AsSpan(4, 16), id))
            return false;
        uint prefix = BinaryPrimitives.ReadUInt32LittleEndian(packet.AsSpan(20, 4));
        ulong next = BinaryPrimitives.ReadUInt64LittleEndian(packet.AsSpan(24, 8));
        if (next <= sequence || (noncePrefix.HasValue && noncePrefix.Value != prefix))
            return false;
        byte[] decoded = new byte[packet.Length - 48];
        try
        {
            cipher.Decrypt(packet.AsSpan(20, 12), packet.AsSpan(32, decoded.Length),
                packet.AsSpan(packet.Length - 16), decoded, packet.AsSpan(0, 32));
        }
        catch (CryptographicException)
        {
            return false;
        }
        noncePrefix = prefix;
        sequence = next;
        pcm = decoded;
        return true;
    }

    public byte[] Acknowledge(byte[] packet)
    {
        byte[] response = new byte[65];
        "SCA2"u8.CopyTo(response);
        packet.AsSpan(4, 28).CopyTo(response.AsSpan(4));
        response[32] = 1;
        HMACSHA256.HashData(key, response.AsSpan(0, 33)).CopyTo(response.AsSpan(33));
        return response;
    }

    public void Dispose()
    {
        cipher.Dispose();
        CryptographicOperations.ZeroMemory(key);
    }
}
