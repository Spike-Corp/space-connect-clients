package com.limelight.binding.audio;

import android.media.AudioRecord;
import android.os.Looper;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAudioRecord;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class MicForwarderTest {
    private MicForwarder forwarder;
    private final CountDownLatch releaseRead = new CountDownLatch(1);

    @After public void cleanup() throws Exception {
        releaseRead.countDown();
        if (forwarder != null) {
            forwarder.setListener(null);
            forwarder.stop();
            awaitState(MicForwarder.State.STOPPED);
        }
        ShadowAudioRecord.clearSource();
        shadowOf(Looper.getMainLooper()).idle();
    }

    private void awaitState(MicForwarder.State expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (forwarder.getState() != expected && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals(expected, forwarder.getState());
    }

    @Test public void captureSendsExistingProtocolAndReportsRealRecordingState() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        AtomicReference<AudioRecord> recorder = new AtomicReference<>();
        ShadowAudioRecord.setSourceProvider(record -> {
            recorder.set(record);
            return new ShadowAudioRecord.AudioRecordSource() {
                @Override public int readInByteArray(byte[] target, int offset, int size, boolean blocking) {
                    assertFalse(blocking);
                    assertEquals(AudioRecord.RECORDSTATE_RECORDING, record.getRecordingState());
                    if (reads.getAndIncrement() > 0) return 0;
                    target[offset] = 0x34;
                    target[offset + 1] = 0x12;
                    return 2;
                }
            };
        });
        try (DatagramSocket receiver = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            receiver.setSoTimeout(3000);
            forwarder = new MicForwarder(RuntimeEnvironment.getApplication(), "127.0.0.1",
                    receiver.getLocalPort(), null);
            AtomicBoolean active = new AtomicBoolean();
            forwarder.setListener((state, failed) -> {
                assertFalse(failed);
                active.set(state == MicForwarder.State.RUNNING);
            });
            forwarder.start();
            DatagramPacket packet = new DatagramPacket(new byte[64], 64);
            receiver.receive(packet);
            shadowOf(Looper.getMainLooper()).idle();
            assertTrue(active.get());
            assertTrue(forwarder.isRunning());
            assertEquals(14, packet.getLength());
            assertArrayEquals(new byte[] {'S', 'C', 'M', 'B'},
                    java.util.Arrays.copyOfRange(packet.getData(), 0, 4));
            assertEquals(16000, ByteBuffer.wrap(packet.getData(), 8, 4).order(ByteOrder.LITTLE_ENDIAN).getInt());
            assertTrue(forwarder.getCurrentLevel() > 0);
            forwarder.stop();
            awaitState(MicForwarder.State.STOPPED);
            shadowOf(Looper.getMainLooper()).idle();
            assertFalse(active.get());
            assertEquals(AudioRecord.RECORDSTATE_STOPPED, recorder.get().getRecordingState());
        }
    }

    @Test public void captureFailureTurnsOffAndNotifiesUiInsteadOfSpinning() throws Exception {
        ShadowAudioRecord.setSource(new ShadowAudioRecord.AudioRecordSource() {
            @Override public int readInByteArray(byte[] buffer, int offset, int size, boolean blocking) {
                return AudioRecord.ERROR_DEAD_OBJECT;
            }
        });
        AtomicBoolean failure = new AtomicBoolean();
        forwarder = new MicForwarder(RuntimeEnvironment.getApplication(), "127.0.0.1", 48100, null);
        forwarder.setListener((state, failed) -> failure.set(failed));
        forwarder.start();
        awaitState(MicForwarder.State.STOPPED);
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(failure.get());
        assertFalse(forwarder.isRunning());
        assertEquals(0f, forwarder.getCurrentLevel(), 0f);
    }

    @Test public void initializationFailureNeverReportsRunning() throws Exception {
        AtomicBoolean failure = new AtomicBoolean();
        forwarder = new MicForwarder(RuntimeEnvironment.getApplication(), "[", 48100, null);
        forwarder.setListener((state, failed) -> {
            assertNotEquals(MicForwarder.State.RUNNING, state);
            failure.set(failed);
        });
        forwarder.start();
        assertFalse(forwarder.isRunning());
        awaitState(MicForwarder.State.STOPPED);
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(failure.get());
    }

    @Test public void stopDoesNotBlockUiOrAllowOverlappingCaptureWorkers() throws Exception {
        CountDownLatch enteredRead = new CountDownLatch(1);
        Set<AudioRecord> recorders = ConcurrentHashMap.newKeySet();
        ShadowAudioRecord.setSourceProvider(record -> {
            recorders.add(record);
            return new ShadowAudioRecord.AudioRecordSource() {
                @Override public int readInByteArray(byte[] target, int offset, int size, boolean blocking) {
                    enteredRead.countDown();
                    boolean done = false;
                    while (!done) {
                        try {
                            releaseRead.await();
                            done = true;
                        } catch (InterruptedException expected) {
                            // Simulate a device that takes time to release its read operation.
                        }
                    }
                    return 0;
                }
            };
        });
        forwarder = new MicForwarder(RuntimeEnvironment.getApplication(), "127.0.0.1", 48100, null);
        forwarder.start();
        assertTrue(enteredRead.await(3, TimeUnit.SECONDS));
        long started = System.nanoTime();
        forwarder.stop();
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 250);
        assertEquals(MicForwarder.State.STOPPING, forwarder.getState());
        forwarder.start();
        assertEquals(1, recorders.size());
        releaseRead.countDown();
        awaitState(MicForwarder.State.STOPPED);
        forwarder.start();
        awaitState(MicForwarder.State.RUNNING);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (recorders.size() < 2 && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals(2, recorders.size());
    }
}
