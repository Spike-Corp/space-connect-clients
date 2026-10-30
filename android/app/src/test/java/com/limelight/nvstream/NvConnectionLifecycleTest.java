package com.limelight.nvstream;

import com.limelight.nvstream.av.audio.AudioRenderer;
import com.limelight.nvstream.av.video.VideoDecoderRenderer;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.HostHttpResponseException;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.jni.MoonBridge;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = NvConnectionLifecycleTest.Bridge.class)
public class NvConnectionLifecycleTest {
    private final List<Connection> connections = new ArrayList<>();

    @Before public void setup() {
        Bridge.starts.set(0);
        Bridge.stops.set(0);
        Bridge.interrupts.set(0);
        Bridge.nativeGate = null;
        Bridge.entered = new CountDownLatch(1);
    }

    @After public void cleanup() {
        if (Bridge.nativeGate != null) Bridge.nativeGate.countDown();
        for (Connection connection : connections) {
            if (connection.bootstrapGate != null) connection.bootstrapGate.countDown();
            connection.stop();
        }
    }

    @Test public void cancelledHttpStartupAndDuplicateStopCannotInterruptNextConnectionsAudio() throws Exception {
        Connection cancelled = connection();
        cancelled.bootstrapGate = new CountDownLatch(1);
        Listener oldListener = new Listener();
        cancelled.start(new Audio(), new Video(), oldListener);
        await(cancelled.entered);
        cancelled.stop();
        cancelled.bootstrapGate.countDown();
        await(cancelled.exited);
        assertEquals(0, Bridge.starts.get());

        Connection current = connection();
        Audio audio = new Audio();
        Listener listener = new Listener();
        current.start(audio, new Video(), listener);
        await(listener.connected);
        assertEquals(1, audio.starts.get());
        assertEquals(0, audio.stops.get());
        // There is no rendered-frame signal in this test: audio is already running.
        cancelled.stop();
        assertEquals(0, Bridge.stops.get());
        assertEquals(0, audio.stops.get());
        assertEquals(1L, oldListener.connected.getCount());
        current.stop();
        current.stop();
        assertEquals(1, Bridge.stops.get());
        assertEquals(1, audio.stops.get());
    }

    @Test public void cancelDuringNativeHandshakeDiscardsLateConnectionStartedAndStopsOnlyOnce() throws Exception {
        Bridge.nativeGate = new CountDownLatch(1);
        Connection connection = connection();
        Listener listener = new Listener();
        Audio audio = new Audio();
        connection.start(audio, new Video(), listener);
        await(Bridge.entered);
        connection.interrupt();
        ExecutorService stopper = Executors.newSingleThreadExecutor();
        try {
            Future<?> stopped = stopper.submit(connection::stop);
            Bridge.nativeGate.countDown();
            stopped.get(3, TimeUnit.SECONDS);
            assertEquals(1L, listener.connected.getCount());
            assertTrue(Bridge.interrupts.get() >= 1);
            assertEquals(1, Bridge.stops.get());
            assertEquals(1, audio.stops.get());
        } finally {
            stopper.shutdownNow();
        }
    }

    @Test public void launch403RetriesOnlyWithFreshPairAuthorizationWindow() throws Exception {
        Connection fresh = new Connection(20_000);
        connections.add(fresh);
        fresh.forbidden = 1;
        Listener listener = new Listener();
        fresh.start(new Audio(), new Video(), listener);
        await(listener.connected);
        assertEquals(2, fresh.bootstrapCalls.get());
        fresh.stop();

        Connection established = connection();
        established.forbidden = 1;
        Listener rejected = new Listener();
        established.start(new Audio(), new Video(), rejected);
        await(rejected.failed);
        assertEquals(1, established.bootstrapCalls.get());
        assertEquals(1L, rejected.connected.getCount());
        assertEquals(1, Bridge.starts.get());
    }

    private Connection connection() {
        Connection connection = new Connection();
        connections.add(connection);
        return connection;
    }

    @Test public void unexpectedBootstrapFailureReleasesOwnershipAndAllowsNextConnection() throws Exception {
        Connection failed = connection();
        failed.bootstrapFailure = new IllegalStateException("Simulated bootstrap failure");
        Listener listener = new Listener();
        failed.start(new Audio(), new Video(), listener);
        await(listener.failed);
        assertEquals(1L, listener.connected.getCount());

        Connection next = connection();
        Listener connected = new Listener();
        next.start(new Audio(), new Video(), connected);
        await(connected.connected);
        assertEquals(1, Bridge.starts.get());
        assertEquals(0, Bridge.stops.get());
    }

    private static void await(CountDownLatch latch) throws Exception {
        assertTrue(latch.await(3, TimeUnit.SECONDS));
    }

    private static class Connection extends NvConnection {
        final CountDownLatch entered = new CountDownLatch(1), exited = new CountDownLatch(1);
        final AtomicInteger bootstrapCalls = new AtomicInteger();
        CountDownLatch bootstrapGate;
        int forbidden;
        RuntimeException bootstrapFailure;
        Connection() { this(0); }
        Connection(int authorizationWaitMs) {
            super(RuntimeEnvironment.getApplication(), new ComputerDetails.AddressTuple("127.0.0.1", 47989),
                    47984, "test-client", new StreamConfiguration.Builder()
                            .setApp(new NvApp("Desktop", 918, false))
                            .setHostAuthorizationWaitMs(authorizationWaitMs)
                            .setAudioConfiguration(new MoonBridge.AudioConfiguration(2, 3)).build(), null, null);
        }
        @Override protected boolean startApp() throws IOException {
            bootstrapCalls.incrementAndGet();
            entered.countDown();
            try {
                if (bootstrapGate != null) bootstrapGate.await();
                if (bootstrapFailure != null) throw bootstrapFailure;
                if (forbidden-- > 0) throw new HostHttpResponseException(403, "Pending authorization");
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("cancelled");
            } finally {
                exited.countDown();
            }
        }
    }

    @Implements(value = MoonBridge.class, isInAndroidSdk = false)
    public static class Bridge {
        static final AtomicInteger starts = new AtomicInteger(), stops = new AtomicInteger(), interrupts = new AtomicInteger();
        static CountDownLatch nativeGate, entered;
        @Implementation protected static void __staticInitializer__() {}
        @Implementation protected static void interruptConnection() { interrupts.incrementAndGet(); }
        @Implementation protected static void stopConnection() {
            stops.incrementAndGet();
            MoonBridge.bridgeArStop();
            MoonBridge.bridgeArCleanup();
        }
        @Implementation protected static int startConnection(String address, String appVersion, String gfeVersion,
                String rtspSessionUrl, int serverCodecModeSupport, int width, int height, int fps,
                int bitrate, int packetSize, int streamingRemotely, int audioConfiguration,
                int supportedVideoFormats, int clientRefreshRateX100, byte[] key, byte[] iv,
                int videoCapabilities, int colorSpace, int colorRange) {
            starts.incrementAndGet();
            MoonBridge.bridgeArStart();
            entered.countDown();
            if (nativeGate != null) {
                boolean interrupted = false;
                while (true) {
                    try { nativeGate.await(); break; } catch (InterruptedException ignored) { interrupted = true; }
                }
                if (interrupted) Thread.currentThread().interrupt();
            }
            MoonBridge.bridgeClConnectionStarted();
            return 0;
        }
    }

    private static class Audio implements AudioRenderer {
        final AtomicInteger starts = new AtomicInteger(), stops = new AtomicInteger();
        @Override public int setup(MoonBridge.AudioConfiguration config, int rate, int samples) { return 0; }
        @Override public void start() { starts.incrementAndGet(); }
        @Override public void stop() { stops.incrementAndGet(); }
        @Override public void playDecodedAudio(short[] data) {}
        @Override public void cleanup() {}
    }

    private static class Video extends VideoDecoderRenderer {
        @Override public int setup(int format, int width, int height, int fps) { return 0; }
        @Override public void start() {}
        @Override public void stop() {}
        @Override public int submitDecodeUnit(byte[] data, int length, int type, int number, int frameType,
                char latency, long receiveMs, long enqueueMs) { return 0; }
        @Override public void cleanup() {}
        @Override public int getCapabilities() { return 0; }
        @Override public void setHdrMode(boolean enabled, byte[] metadata) {}
    }

    private static class Listener implements NvConnectionListener {
        final CountDownLatch connected = new CountDownLatch(1);
        final CountDownLatch failed = new CountDownLatch(1);
        @Override public void stageStarting(String stage) {}
        @Override public void stageComplete(String stage) {}
        @Override public void stageFailed(String stage, int flags, int error) { failed.countDown(); }
        @Override public void connectionStarted() { connected.countDown(); }
        @Override public void connectionTerminated(int error) {}
        @Override public void connectionStatusUpdate(int status) {}
        @Override public void displayMessage(String message) {}
        @Override public void displayTransientMessage(String message) {}
        @Override public void rumble(short controller, short low, short high) {}
        @Override public void rumbleTriggers(short controller, short left, short right) {}
        @Override public void setHdrMode(boolean enabled, byte[] metadata) {}
        @Override public void setMotionEventState(short controller, byte type, short rate) {}
        @Override public void setControllerLED(short controller, byte r, byte g, byte b) {}
    }
}
