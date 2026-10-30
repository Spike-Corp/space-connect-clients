package com.limelight.session;

import com.limelight.account.SpaceConnectApiClient.ConnectionResponse;
import com.limelight.account.SpaceConnectApiClient.QueueStatus;
import com.limelight.account.SpaceConnectApiClient.StatusResponse;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class SessionOrchestratorTest {
    private static final String MACHINE = "507f1f77bcf86cd799439011";
    private final List<SessionOrchestrator> attempts = new ArrayList<>();
    private final List<CountDownLatch> gates = new ArrayList<>();

    @After public void cleanup() {
        for (CountDownLatch gate : gates) gate.countDown();
        for (SessionOrchestrator attempt : attempts) attempt.cancel();
    }

    @Test public void queueAndBootAutomaticallyReachDesktopButWaitForRenderedFrame() throws Exception {
        Backend backend = new Backend();
        backend.statuses.add(status("idle"));
        backend.statuses.add(status("starting"));
        backend.statuses.add(status("ready"));
        Host host = new Host();
        Listener listener = new Listener();
        Callbacks callbacks = new Callbacks();
        SessionOrchestrator attempt = attempt(backend, host, callbacks, listener);
        attempt.start();
        await(host.prepared);
        awaitStage(attempt, SessionOrchestrator.Stage.WAITING_FOR_FRAME);
        callbacks.drain();

        assertEquals(1, backend.joins.get());
        assertEquals(1, listener.opened.get());
        assertTrue(listener.stages.contains(SessionOrchestrator.Stage.QUEUED));
        assertTrue(listener.stages.contains(SessionOrchestrator.Stage.STARTING));
        assertEquals(4, listener.position);
        assertEquals(7, listener.total);
        assertEquals(SessionOrchestrator.Stage.WAITING_FOR_FRAME, attempt.getStage());
        AtomicInteger stops = new AtomicInteger();
        assertTrue(attempt.attachStream(stops::incrementAndGet));
        assertTrue(attempt.onFirstFrameRendered());
        assertFalse(attempt.onFirstFrameRendered());
        assertEquals(SessionOrchestrator.Stage.STREAMING, attempt.getStage());
        attempt.cancel();
        attempt.cancel();
        assertFalse(attempt.onFirstFrameRendered());
        assertEquals(1, stops.get());
        assertEquals(0, backend.cleanups.get());
    }

    @Test public void cancelledAcceptedJoinIsCompensatedAndLateReadyCannotLaunch() throws Exception {
        Backend backend = new Backend();
        backend.statuses.add(status("idle"));
        backend.joinGate = gate();
        backend.joinResult = status("ready");
        Host host = new Host();
        Listener listener = new Listener();
        Callbacks callbacks = new Callbacks();
        SessionOrchestrator attempt = attempt(backend, host, callbacks, listener);
        attempt.start();
        await(backend.joinEntered);
        attempt.cancel();
        attempt.cancel();
        await(backend.cleanupDone);
        backend.joinGate.countDown();
        await(backend.joinReturned);
        callbacks.drain();
        assertEquals(1, backend.cleanups.get());
        assertTrue(backend.uncertainCleanup);
        assertEquals(0, backend.connections.get());
        assertEquals(0, listener.opened.get());
        assertFalse(attempt.onFirstFrameRendered());
        assertNull(SessionOrchestrator.find(attempt.id));
    }

    @Test public void cancelledHandshakeDiscardsNativeSuccessAndDoesNotLeaveOrStopVm() throws Exception {
        Backend backend = new Backend();
        backend.statuses.add(status("ready"));
        Host host = new Host();
        host.handshakeGate = gate();
        Listener listener = new Listener();
        Callbacks callbacks = new Callbacks();
        SessionOrchestrator attempt = attempt(backend, host, callbacks, listener);
        attempt.start();
        await(host.entered);
        attempt.cancel();

        Backend retryBackend = new Backend();
        retryBackend.statuses.add(status("ready"));
        Host retryHost = new Host();
        Listener retryListener = new Listener();
        Callbacks retryCallbacks = new Callbacks();
        SessionOrchestrator retry = attempt(retryBackend, retryHost, retryCallbacks, retryListener);
        retry.start();
        await(retryHost.prepared);
        awaitStage(retry, SessionOrchestrator.Stage.WAITING_FOR_FRAME);
        host.handshakeGate.countDown();
        await(host.prepared);
        callbacks.drain();
        retryCallbacks.drain();
        assertEquals(1, host.cancels.get());
        assertEquals(1, backend.cancels.get());
        assertEquals(0, backend.cleanups.get());
        assertEquals(0, listener.opened.get());
        assertEquals(1, retryListener.opened.get());
        assertSame(retry, SessionOrchestrator.find(retry.id));
        assertFalse(attempt.attachStream(() -> fail("cancelled stream attached")));
    }

    @Test public void newEpochWaitsForOldQueueCleanupAndDropsOldHandoff() throws Exception {
        Backend firstBackend = new Backend();
        firstBackend.statuses.add(status("idle"));
        firstBackend.joinGate = gate();
        firstBackend.cleanupGate = gate();
        Callbacks firstCallbacks = new Callbacks();
        Listener firstListener = new Listener();
        SessionOrchestrator first = attempt(firstBackend, new Host(), firstCallbacks, firstListener);
        first.start();
        await(firstBackend.joinEntered);

        Backend secondBackend = new Backend();
        secondBackend.statuses.add(status("ready"));
        Host secondHost = new Host();
        Callbacks secondCallbacks = new Callbacks();
        Listener secondListener = new Listener();
        SessionOrchestrator second = attempt(secondBackend, secondHost, secondCallbacks, secondListener);
        second.start();
        await(firstBackend.cleanupEntered);
        assertFalse(secondBackend.statusEntered.await(80, TimeUnit.MILLISECONDS));
        firstBackend.joinGate.countDown();
        firstBackend.cleanupGate.countDown();
        await(secondHost.prepared);
        awaitStage(second, SessionOrchestrator.Stage.WAITING_FOR_FRAME);
        firstCallbacks.drain();
        secondCallbacks.drain();
        assertEquals(0, firstListener.opened.get());
        assertEquals(1, secondListener.opened.get());
        assertSame(second, SessionOrchestrator.find(second.id));
        assertFalse(first.onFirstFrameRendered());
    }

    @Test public void anotherMachinesReadyStatusCannotConnect() throws Exception {
        Backend backend = new Backend();
        StatusResponse ready = status("ready");
        ready.session = new com.limelight.account.SpaceConnectApiClient.SessionStatus();
        ready.session.machine = new com.limelight.account.SpaceConnectApiClient.MachineStatus();
        ready.session.machine.id = "507f1f77bcf86cd799439022";
        backend.statuses.add(ready);
        Callbacks callbacks = new Callbacks();
        Listener listener = new Listener();
        SessionOrchestrator attempt = attempt(backend, new Host(), callbacks, listener);
        attempt.start();
        awaitStage(attempt, SessionOrchestrator.Stage.FAILED);
        callbacks.drain();
        assertEquals(0, backend.connections.get());
        assertEquals(0, listener.opened.get());
    }

    private CountDownLatch gate() {
        CountDownLatch gate = new CountDownLatch(1);
        gates.add(gate);
        return gate;
    }

    private SessionOrchestrator attempt(Backend backend, Host host, Callbacks callbacks, Listener listener) {
        SessionOrchestrator attempt = new SessionOrchestrator(MACHINE, false, backend, host, callbacks,
                listener, Executors.newSingleThreadScheduledExecutor(), 1);
        attempts.add(attempt);
        return attempt;
    }

    private static StatusResponse status(String state) {
        StatusResponse status = new StatusResponse();
        status.state = state;
        if ("queued".equals(state)) {
            status.queue = new QueueStatus();
            status.queue.position = 4;
            status.queue.total = 7;
        }
        return status;
    }

    private static void await(CountDownLatch latch) throws Exception {
        assertTrue("worker did not reach checkpoint", latch.await(3, TimeUnit.SECONDS));
    }

    private static void awaitStage(SessionOrchestrator attempt, SessionOrchestrator.Stage stage) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (attempt.getStage() != stage && System.nanoTime() < end) Thread.sleep(1);
        assertEquals(stage, attempt.getStage());
    }

    private static void awaitIgnoringInterrupts(CountDownLatch gate) {
        if (gate == null) return;
        boolean interrupted = false;
        while (true) {
            try { gate.await(); break; } catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static class Backend implements SessionOrchestrator.Backend {
        final Queue<StatusResponse> statuses = new ConcurrentLinkedQueue<>();
        final AtomicInteger joins = new AtomicInteger(), connections = new AtomicInteger(),
                cancels = new AtomicInteger(), cleanups = new AtomicInteger();
        final CountDownLatch statusEntered = new CountDownLatch(1), joinEntered = new CountDownLatch(1),
                joinReturned = new CountDownLatch(1), cleanupEntered = new CountDownLatch(1), cleanupDone = new CountDownLatch(1);
        CountDownLatch joinGate, cleanupGate;
        StatusResponse joinResult = SessionOrchestratorTest.status("queued");
        volatile boolean uncertainCleanup;

        @Override public StatusResponse status() {
            statusEntered.countDown();
            StatusResponse status = statuses.poll();
            return status == null ? SessionOrchestratorTest.status("queued") : status;
        }
        @Override public StatusResponse joinQueue() {
            joins.incrementAndGet();
            joinEntered.countDown();
            awaitIgnoringInterrupts(joinGate);
            joinReturned.countDown();
            return joinResult;
        }
        @Override public ConnectionResponse connection() {
            connections.incrementAndGet();
            ConnectionResponse connection = new ConnectionResponse();
            connection.machineId = MACHINE;
            return connection;
        }
        @Override public void cancel() { cancels.incrementAndGet(); }
        @Override public void cleanupQueue(boolean uncertain) {
            cleanups.incrementAndGet();
            uncertainCleanup = uncertain;
            cleanupEntered.countDown();
            awaitIgnoringInterrupts(cleanupGate);
            cleanupDone.countDown();
        }
    }

    private static class Host implements SessionOrchestrator.Host {
        final CountDownLatch entered = new CountDownLatch(1), prepared = new CountDownLatch(1);
        final AtomicInteger cancels = new AtomicInteger();
        CountDownLatch handshakeGate;
        @Override public SessionOrchestrator.PreparedSession prepare(ConnectionResponse connection, Runnable onPairing) {
            onPairing.run();
            entered.countDown();
            awaitIgnoringInterrupts(handshakeGate);
            SessionOrchestrator.PreparedSession result = new SessionOrchestrator.PreparedSession(
                    new ComputerDetails(), new NvApp("Desktop", 918, false), "test-client", connection, true);
            prepared.countDown();
            return result;
        }
        @Override public void cancel() { cancels.incrementAndGet(); }
    }

    private static class Callbacks implements SessionOrchestrator.Callbacks {
        final Queue<Runnable> pending = new ConcurrentLinkedQueue<>();
        @Override public void post(Runnable action) { pending.add(action); }
        @Override public void clear() { pending.clear(); }
        void drain() { Runnable task; while ((task = pending.poll()) != null) task.run(); }
    }

    private static class Listener implements SessionOrchestrator.Listener {
        final AtomicInteger opened = new AtomicInteger();
        final List<SessionOrchestrator.Stage> stages = new ArrayList<>();
        int position, total;
        @Override public void onProgress(SessionOrchestrator.Stage stage, StatusResponse status) {
            stages.add(stage);
            if (status != null && status.queue != null) {
                position = status.queue.position;
                total = status.queue.total;
            }
        }
        @Override public void onPrepared(SessionOrchestrator.PreparedSession session) { opened.incrementAndGet(); }
        @Override public void onFailure(SessionOrchestrator.Failure failure) {}
    }
}
