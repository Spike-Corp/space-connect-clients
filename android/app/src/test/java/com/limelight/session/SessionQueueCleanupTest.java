package com.limelight.session;

import com.limelight.account.SpaceConnectApiClient;
import com.limelight.account.SpaceConnectApiClient.ConnectionResponse;
import com.limelight.account.SpaceConnectApiClient.StatusResponse;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.utils.RequestScope;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import static org.junit.Assert.*;

public class SessionQueueCleanupTest {
    private static final String MACHINE = "507f1f77bcf86cd799439011";
    private final MockWebServer server = new MockWebServer();
    private final List<SessionOrchestrator> attempts = new ArrayList<>();
    private final List<Backend> backends = new ArrayList<>();
    private final CountDownLatch accepted = new CountDownLatch(1), releasePost = new CountDownLatch(1);
    private final AtomicInteger joins = new AtomicInteger(), deletes = new AtomicInteger(), statuses = new AtomicInteger();
    private final AtomicBoolean queued = new AtomicBoolean(), prematureRetry = new AtomicBoolean();
    private final Queue<RecordedRequest> requests = new ConcurrentLinkedQueue<>();
    private SpaceConnectApiClient client;
    private volatile String state = "idle", stateAfterJoin = "idle";
    private volatile boolean pendingPost, timeoutPost, failFirstDelete;

    @Before public void setup() throws Exception {
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                requests.add(request);
                if ("GET".equals(request.getMethod()) && request.getPath().equals("/status?machineId=" + MACHINE)) {
                    statuses.incrementAndGet();
                    return status(state);
                }
                if ("POST".equals(request.getMethod()) && request.getPath().equals("/queue")) {
                    queued.set(true);
                    if (joins.incrementAndGet() == 1) {
                        state = stateAfterJoin;
                        accepted.countDown();
                        if (pendingPost) releasePost.await(5, TimeUnit.SECONDS);
                        return timeoutPost ? status("ready").setBodyDelay(1, TimeUnit.SECONDS)
                                : new MockResponse().setResponseCode(503).setBody("{\"message\":\"Try later\"}");
                    }
                    prematureRetry.set(deletes.get() != 4);
                    state = "ready";
                    return status(state);
                }
                if ("DELETE".equals(request.getMethod()) && request.getPath().equals("/queue?machineId=" + MACHINE)) {
                    if (deletes.incrementAndGet() == 1 && failFirstDelete) {
                        return new MockResponse().setResponseCode(503);
                    }
                    queued.set(false);
                    return status(state);
                }
                return new MockResponse().setResponseCode(400);
            }
        });
        server.start();
        client = new SpaceConnectApiClient(server.url("/").toString());
    }

    @After public void cleanup() throws Exception {
        releasePost.countDown();
        for (SessionOrchestrator attempt : attempts) attempt.cancel();
        for (Backend backend : backends) {
            if (backend.cleanupStarted.get()) await(backend.cleanupDone);
        }
        server.shutdown();
    }

    @Test public void acceptedPost503DeletesEvenWhenStatusIsIdleAndRetriesFailedDelete() throws Exception {
        failFirstDelete = true;
        Backend backend = new Backend();
        Events events = new Events();
        SessionOrchestrator attempt = attempt(backend, events);
        attempt.start();
        await(backend.cleanupDone);
        events.drain();

        assertEquals(SessionOrchestrator.Stage.FAILED, attempt.getStage());
        assertEquals(503, ((SpaceConnectApiClient.ApiException) backend.joinError).status);
        assertEquals(1, events.failures.get());
        assertCompensated();
    }

    @Test public void acceptedPostTimeoutDeletesEvenWhenServerAlreadyReportsReady() throws Exception {
        timeoutPost = true;
        stateAfterJoin = "ready";
        Constructor<SpaceConnectApiClient> constructor = SpaceConnectApiClient.class
                .getDeclaredConstructor(String.class, OkHttpClient.class);
        constructor.setAccessible(true);
        client = constructor.newInstance(server.url("/").toString(),
                new OkHttpClient.Builder().readTimeout(150, TimeUnit.MILLISECONDS).build());
        Backend backend = new Backend();
        SessionOrchestrator attempt = attempt(backend, new Events());
        attempt.start();
        await(backend.cleanupDone);

        assertEquals(SessionOrchestrator.Stage.FAILED, attempt.getStage());
        assertTrue(backend.joinError instanceof SocketTimeoutException);
        assertEquals("ready", state);
        assertCompensated();
    }

    @Test public void cancelPendingAcceptedPostWaitsForCleanupBeforeImmediateRetryAndDropsLate503() throws Exception {
        pendingPost = true;
        Backend firstBackend = new Backend();
        Events firstEvents = new Events();
        SessionOrchestrator first = attempt(firstBackend, firstEvents);
        first.start();
        await(accepted);
        first.cancel();

        Backend secondBackend = new Backend();
        Events secondEvents = new Events();
        SessionOrchestrator second = attempt(secondBackend, secondEvents);
        second.start();
        await(secondEvents.prepared);
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (second.getStage() != SessionOrchestrator.Stage.WAITING_FOR_FRAME && System.nanoTime() < end) {
            Thread.sleep(1);
        }
        assertEquals(SessionOrchestrator.Stage.WAITING_FOR_FRAME, second.getStage());
        releasePost.countDown();
        await(firstBackend.joinReturned);
        firstEvents.drain();
        secondEvents.drain();

        assertEquals(0, firstEvents.opened.get());
        assertEquals(0, firstEvents.failures.get());
        assertEquals(1, secondEvents.opened.get());
        assertEquals(2, joins.get());
        assertEquals(4, deletes.get());
        assertFalse(prematureRetry.get());
        assertTrue(queued.get());
        assertSame(second, SessionOrchestrator.find(second.id));
        assertScopedRequests();
    }

    private void assertCompensated() {
        assertEquals(1, statuses.get()); // Cleanup cannot depend on a fresh GET or cached state.
        assertEquals(1, joins.get());
        assertEquals(4, deletes.get());
        assertFalse(queued.get());
        assertScopedRequests();
    }

    private void assertScopedRequests() {
        for (RecordedRequest request : requests) {
            if ("POST".equals(request.getMethod())) {
                assertEquals("/queue", request.getPath());
                assertTrue(request.getBody().clone().readUtf8().contains("\"machineId\":\"" + MACHINE + "\""));
            } else {
                assertTrue(request.getPath().equals("/status?machineId=" + MACHINE)
                        || request.getPath().equals("/queue?machineId=" + MACHINE));
            }
        }
    }

    private SessionOrchestrator attempt(Backend backend, Events events) {
        SessionOrchestrator.Host host = new SessionOrchestrator.Host() {
            @Override public SessionOrchestrator.PreparedSession prepare(ConnectionResponse connection, Runnable pairing) {
                events.prepared.countDown();
                return new SessionOrchestrator.PreparedSession(new ComputerDetails(),
                        new NvApp("Desktop", 918, false), "fixture-client", connection, false);
            }
            @Override public void cancel() {}
        };
        SessionOrchestrator attempt = new SessionOrchestrator(MACHINE, false, backend, host, events, events,
                Executors.newSingleThreadScheduledExecutor(), 1);
        attempts.add(attempt);
        backends.add(backend);
        return attempt;
    }

    private static MockResponse status(String state) {
        return new MockResponse().setBody("{\"state\":\"" + state + "\"}");
    }

    private static void await(CountDownLatch latch) throws Exception {
        assertTrue("worker did not finish", latch.await(5, TimeUnit.SECONDS));
    }

    private final class Backend implements SessionOrchestrator.Backend {
        final RequestScope scope = new RequestScope();
        final SpaceConnectApiClient api = client.withScope(scope);
        final AtomicBoolean cleanupStarted = new AtomicBoolean();
        final CountDownLatch cleanupDone = new CountDownLatch(1), joinReturned = new CountDownLatch(1);
        volatile Exception joinError;
        @Override public StatusResponse status() throws Exception { return api.getStatus("fixture", MACHINE); }
        @Override public StatusResponse joinQueue() throws Exception {
            try { return api.joinQueue("fixture", 24, "proxmox", MACHINE); }
            catch (Exception e) { joinError = e; throw e; }
            finally { joinReturned.countDown(); }
        }
        @Override public ConnectionResponse connection() {
            ConnectionResponse connection = new ConnectionResponse();
            connection.machineId = MACHINE;
            return connection;
        }
        @Override public void cancel() { scope.close(); }
        @Override public void cleanupQueue(boolean uncertain) {
            cleanupStarted.set(true);
            RequestScope cleanup = new RequestScope();
            try {
                SessionBackend.cleanupQueue(cleanup, uncertain,
                        () -> client.withScope(cleanup).leaveQueue("fixture", MACHINE), 1);
            } finally { cleanupDone.countDown(); }
        }
    }

    private static final class Events implements SessionOrchestrator.Listener, SessionOrchestrator.Callbacks {
        final Queue<Runnable> actions = new ConcurrentLinkedQueue<>();
        final AtomicInteger opened = new AtomicInteger(), failures = new AtomicInteger();
        final CountDownLatch prepared = new CountDownLatch(1);
        @Override public void post(Runnable action) { actions.add(action); }
        @Override public void clear() { actions.clear(); }
        @Override public void onProgress(SessionOrchestrator.Stage stage, StatusResponse status) {}
        @Override public void onPrepared(SessionOrchestrator.PreparedSession session) { opened.incrementAndGet(); }
        @Override public void onFailure(SessionOrchestrator.Failure failure) { failures.incrementAndGet(); }
        void drain() { Runnable action; while ((action = actions.poll()) != null) action.run(); }
    }
}
