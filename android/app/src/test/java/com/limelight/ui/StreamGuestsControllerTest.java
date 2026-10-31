package com.limelight.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import com.limelight.R;
import com.limelight.account.AccountManager;
import com.limelight.account.SpaceConnectApiClient;
import com.limelight.account.StreamClientIdentity;
import com.limelight.utils.RequestScope;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "pt-rBR")
@LooperMode(LooperMode.Mode.PAUSED)
public class StreamGuestsControllerTest {
    private ActivityController<Activity> lifecycle;
    private Activity activity;
    private QueuedWorker worker;
    private State state;
    private StreamGuestsController controller;
    private static final String GUEST = "507f1f77bcf86cd799439022";

    @Before public void setUp() {
        lifecycle = Robolectric.buildActivity(Activity.class);
        activity = lifecycle.get();
        activity.setTheme(R.style.StreamTheme);
        lifecycle.setup();
        worker = new QueuedWorker();
        state = new State();
        state.response = guests("Conta Amiga");
        controller = new StreamGuestsController(activity, new Requests(state, new RequestScope()), null, worker);
    }

    @After public void tearDown() {
        controller.dispose();
        lifecycle.pause().stop().destroy();
    }

    @Test public void accountNameAndConfirmationControlOnlyTheSelectedFriend() {
        openReady();
        AlertDialog panel = ShadowAlertDialog.getLatestAlertDialog();
        Button friend = findButton(panel.getWindow().getDecorView(), "Conta Amiga");
        assertNotNull(friend);
        assertTrue(friend.getText().toString().contains("2"));
        friend.performClick();
        AlertDialog confirmation = ShadowAlertDialog.getLatestAlertDialog();
        assertNotSame(panel, confirmation);
        assertTrue(shadowOf(confirmation).getMessage().toString().contains("conectar novamente"));
        confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(state.disconnected.isEmpty());
        friend.performClick();
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        next();
        assertEquals(Collections.singletonList(GUEST), state.disconnected);
        next();
        assertTrue(controller.isShowing());
    }

    @Test public void closingThePanelDoesNotCancelOrReopenAnAcceptedAction() {
        openReady();
        findButton(ShadowAlertDialog.getLatestAlertDialog().getWindow().getDecorView(), "Conta Amiga").performClick();
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        controller.closePanel();
        assertFalse(controller.isShowing());
        next();
        assertEquals(Collections.singletonList(GUEST), state.disconnected);
        assertFalse(controller.isShowing());
        assertTrue(worker.tasks.isEmpty());
    }

    @Test public void aLateReadCannotPopulateAClosedAndReopenedPanel() {
        controller.start();
        controller.showPanel();
        next();
        worker.runNext();
        controller.closePanel();
        state.response = guests("Nova Conta Amiga");
        controller.showPanel();
        shadowOf(Looper.getMainLooper()).idle();
        AlertDialog panel = ShadowAlertDialog.getLatestAlertDialog();
        assertNull(findButton(panel.getWindow().getDecorView(), "Conta Amiga"));
        next();
        assertNotNull(findButton(panel.getWindow().getDecorView(), "Nova Conta Amiga"));
    }

    @Test public void anAccountChangeBeforeUiDeliveryDoesNotRevealThePreviousAccountsFriends() {
        controller.start();
        controller.showPanel();
        next();
        worker.runNext();
        state.current = false;
        shadowOf(Looper.getMainLooper()).idle();
        AlertDialog panel = ShadowAlertDialog.getLatestAlertDialog();
        assertNull(findButton(panel.getWindow().getDecorView(), "Conta Amiga"));
        assertTrue(allText(panel.getWindow().getDecorView()).contains(activity.getString(R.string.stream_account_changed)));
    }

    @Test public void friendsAreIdentifiedWithoutReceivingOwnerControls() {
        controller.dispose();
        state.owner = false;
        worker = new QueuedWorker();
        controller = new StreamGuestsController(activity, new Requests(state, new RequestScope()), null, worker);
        controller.start();
        next();
        controller.showPanel();
        assertEquals(1, state.registrations);
        assertFalse(controller.isShowing());
        assertTrue(worker.tasks.isEmpty());
    }

    @Test public void destroyingTheStreamSuppressesAlreadyQueuedUiCallbacks() {
        controller.start();
        controller.showPanel();
        worker.runNext();
        controller.dispose();
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse(controller.isShowing());
        assertTrue(worker.isShutdown());
        assertTrue(worker.tasks.isEmpty());
    }

    @Test public void partialDisconnectIsExplicitAndRefreshDoesNotRepeatTheAction() {
        openReady();
        state.disconnectFailure = ReflectionHelpers.callConstructor(SpaceConnectApiClient.ApiException.class,
                ClassParameter.from(int.class, 502),
                ClassParameter.from(String.class, "STREAM_HOST_UNAVAILABLE"),
                ClassParameter.from(String.class, "Upstream failure"),
                ClassParameter.from(String.class, null),
                ClassParameter.from(Integer.class, 1),
                ClassParameter.from(Integer.class, 0),
                ClassParameter.from(Integer.class, 1),
                ClassParameter.from(Integer.class, 0));
        AlertDialog panel = ShadowAlertDialog.getLatestAlertDialog();
        findButton(panel.getWindow().getDecorView(), "Conta Amiga").performClick();
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        next();
        assertTrue(allText(panel.getWindow().getDecorView())
                .contains(activity.getString(R.string.stream_guests_partial, 1, 0, 1, 0)));
        assertNull(findButton(panel.getWindow().getDecorView(), "Conta Amiga"));
        panel.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        next();
        assertEquals(Collections.singletonList(GUEST), state.disconnected);
        assertNotNull(findButton(panel.getWindow().getDecorView(), "Conta Amiga"));
    }

    @Test public void emptyVerifiedListStillExplainsUnidentifiedConnections() {
        state.response.guests = new SpaceConnectApiClient.StreamGuest[0];
        state.response.unidentifiedCount = 3;
        openReady();
        View panel = ShadowAlertDialog.getLatestAlertDialog().getWindow().getDecorView();
        assertTrue(allText(panel).contains(activity.getString(R.string.stream_guests_empty)));
        assertTrue(allText(panel).contains(activity.getResources().getQuantityString(R.plurals.stream_guests_unidentified, 3, 3)));
        assertNull(findButton(panel, "Conta Amiga"));
    }

    @Test public void retryAfterRegistrationFailureLoadsTheCurrentList() {
        state.registrationFailure = new java.io.IOException("Test host unavailable");
        controller.start();
        controller.showPanel();
        next();
        AlertDialog panel = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(allText(panel.getWindow().getDecorView()).contains(activity.getString(R.string.stream_guests_error)));
        state.registrationFailure = null;
        panel.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        next();
        next();
        assertEquals(2, state.registrations);
        assertNotNull(findButton(panel.getWindow().getDecorView(), "Conta Amiga"));
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void longAccountNamesRemainReachableOnShortScreensWithLargeFonts() throws Exception {
        RuntimeEnvironment.setQualifiers("pt-rBR-w480dp-h240dp-land-mdpi");
        RuntimeEnvironment.setFontScale(1.6f);
        state.response = guests("Minha conta amiga com nome longo");
        openReady();
        View decor = ShadowAlertDialog.getLatestAlertDialog().getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.AT_MOST));
        decor.layout(0, 0, decor.getMeasuredWidth(), decor.getMeasuredHeight());
        Button friend = findButton(decor, "Minha conta amiga");
        assertNotNull(friend);
        friend.requestRectangleOnScreen(new Rect(0, 0, friend.getWidth(), friend.getHeight()), true);
        Rect visible = new Rect();
        assertTrue(friend.getGlobalVisibleRect(visible));
        assertTrue(visible.height() >= 48);
        Bitmap bitmap = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
        decor.draw(new Canvas(bitmap));
        java.io.File directory = new java.io.File("build/reports/stream-guests");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(new java.io.File(directory, "large-font-pt-br.png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally {
            bitmap.recycle();
        }
        friend.performClick();
        assertTrue(shadowOf(ShadowAlertDialog.getLatestAlertDialog()).getMessage()
                .toString().contains("conectar novamente"));
    }

    private void openReady() {
        controller.start();
        controller.showPanel();
        next();
        next();
        assertTrue(controller.isShowing());
    }

    private void next() {
        worker.runNext();
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static Button findButton(View view, String name) {
        if (view instanceof Button && ((Button) view).getText().toString().contains(name)) return (Button) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Button button = findButton(group.getChildAt(i), name);
                if (button != null) return button;
            }
        }
        return null;
    }

    private static String allText(View view) {
        StringBuilder text = new StringBuilder();
        if (view instanceof android.widget.TextView) text.append(((android.widget.TextView) view).getText());
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) text.append(allText(group.getChildAt(i)));
        }
        return text.toString();
    }

    private static SpaceConnectApiClient.StreamGuestsResponse guests(String name) {
        SpaceConnectApiClient.StreamGuest guest = new SpaceConnectApiClient.StreamGuest();
        guest.userId = GUEST;
        guest.displayName = name;
        guest.username = "amigo";
        SpaceConnectApiClient.StreamGuestDevice first = new SpaceConnectApiClient.StreamGuestDevice();
        first.bindingId = "507f1f77bcf86cd799439066";
        first.platform = "android";
        SpaceConnectApiClient.StreamGuestDevice second = new SpaceConnectApiClient.StreamGuestDevice();
        second.bindingId = "507f1f77bcf86cd799439077";
        second.platform = "windows";
        guest.devices = new SpaceConnectApiClient.StreamGuestDevice[] {first, second};
        SpaceConnectApiClient.StreamGuestsResponse response = new SpaceConnectApiClient.StreamGuestsResponse();
        response.guests = new SpaceConnectApiClient.StreamGuest[] {guest};
        response.unidentifiedCount = 1;
        return response;
    }

    private static final class State {
        boolean owner = true, current = true;
        int registrations;
        Exception registrationFailure, disconnectFailure;
        SpaceConnectApiClient.StreamGuestsResponse response;
        final List<String> disconnected = new ArrayList<>();
    }

    private static final class Requests implements AccountManager.StreamRequests {
        private final State state;
        private final RequestScope scope;
        Requests(State state, RequestScope scope) { this.state = state; this.scope = scope; }
        @Override public AccountManager.StreamRequests withScope(RequestScope scope) { return new Requests(state, scope); }
        @Override public boolean isCurrentAccount() { return state.current; }
        @Override public boolean isOwnerSession() { return state.owner; }
        @Override public SpaceConnectApiClient.StreamIdentityResponse registerStream(StreamClientIdentity identity) throws Exception {
            scope.checkCancelled();
            state.registrations++;
            if (state.registrationFailure != null) throw state.registrationFailure;
            SpaceConnectApiClient.StreamIdentityResponse result = new SpaceConnectApiClient.StreamIdentityResponse();
            result.bindingId = "507f1f77bcf86cd799439055";
            result.canManageGuests = state.owner;
            return result;
        }
        @Override public SpaceConnectApiClient.StreamGuestsResponse streamGuests(String bindingId) throws Exception {
            scope.checkCancelled();
            assertEquals("507f1f77bcf86cd799439055", bindingId);
            return state.response;
        }
        @Override public SpaceConnectApiClient.StreamDisconnectResponse disconnectStreamGuest(String bindingId, String userId) throws Exception {
            scope.checkCancelled();
            assertEquals("507f1f77bcf86cd799439055", bindingId);
            state.disconnected.add(userId);
            if (state.disconnectFailure != null) throw state.disconnectFailure;
            SpaceConnectApiClient.StreamDisconnectResponse response = new SpaceConnectApiClient.StreamDisconnectResponse();
            response.requested = 2;
            response.alreadyDisconnected = 0;
            return response;
        }
        @Override public void close() { scope.close(); }
    }

    private static final class QueuedWorker extends AbstractExecutorService {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        boolean shutdown;
        void runNext() {
            assertFalse("A streaming request must be scheduled", tasks.isEmpty());
            tasks.remove().run();
        }
        @Override public void execute(Runnable command) { tasks.add(command); }
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() {
            shutdown = true;
            List<Runnable> pending = new ArrayList<>(tasks);
            tasks.clear();
            return pending;
        }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown && tasks.isEmpty(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
    }
}
