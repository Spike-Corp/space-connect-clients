package com.limelight.session;

import android.content.Context;

import com.limelight.account.AccountManager;
import com.limelight.account.SpaceConnectApiClient.ConnectionResponse;
import com.limelight.account.SpaceConnectApiClient.StatusResponse;
import com.limelight.utils.RequestScope;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

final class SessionBackend implements SessionOrchestrator.Backend {
    final AccountManager.SessionRequests requests;

    SessionBackend(Context context, String machineId, boolean friend, RequestScope scope) {
        this(context, machineId, friend, scope, AccountManager.currentAccountGeneration());
    }

    SessionBackend(Context context, String machineId, boolean friend, RequestScope scope, long generation) {
        requests = AccountManager.sessionRequests(context, machineId, friend, scope, generation);
    }

    @Override public StatusResponse status() throws Exception { return requests.status(); }
    @Override public StatusResponse joinQueue() throws Exception { return requests.joinQueue(); }
    @Override public ConnectionResponse connection() throws Exception { return requests.connection(); }
    @Override public void cancel() { requests.close(); }
    @Override public AccountManager.StreamRequests streamRequests(RequestScope scope) {
        return requests.withScope(scope);
    }

    @Override
    public void cleanupQueue(boolean joinUncertain) {
        RequestScope scope = new RequestScope();
        try (RequestScope cleanup = scope) {
            cleanupQueue(scope, joinUncertain, () -> requests.removeQueuedAttempt(cleanup), 1000);
        }
    }

    interface QueueRemoval {
        void remove() throws Exception;
    }

    static void cleanupQueue(RequestScope scope, boolean joinUncertain, QueueRemoval removal, long retryMillis) {
        Thread cleanupThread = Thread.currentThread();
        ScheduledExecutorService deadline = Executors.newSingleThreadScheduledExecutor(
                r -> new Thread(r, "Session-cleanup-deadline"));
        deadline.schedule(() -> {
            scope.close();
            cleanupThread.interrupt();
        }, 15, TimeUnit.SECONDS);
        try {
            // A lost POST response is ambiguous even if status still says idle or ready.
            // DELETE removes only this machine's queue entry, never its running session.
            int checks = joinUncertain ? 4 : 1;
            for (int i = 0; i < checks; i++) {
                if (i > 0) Thread.sleep(retryMillis << (i - 1));
                scope.checkCancelled();
                try {
                    removal.remove();
                } catch (Exception e) {
                    scope.checkCancelled();
                    SessionLog.failure("queue removal", e);
                }
            }
        } catch (Exception e) {
            SessionLog.failure("queue cleanup", e);
        } finally {
            scope.close();
            deadline.shutdownNow();
        }
    }
}
