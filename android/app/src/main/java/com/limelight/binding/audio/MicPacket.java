package com.limelight.binding.audio;

import android.util.Base64;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class MicPacket {
    public static final int FRAME_BYTES = 640;
    private final byte[] sessionId;
    private final byte[] prefix = new byte[4];
    private final SecretKeySpec encryptionKey;
    private final SecretKeySpec acknowledgementKey;
    private long sequence;
    private long acknowledged;

    public MicPacket(String sessionId, String key) {
        if (sessionId == null || key == null) throw new IllegalArgumentException("Missing microphone session");
        this.sessionId = Base64.decode(sessionId, Base64.NO_WRAP);
        byte[] decoded = Base64.decode(key, Base64.NO_WRAP);
        if (this.sessionId.length != 16 || decoded.length != 32)
            throw new IllegalArgumentException("Invalid microphone session");
        encryptionKey = new SecretKeySpec(decoded, "AES");
        acknowledgementKey = new SecretKeySpec(decoded, "HmacSHA256");
        Arrays.fill(decoded, (byte) 0);
        new SecureRandom().nextBytes(prefix);
    }

    public byte[] encode(byte[] pcm, int length) throws GeneralSecurityException {
        if (length < 0 || length > FRAME_BYTES || length > pcm.length || length % 2 != 0
                || sequence == Long.MAX_VALUE)
            throw new IllegalArgumentException("Invalid microphone frame");
        byte[] packet = new byte[48 + length];
        packet[0] = 'S'; packet[1] = 'C'; packet[2] = 'M'; packet[3] = '2';
        System.arraycopy(sessionId, 0, packet, 4, 16);
        System.arraycopy(prefix, 0, packet, 20, 4);
        ByteBuffer.wrap(packet, 24, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(++sequence);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey,
                new GCMParameterSpec(128, Arrays.copyOfRange(packet, 20, 32)));
        cipher.updateAAD(packet, 0, 32);
        byte[] encrypted = cipher.doFinal(pcm, 0, length);
        System.arraycopy(encrypted, 0, packet, 32, encrypted.length);
        return packet;
    }

    public boolean acceptAcknowledgement(byte[] packet, int length) throws GeneralSecurityException {
        if (length != 65 || packet.length < length || packet[0] != 'S' || packet[1] != 'C'
                || packet[2] != 'A' || packet[3] != '2' || packet[32] != 1
                || !Arrays.equals(sessionId, Arrays.copyOfRange(packet, 4, 20))
                || !Arrays.equals(prefix, Arrays.copyOfRange(packet, 20, 24)))
            return false;
        long received = ByteBuffer.wrap(packet, 24, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
        if (received <= acknowledged || received > sequence) return false;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(acknowledgementKey);
        mac.update(packet, 0, 33);
        if (!MessageDigest.isEqual(mac.doFinal(), Arrays.copyOfRange(packet, 33, 65))) return false;
        acknowledged = received;
        return true;
    }
}
