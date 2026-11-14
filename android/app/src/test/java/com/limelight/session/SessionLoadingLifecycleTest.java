package com.limelight.session;

import android.app.Application;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.widget.TextView;
import android.widget.Button;

import com.limelight.Game;
import com.limelight.R;
import com.limelight.account.AccountManager;
import com.limelight.account.LauncherActivity;
import com.limelight.account.LauncherPollingTest;
import com.limelight.account.SpaceConnectApiClient;
import com.limelight.computers.ComputerManagerService;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.jni.MoonBridge;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Delayed;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = SessionLoadingLifecycleTest.NativeBridge.class)
public class SessionLoadingLifecycleTest {
    private static final String MACHINE = "507f1f77bcf86cd799439011";

    @Implements(MoonBridge.class)
    public static class NativeBridge {
        @Implementation protected static void __staticInitializer__() {}
    }

    @Test @Config(sdk = {24, 28, 35})
    public void loadingActivityInflatesWithItsOwnManifestTheme() {
        ActivityController<SessionLoadingActivity> loading = Robolectric.buildActivity(
                SessionLoadingActivity.class);
        try {
            // No machine extra: exercise inflation without creating a backend session.
            loading.create();
            TextView wordmark = loading.get().findViewById(R.id.sessionWordmark);
            TextView tip = loading.get().findViewById(R.id.sessionTip);
            assertEquals(loading.get().getString(R.string.login_app_name), wordmark.getText().toString());
            assertFalse(tip.getText().toString().isEmpty());
            assertNull(loading.get().getActionBar());
        } finally {
            loading.destroy();
        }
    }

    @Test public void visibleOverlayKeepsItsBindingAndPollsPastSixMinutesWithLauncherPaused() {
        Application app = RuntimeEnvironment.getApplication();
        Settings.Global.putFloat(app.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 0);
        shadowOf(app).setComponentNameAndServiceForBindService(
                new ComponentName(app, ComputerManagerService.class),
                new ComputerManagerService().onBind(null));
        ActivityController<LauncherPollingTest.Launcher> dashboard =
                Robolectric.buildActivity(LauncherPollingTest.Launcher.class).setup();
        dashboard.pause().stop();
        ActivityController<Loading> loading = Robolectric.buildActivity(Loading.class,
                SessionLoadingActivity.intent(app, MACHINE, "Fixture", false)).setup();
        Loading activity = loading.get();
        try {
            shadowOf(Looper.getMainLooper()).idle();
            assertNotNull(activity.attempt);
            assertEquals(1, activity.polls);
            assertEquals(1, shadowOf(app).getBoundServiceConnections().size());

            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(6));
            assertFalse(activity.isFinishing());
            assertTrue(activity.attempt.isActive());
            assertEquals(SessionOrchestrator.Stage.QUEUED, activity.attempt.getStage());
            assertEquals(73, activity.polls);
            assertEquals(1, dashboard.get().callbacks.size());
            assertTrue(dashboard.get().scopes.get(0).isCancelled());
            assertEquals(1, shadowOf(app).getBoundServiceConnections().size());
            assertTrue(shadowOf(app).getUnboundServiceConnections().isEmpty());
            assertNull(shadowOf(activity).getNextStartedActivity());

            activity.ready = true;
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5));
            Intent game = shadowOf(activity).getNextStartedActivity();
            assertNotNull(game);
            assertEquals(Game.class.getName(), game.getComponent().getClassName());
            assertEquals(activity.attempt.id, game.getStringExtra(Game.EXTRA_SESSION_ATTEMPT));
            assertEquals(SessionOrchestrator.Stage.WAITING_FOR_FRAME, activity.attempt.getStage());
            assertEquals(1, activity.connections);
        } finally {
            if (activity.attempt != null) activity.attempt.cancel();
            loading.pause().stop().destroy();
            dashboard.destroy();
        }
    }

    private ActivityController<Loading> loadingForBackground() {
        Application app = RuntimeEnvironment.getApplication();
        Settings.Global.putFloat(app.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 0);
        shadowOf(app.getSystemService(NotificationManager.class)).setNotificationsEnabled(true);
        shadowOf(app).setComponentNameAndServiceForBindService(
                new ComponentName(app, ComputerManagerService.class), new ComputerManagerService().onBind(null));
        ActivityController<Loading> controller = Robolectric.buildActivity(Loading.class,
                SessionLoadingActivity.intent(app, MACHINE, "Fixture", false));
        controller.get().beginIdle = true;
        return controller;
    }

    private Button backgroundButton(Loading activity) {
        Button button = activity.findViewById(activity.getResources().getIdentifier(
                "sessionBackground", "id", activity.getPackageName()));
        assertNotNull("The loading screen needs an explicit background action", button);
        return button;
    }

    private Intent nextStartedMonitor() {
        Intent next;
        while ((next = shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService()) != null) {
            // Robolectric also records the ComputerManagerService auto-create binding here.
            if (!ComputerManagerService.class.getName().equals(next.getComponent().getClassName())) return next;
        }
        return null;
    }

    @Test public void backgroundHandoffKeepsTheAcceptedQueueWithoutOpeningTheStream() throws Exception {
        ActivityController<Loading> loading = loadingForBackground().setup();
        Loading activity = loading.get();
        try {
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(1, activity.joins);
            Button button = backgroundButton(activity);
            assertTrue(button.isEnabled());
            button.performClick();
            assertTrue(activity.isFinishing());
            Intent monitor = nextStartedMonitor();
            assertNotNull(monitor);
            assertEquals("com.limelight.account.VmMonitorService", monitor.getComponent().getClassName());
            assertEquals(MACHINE, monitor.getStringExtra("machineId"));
            assertTrue(monitor.getBooleanExtra("boot", false));
            assertNull(nextStartedMonitor());
            loading.pause().stop();
            activity.ready = true;
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(6));
            CompletableFuture<?> cleanup = ReflectionHelpers.getStaticField(SessionOrchestrator.class, "previousCleanup");
            cleanup.get(3, TimeUnit.SECONDS);
            assertEquals(0, activity.cleanups);
            assertEquals(0, activity.connections);
            assertFalse(activity.attempt.isActive());
            assertNull(shadowOf(activity).getNextStartedActivity());
        } finally {
            activity.attempt.cancel();
            loading.destroy();
        }
    }

    @Test public void blockedNotificationsKeepStartupInTheForeground() {
        ActivityController<Loading> loading = loadingForBackground().setup();
        Loading activity = loading.get();
        try {
            shadowOf(Looper.getMainLooper()).idle();
            shadowOf(activity.getSystemService(NotificationManager.class)).setNotificationsEnabled(false);
            backgroundButton(activity).performClick();
            assertFalse(activity.isFinishing());
            assertTrue(activity.attempt.isActive());
            assertNull(nextStartedMonitor());
            assertNotNull(ShadowToast.getTextOfLatestToast());
        } finally {
            activity.attempt.cancel();
            loading.pause().stop().destroy();
        }
    }

    @Test public void anUnknownJoinCannotBeHandedOffEvenByAQueuedClick() {
        ActivityController<Loading> loading = loadingForBackground();
        Loading activity = loading.get();
        activity.duringJoin = () -> {
            Button button = backgroundButton(activity);
            assertFalse(button.isEnabled());
            button.performClick();
            assertFalse(activity.isFinishing());
            assertNull(nextStartedMonitor());
        };
        try {
            loading.setup();
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(1, activity.joins);
            assertTrue(backgroundButton(activity).isEnabled());
        } finally {
            if (activity.attempt != null) activity.attempt.cancel();
            loading.pause().stop().destroy();
        }
    }

    @Test @Config(qualifiers = "pt-rBR")
    public void expiredPlanExplainsRenewalRatherThanAConnectionFailure() {
        ActivityController<Loading> loading = loadingForBackground().setup();
        try {
            shadowOf(Looper.getMainLooper()).idle();
            loading.get().onFailure(SessionOrchestrator.Failure.PLAN_EXPIRED);
            String message = ShadowToast.getTextOfLatestToast().toLowerCase(java.util.Locale.ROOT);
            assertTrue(message, message.contains("validade") && message.contains("renov"));
        } finally {
            loading.get().attempt.cancel();
            loading.pause().stop().destroy();
        }
    }

    @Test public void accountChangeBeforePreparedCallbackCannotOpenGame() {
        ActivityController<Loading> loading = loadingForBackground().setup();
        Loading activity = loading.get();
        try {
            shadowOf(Looper.getMainLooper()).idle();
            activity.ready = true;
            activity.duringPrepare = () -> AccountManager.logout(activity);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5));
            Intent destination = shadowOf(activity).getNextStartedActivity();
            assertNotNull(destination);
            assertEquals(LauncherActivity.class.getName(), destination.getComponent().getClassName());
            assertFalse(activity.attempt.isActive());
            assertNull(activity.getSharedPreferences("space_connect_launcher", 0).getString("last_host", null));
        } finally {
            activity.attempt.cancel();
            loading.pause().stop().destroy();
        }
    }

    public static class Loading extends SessionLoadingActivity {
        SessionOrchestrator attempt;
        int polls, connections, joins, cleanups;
        boolean ready, beginIdle;
        Runnable duringJoin, duringPrepare;

        @Override protected SessionOrchestrator createAttempt(ComputerManagerService.ComputerManagerBinder binder) {
            assertNotNull(binder);
            SessionOrchestrator.Backend backend = new SessionOrchestrator.Backend() {
                @Override public com.limelight.account.AccountManager.StreamRequests streamRequests(com.limelight.utils.RequestScope scope) {
                    throw new AssertionError("Startup-only backend");
                }
                @Override public SpaceConnectApiClient.StatusResponse status() {
                    polls++;
                    SpaceConnectApiClient.StatusResponse status = new SpaceConnectApiClient.StatusResponse();
                    status.state = beginIdle && polls == 1 ? "idle" : ready ? "ready" : "queued";
                    if ("queued".equals(status.state)) {
                        status.queue = new SpaceConnectApiClient.QueueStatus();
                        status.queue.position = 2;
                        status.queue.total = 4;
                        status.queue.machineId = MACHINE;
                    }
                    return status;
                }
                @Override public SpaceConnectApiClient.StatusResponse joinQueue() {
                    if (!beginIdle) throw new AssertionError("Existing queue must not be joined again");
                    joins++;
                    if (duringJoin != null) duringJoin.run();
                    SpaceConnectApiClient.StatusResponse result = new SpaceConnectApiClient.StatusResponse();
                    result.state = "queued";
                    result.queue = new SpaceConnectApiClient.QueueStatus();
                    result.queue.machineId = MACHINE;
                    result.queue.position = 2;
                    result.queue.total = 4;
                    return result;
                }
                @Override public SpaceConnectApiClient.ConnectionResponse connection() {
                    connections++;
                    SpaceConnectApiClient.ConnectionResponse connection = new SpaceConnectApiClient.ConnectionResponse();
                    connection.machineId = MACHINE;
                    return connection;
                }
                @Override public void cancel() {}
                @Override public void cleanupQueue(boolean uncertain) { cleanups++; }
            };
            SessionOrchestrator.Host host = new SessionOrchestrator.Host() {
                @Override public SessionOrchestrator.PreparedSession prepare(
                        SpaceConnectApiClient.ConnectionResponse connection, Runnable pairing) {
                    if (duringPrepare != null) duringPrepare.run();
                    ComputerDetails computer = new ComputerDetails();
                    computer.uuid = "fixture-host";
                    computer.name = "Fixture";
                    computer.activeAddress = new ComputerDetails.AddressTuple("127.0.0.1", 47989);
                    return new SessionOrchestrator.PreparedSession(computer,
                            new NvApp("Desktop", 918, false), "fixture-client", connection, false);
                }
                @Override public void cancel() {}
            };
            Handler handler = new Handler(Looper.getMainLooper());
            SessionOrchestrator.Callbacks callbacks = new SessionOrchestrator.Callbacks() {
                @Override public void post(Runnable action) { handler.post(action); }
                @Override public void clear() { handler.removeCallbacksAndMessages(null); }
            };
            attempt = new SessionOrchestrator(MACHINE, false, backend, host, callbacks, this,
                    new MainLooperExecutor(), 5000);
            return attempt;
        }
    }

    /** Drives the production five-second scheduling with Robolectric's virtual clock. */
    private static final class MainLooperExecutor extends ScheduledThreadPoolExecutor {
        final Handler handler = new Handler(Looper.getMainLooper());
        MainLooperExecutor() { super(1); }
        @Override public void execute(Runnable action) { schedule(action, 0, TimeUnit.MILLISECONDS); }
        @Override public ScheduledFuture<?> schedule(Runnable action, long delay, TimeUnit unit) {
            if (isShutdown()) throw new RejectedExecutionException();
            long millis = unit.toMillis(delay);
            ScheduledAction task = new ScheduledAction(action, SystemClock.uptimeMillis() + millis);
            handler.postDelayed(task, millis);
            return task;
        }
        @Override public List<Runnable> shutdownNow() {
            handler.removeCallbacksAndMessages(null);
            return super.shutdownNow();
        }
    }

    private static final class ScheduledAction extends FutureTask<Void> implements ScheduledFuture<Void> {
        final long deadline;
        ScheduledAction(Runnable action, long deadline) { super(action, null); this.deadline = deadline; }
        @Override public long getDelay(TimeUnit unit) {
            return unit.convert(deadline - SystemClock.uptimeMillis(), TimeUnit.MILLISECONDS);
        }
        @Override public int compareTo(Delayed other) {
            return Long.compare(getDelay(TimeUnit.MILLISECONDS), other.getDelay(TimeUnit.MILLISECONDS));
        }
    }
}
