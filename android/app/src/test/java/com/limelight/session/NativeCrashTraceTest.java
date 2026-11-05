package com.limelight.session;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class NativeCrashTraceTest {
    @Test public void keepsOnlyTheCrashingThreadsNativeFramesWithoutMemoryLogsOrPaths() throws Exception {
        byte[] frame = join(number(1, 0x1234), text(4, "AudioRecord_start"), number(5, 16),
                text(6, "/data/private-token/lib64/libaudio.so"), text(8, "aabb0011"));
        byte[] crashed = join(number(1, 42), bytes(4, frame), text(5, "voice-memory-private-token"));
        byte[] other = join(number(1, 41), bytes(4, join(number(1, 99), text(4, "unrelated_thread"))));
        byte[] trace = join(bytes(16, join(number(1, 41), bytes(2, other))),
                bytes(16, join(number(1, 42), bytes(2, crashed))),
                number(6, 42), text(9, "account-and-token"), text(18, "secret-log"));
        String report = NativeCrashTrace.read(trace);
        assertTrue(report, report.contains("pc 1234 libaudio.so AudioRecord_start+16 build=aabb0011"));
        for (String excluded : new String[] {"private-token", "voice-memory", "account-and-token", "secret-log", "unrelated_thread", "/data/"})
            assertFalse(excluded, report.contains(excluded));
    }

    @Test public void malformedLengthsFailExplicitlyInsteadOfReturningAValidLookingTrace() {
        assertThrows(IOException.class, () -> NativeCrashTrace.read(new byte[] {(byte) 130, 1, 10, 0}));
    }

    private static byte[] number(int field, long value) {
        return join(varint(field << 3), varint(value));
    }
    private static byte[] text(int field, String value) {
        return bytes(field, value.getBytes(StandardCharsets.UTF_8));
    }
    private static byte[] bytes(int field, byte[] value) {
        return join(varint((field << 3) | 2), varint(value.length), value);
    }
    private static byte[] varint(long value) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        while ((value & ~0x7fL) != 0) { output.write((int) value | 0x80); value >>>= 7; }
        output.write((int) value);
        return output.toByteArray();
    }
    private static byte[] join(byte[]... values) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] value : values) output.write(value, 0, value.length);
        return output.toByteArray();
    }
}
