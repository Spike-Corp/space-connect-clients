package com.limelight.binding.audio;

import android.util.Base64;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 24)
public class MicPacketTest {
    static final String KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    static final String ID = "ICEiIyQlJicoKSorLC0uLw==";

    @Test public void preservesRecordingSamplesInAuthenticatedMtuSizedFrames() throws Exception {
        MicPacket codec = new MicPacket(ID, KEY);
        byte[] pcm = new byte[] {0x34, 0x12, (byte) 0xcc, (byte) 0xed};
        byte[] packet = codec.encode(pcm, pcm.length);
        assertEquals(52, packet.length);
        assertArrayEquals(new byte[] {'S', 'C', 'M', '2'}, Arrays.copyOf(packet, 4));
        assertArrayEquals(pcm, decrypt(packet));
        byte[] next = codec.encode(pcm, pcm.length);
        assertFalse(Arrays.equals(Arrays.copyOfRange(packet, 20, 32), Arrays.copyOfRange(next, 20, 32)));
        assertEquals(688, codec.encode(new byte[640], 640).length);
    }

    @Test public void onlyAcceptsAnAuthenticatedFreshHostAcknowledgement() throws Exception {
        MicPacket codec = new MicPacket(ID, KEY);
        byte[] packet = codec.encode(new byte[0], 0);
        byte[] ack = acknowledge(packet);
        byte[] forged = ack.clone();
        forged[64] ^= 1;
        assertFalse(codec.acceptAcknowledgement(forged, forged.length));
        assertTrue(codec.acceptAcknowledgement(ack, ack.length));
        assertFalse(codec.acceptAcknowledgement(ack, ack.length));
        MicPacket other = new MicPacket("AAAAAAAAAAAAAAAAAAAAAA==", KEY);
        other.encode(new byte[0], 0);
        assertFalse(other.acceptAcknowledgement(ack, ack.length));
    }

    @Test public void refusesInvalidFrameLengthRatherThanFragmentingOrSendingPartialSamples() throws Exception {
        MicPacket codec = new MicPacket(ID, KEY);
        for (int length : new int[] {1, 642, -2}) {
            try {
                codec.encode(new byte[642], length);
                fail("Invalid PCM length was accepted: " + length);
            } catch (IllegalArgumentException expected) {}
        }
    }

    static byte[] decrypt(byte[] packet) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(Base64.decode(KEY, Base64.NO_WRAP), "AES"),
                new GCMParameterSpec(128, Arrays.copyOfRange(packet, 20, 32)));
        cipher.updateAAD(packet, 0, 32);
        return cipher.doFinal(packet, 32, packet.length - 32);
    }

    static byte[] acknowledge(byte[] packet) throws Exception {
        byte[] ack = new byte[65];
        System.arraycopy(packet, 0, ack, 0, 32);
        ack[2] = 'A';
        ack[32] = 1;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Base64.decode(KEY, Base64.NO_WRAP), "HmacSHA256"));
        mac.update(ack, 0, 33);
        System.arraycopy(mac.doFinal(), 0, ack, 33, 32);
        return ack;
    }
}
