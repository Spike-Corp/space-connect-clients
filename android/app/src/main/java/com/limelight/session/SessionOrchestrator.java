package com.limelight.session;

import com.limelight.account.SpaceConnectApiClient.ConnectionResponse;
import com.limelight.account.SpaceConnectApiClient.StatusResponse;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** One cancellable journey from the dashboard through the first rendered frame. */
public final class SessionOrchestrator {
    public enum Stage { CHECKING, QUEUED, STARTING, CONNECTING, PAIRING, WAITING_FOR_FRAME, STREAMING, FAILED, CANCELLED }
    public enum Failure { CONNECTION, PAIRING, DESKTOP_MISSING, HOST_BUSY }

    public interface Backend {
        StatusResponse status() throws Exception;
        StatusResponse joinQueue() throws Exception;
        ConnectionResponse connection() throws Exception;
        void cancel();
        // Independent, bounded I/O: only reconcile/leave this attempt's queue, never end a session.
        void cleanupQueue(boolean joinUncertain);
    }

    public interface Host {
        PreparedSession prepare(ConnectionResponse connection, Runnable onPairing) throws Exception;
        void cancel();
    }

    public interface Listener {
        void onProgress(Stage stage, StatusResponse status);
        void onPrepared(PreparedSession session);
        void onFailure(Failure failure);
    }

    public interface Callbacks {
        void post(Runnable action);
        void clear();
    }

    public static final class PreparedSession {
        public final ComputerDetails computer;
        public final NvApp desktop;
        public final String uniqueId;
        public final ConnectionResponse connection;
        public final boolean newlyPaired;

        public PreparedSession(ComputerDetails computer, NvApp desktop, String uniqueId,
                ConnectionResponse connection, boolean newlyPaired) {
            this.computer = computer;
            this.desktop = desktop;
            this.uniqueId = uniqueId;
            this.connection = connection;
            this.newlyPaired = newlyPaired;
        }
    }

    public static final class SessionException extends Exception {
        public final Failure failure;
        public SessionException(Failure failure) {
            super(failure.name());
            this.failure = failure;
        }
    }

    private static final Object REGISTRY_LOCK = new Object();
    private static volatile SessionOrchestrator active;
    private static CompletableFuture<Void> previousCleanup = CompletableFuture.completedFuture(null);

    public final String id = UUID.randomUUID().toString();
    public final String machineId;
    private final boolean friend;
    private final Backend backend;
    private final Host host;
    private final Callbacks callbacks;
    private final ScheduledExecutorService worker;
    private final long pollMillis;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean resourcesClosed = new AtomicBoolean();
    private volatile Listener listener;
    private volatile Stage stage = Stage.CHECKING;
    private volatile StatusResponse status;
    private boolean started;
    private boolean queueMayExist;
    private boolean joinUncertain;
    private boolean joined;
    private Runnable streamCancellation;

    public SessionOrchestrator(String machineId, boolean friend, Backend backend, Host host,
            Callbacks callbacks, Listener listener) {
        this(machineId, friend, backend, host, callbacks, listener,
                Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "Session-startup")), 5000);
    }

    SessionOrchestrator(String machineId, boolean friend, Backend backend, Host host,
            Callbacks callbacks, Listener listener, ScheduledExecutorService worker, long pollMillis) {
        if (machineId == null || machineId.trim().isEmpty()) throw new IllegalArgumentException("Missing machine");
        this.machineId = machineId;
        this.friend = friend;
        this.backend = backend;
        this.host = host;
        this.callbacks = callbacks;
        this.listener = listener;
        this.worker = worker;
        this.pollMillis = pollMillis;
    }

    public static SessionOrchestrator find(String id) {
        SessionOrchestrator current = active;
        return current != null && current.id.equals(id) && current.isActive() ? current : null;
    }

    public void start() {
        synchronized (REGISTRY_LOCK) {
            if (started || cancelled.get()) return;
            started = true;
            if (active != null) active.cancel();
            CompletableFuture<Void> cleanup = previousCleanup;
            active = this;
            publish(Stage.CHECKING, null);
            worker.execute(() -> {
                try {
                    // A previous DELETE must not remove a new join for the same machine.
                    cleanup.get();
                    if (!isActive()) return;
                    if (friend) prepare();
                    else poll();
                } catch (Exception e) {
                    fail(e);
                }
            });
        }
    }

    public boolean isActive() {
        return active == this && !cancelled.get() && stage != Stage.FAILED;
    }

    public Stage getStage() {
        synchronized (REGISTRY_LOCK) { return stage; }
    }

    private void poll() {
        try {
            if (!isActive()) return;
            StatusResponse next = backend.status();
            if (!isActive()) return;
            if (next != null && "idle".equals(next.state) && !joined) {
                synchronized (REGISTRY_LOCK) {
                    if (!isActive()) return;
                    // Set this BEFORE the POST: cancellation can lose its response after acceptance.
                    joined = true;
                    joinUncertain = queueMayExist = true;
                }
                next = backend.joinQueue();
                synchronized (REGISTRY_LOCK) {
                    if (!isActive()) return;
                    joinUncertain = false;
                }
            }
            acceptStatus(next);
        } catch (Exception e) {
            fail(e);
        }
    }

    private void acceptStatus(StatusResponse next) throws Exception {
        if (next == null) throw new SessionException(Failure.CONNECTION);
        synchronized (REGISTRY_LOCK) {
            if (!isActive()) return;
            if (next.session != null && next.session.machine != null
                    && next.session.machine.id != null && !machineId.equals(next.session.machine.id)) {
                throw new SessionException(Failure.CONNECTION);
            }
            if (next.queue != null && next.queue.machineId != null && !machineId.equals(next.queue.machineId)) {
                throw new SessionException(Failure.CONNECTION);
            }
            status = next;
            if ("queued".equals(next.state)) {
                queueMayExist = true;
                publish(Stage.QUEUED, next);
            } else if ("starting".equals(next.state) || "booting".equals(next.state)) {
                queueMayExist = false;
                publish(Stage.STARTING, next);
            } else if ("ready".equals(next.state)) {
                queueMayExist = false;
            } else {
                throw new SessionException(Failure.CONNECTION);
            }
        }
        if ("ready".equals(next.state)) {
            prepare();
        } else if (isActive()) {
            worker.schedule(this::poll, pollMillis, TimeUnit.MILLISECONDS);
        }
    }

    private void prepare() throws Exception {
        publish(Stage.CONNECTING, status);
        if (!isActive()) return;
        ConnectionResponse connection = backend.connection();
        if (!isActive()) return;
        if (connection == null || (connection.machineId != null && !machineId.equals(connection.machineId))) {
            throw new SessionException(Failure.CONNECTION);
        }
        PreparedSession prepared = host.prepare(connection, () -> publish(Stage.PAIRING, status));
        synchronized (REGISTRY_LOCK) {
            if (!isActive()) return;
            publish(Stage.WAITING_FOR_FRAME, status);
            callbacks.post(() -> {
                if (isActive() && listener != null) listener.onPrepared(prepared);
            });
            worker.shutdown();
        }
    }

    private void publish(Stage next, StatusResponse nextStatus) {
        synchronized (REGISTRY_LOCK) {
            if (!isActive()) return;
            stage = next;
            callbacks.post(() -> {
                if (isActive() && listener != null) listener.onProgress(next, nextStatus);
            });
        }
    }

    public void detachListener() {
        listener = null;
        callbacks.clear();
    }

    public boolean attachStream(Runnable cancelStream) {
        synchronized (REGISTRY_LOCK) {
            if (!isActive() || stage != Stage.WAITING_FOR_FRAME || streamCancellation != null) return false;
            streamCancellation = cancelStream;
            return true;
        }
    }

    /** Called only by MediaCodec.OnFrameRenderedListener, never connectionStarted/submit/release. */
    public boolean onFirstFrameRendered() {
        synchronized (REGISTRY_LOCK) {
            if (!isActive() || stage != Stage.WAITING_FOR_FRAME || streamCancellation == null) return false;
            stage = Stage.STREAMING;
            return true;
        }
    }

    private void fail(Exception error) {
        synchronized (REGISTRY_LOCK) {
            if (!isActive()) return;
            Failure failure = error instanceof SessionException
                    ? ((SessionException) error).failure
                    : stage == Stage.PAIRING ? Failure.PAIRING : Failure.CONNECTION;
            SessionLog.failure(stage.name(), error);
            stage = Stage.FAILED;
            closeResources();
            scheduleQueueCleanup();
            callbacks.post(() -> {
                if (active == this && !cancelled.get() && listener != null) listener.onFailure(failure);
            });
        }
    }

    public void cancel() {
        synchronized (REGISTRY_LOCK) {
            if (!cancelled.compareAndSet(false, true)) return;
            stage = Stage.CANCELLED;
            detachListener();
            closeResources();
            if (streamCancellation != null) {
                streamCancellation.run();
                streamCancellation = null;
            }
            scheduleQueueCleanup();
            if (active == this) active = null;
        }
    }

    private void closeResources() {
        if (!resourcesClosed.compareAndSet(false, true)) return;
        backend.cancel();
        host.cancel();
        worker.shutdownNow();
    }

    private void scheduleQueueCleanup() {
        if (!queueMayExist || friend) return;
        queueMayExist = false;
        boolean uncertain = joinUncertain;
        CompletableFuture<Void> complete = new CompletableFuture<>();
        previousCleanup = complete;
        ExecutorService cleanup = Executors.newSingleThreadExecutor(r -> new Thread(r, "Session-queue-cleanup"));
        cleanup.execute(() -> {
            try {
                backend.cleanupQueue(uncertain);
            } finally {
                complete.complete(null);
                cleanup.shutdown();
            }
        });
    }
}
