package com.limelight.account;

import android.os.Looper;
import android.content.Intent;
import android.widget.Button;
import com.google.gson.Gson;
import com.limelight.R;
import com.limelight.session.SessionLoadingActivity;
import com.limelight.utils.RequestScope;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LauncherPollingTest {
    public static class Launcher extends LauncherActivity {
        public final List<AccountManager.ResultCallback<SpaceConnectApiClient.StatusResponse>> callbacks = new ArrayList<>();
        public final List<RequestScope> scopes = new ArrayList<>();
        final List<CompletableFuture<Void>> futures = new ArrayList<>();
        @Override protected Future<?> requestStatus(String machine, RequestScope scope,
                AccountManager.ResultCallback<SpaceConnectApiClient.StatusResponse> callback) {
            callbacks.add(callback);
            scopes.add(scope);
            CompletableFuture<Void> future = new CompletableFuture<>();
            futures.add(future);
            return future;
        }
    }

    @Test public void pausedInFlightCallbackCannotRestartPollingEvenAfterResume() {
        ActivityController<Launcher> controller = Robolectric.buildActivity(Launcher.class).setup();
        Launcher launcher = controller.get();
        assertEquals(1, launcher.callbacks.size());
        controller.pause();
        assertTrue(launcher.scopes.get(0).isCancelled());
        assertTrue(launcher.futures.get(0).isCancelled());
        launcher.callbacks.get(0).onSuccess(queued());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6));
        assertEquals(1, launcher.callbacks.size());

        controller.resume();
        assertEquals(2, launcher.callbacks.size());
        launcher.callbacks.get(0).onError("late response");
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6));
        assertEquals(2, launcher.callbacks.size());
        launcher.callbacks.get(1).onSuccess(queued());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5));
        assertEquals(3, launcher.callbacks.size());
        controller.pause().stop().destroy();
        launcher.callbacks.get(2).onSuccess(queued());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6));
        assertEquals(3, launcher.callbacks.size());
    }

    private static SpaceConnectApiClient.StatusResponse queued() {
        SpaceConnectApiClient.StatusResponse response = new SpaceConnectApiClient.StatusResponse();
        response.state = "queued";
        response.queue = new SpaceConnectApiClient.QueueStatus();
        response.queue.position = 2;
        response.queue.total = 4;
        return response;
    }

    @Test public void queuedAndBootingDashboardOpenTheLoadingFlow() {
        for (String state : new String[]{"queued", "starting"}) {
            ActivityController<Launcher> controller = Robolectric.buildActivity(Launcher.class).setup();
            Launcher launcher = controller.get();
            ReflectionHelpers.setField(launcher, "selectedMachineId", "vm-fixture");
            SpaceConnectApiClient.StatusResponse status = "queued".equals(state) ? queued()
                    : new Gson().fromJson("{\"state\":\"starting\",\"session\":{\"machine\":{\"id\":\"vm-fixture\"}}}",
                            SpaceConnectApiClient.StatusResponse.class);
            launcher.callbacks.get(0).onSuccess(status);
            Button open = launcher.findViewById(R.id.launcherPrimaryButton);
            assertTrue(state, open.isEnabled());
            assertEquals(launcher.getString(R.string.launcher_join_queue), open.getText().toString());
            open.performClick();
            Intent next = shadowOf(launcher).getNextStartedActivity();
            assertNotNull(state, next);
            assertEquals(SessionLoadingActivity.class.getName(), next.getComponent().getClassName());
            controller.pause().stop().destroy();
        }
    }
}
