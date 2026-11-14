package com.limelight;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.media.AudioRecord;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.limelight.binding.audio.MicForwarder;
import com.limelight.binding.audio.TestMicHost;
import com.limelight.binding.input.touch.TouchContext;
import com.limelight.binding.input.virtual_controller.VirtualController;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.session.SessionLoadingLifecycleTest;
import com.limelight.session.SessionOrchestrator;
import com.limelight.ui.StreamSideMenu;
import com.limelight.ui.StreamGuestsController;
import com.limelight.account.AccountManager;
import com.limelight.account.SessionAccountSafetyTest;
import com.limelight.account.SpaceConnectApiClient;
import com.limelight.account.StreamClientIdentity;
import com.limelight.utils.RequestScope;
import com.limelight.binding.input.capture.InputCaptureProvider;
import com.limelight.binding.input.KeyboardTranslator;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.KeyboardPacket;
import com.limelight.nvstream.jni.MoonBridge;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowAudioRecord;
import org.robolectric.shadows.ShadowInputDevice;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "pt-rBR-w640dp-h360dp-land-mdpi",
        instrumentedPackages = "com.limelight.account",
        shadows = {SessionLoadingLifecycleTest.NativeBridge.class,
                SessionAccountSafetyTest.Store.class, SessionAccountSafetyTest.Api.class})
public class GameStreamSideMenuTest {
    private final List<Game> games = new ArrayList<>();
    private final List<TestMicHost> micHosts = new ArrayList<>();

    private Game createUi(Bundle saved) {
        return createUi(saved, false);
    }

    private Game createUi(Bundle saved, boolean withController) {
        return createUi(saved, withController, null);
    }

    private Game createUi(Bundle saved, boolean withController, Boolean guestOwner) {
        // Build only the real Activity's UI/input bridge, without launching a remote session.
        Game game = Robolectric.buildActivity(Game.class).get();
        games.add(game);
        game.setTheme(R.style.StreamTheme);
        game.getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        game.setContentView(R.layout.activity_game);
        ReflectionHelpers.setField(game, "connected", true);
        ReflectionHelpers.setField(game, "hostAddress", "127.0.0.1");
        ReflectionHelpers.setField(game, "prefConfig", PreferenceConfiguration.readPreferences(game));
        PreferenceConfiguration preferences = ReflectionHelpers.getField(game, "prefConfig");
        preferences.onscreenController = withController;
        if (withController) {
            VirtualController controls = new VirtualController(null,
                    (FrameLayout) game.findViewById(R.id.backgroundTouchView).getParent(), game);
            controls.refreshLayout();
            controls.show();
            ReflectionHelpers.setField(game, "virtualController", controls);
        }
        ReflectionHelpers.setField(game, "gameMenu", new GameMenu(game));
        if (guestOwner != null) {
            ReflectionHelpers.setField(game, "streamGuests",
                    new StreamGuestsController(game, new GuestRequests(guestOwner, new RequestScope()), null));
        }
        ReflectionHelpers.callInstanceMethod(game, "initializeStreamSideMenu", ClassParameter.from(Bundle.class, saved));
        View decor = game.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 640, 360);
        return game;
    }

    private StreamSideMenu menu(Game game) {
        return ReflectionHelpers.getField(game, "streamSideMenu");
    }

    private Game managedGame() throws Exception {
        return managedGame(false);
    }

    private Game managedGame(boolean friend) throws Exception {
        Game game = createUi(null);
        SessionAccountSafetyTest.Api.micFailure = null;
        SessionAccountSafetyTest.Api.micAccepted = true;
        SessionAccountSafetyTest.Api.micState = "ready";
        TestMicHost host = new TestMicHost();
        micHosts.add(host);
        SessionAccountSafetyTest.Api.micPort = host.port();
        SessionAccountSafetyTest.Api.duringMic = () -> {};
        AccountManager.logout(game);
        SessionAccountSafetyTest.saveAccountFixture(game, "first");
        String machineId = "507f1f77bcf86cd799439011";
        SessionOrchestrator.Backend backend = ReflectionHelpers.callConstructor(
                Class.forName("com.limelight.session.SessionBackend").asSubclass(SessionOrchestrator.Backend.class),
                ClassParameter.from(Context.class, game), ClassParameter.from(String.class, machineId),
                ClassParameter.from(boolean.class, friend), ClassParameter.from(RequestScope.class, new RequestScope()));
        SessionOrchestrator attempt = new SessionOrchestrator(machineId, friend, backend,
                new SessionOrchestrator.Host() {
                    @Override public SessionOrchestrator.PreparedSession prepare(
                            SpaceConnectApiClient.ConnectionResponse connection, Runnable onPairing) {
                        throw new AssertionError("This streaming UI fixture must not open a remote connection");
                    }
                    @Override public void cancel() {}
                }, new SessionOrchestrator.Callbacks() {
                    @Override public void post(Runnable action) { game.runOnUiThread(action); }
                    @Override public void clear() {}
                }, null);
        ReflectionHelpers.setField(game, "sessionAttempt", attempt);
        shadowOf(game.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO);
        ShadowAudioRecord.setSource(new ShadowAudioRecord.AudioRecordSource() {
            @Override public int readInByteArray(byte[] data, int offset, int size, boolean blocking) { return 0; }
        });
        return game;
    }

    @After public void cleanup() throws Exception {
        for (Game game : games) {
            ReflectionHelpers.callInstanceMethod(game, "stopConnection");
            menu(game).dispose();
            StreamGuestsController guests = ReflectionHelpers.getField(game, "streamGuests");
            if (guests != null) guests.dispose();
            VirtualController controls = ReflectionHelpers.getField(game, "virtualController");
            if (controls != null) controls.dispose();
            MicForwarder mic = ReflectionHelpers.getField(game, "micForwarder");
            if (mic != null) awaitState(mic, MicForwarder.State.STOPPED);
        }
        SessionAccountSafetyTest.awaitAccountWorker();
        for (TestMicHost host : micHosts) host.close();
        ShadowAudioRecord.clearSource();
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test public void actualGameDispatchKeepsRevealGestureOnBackground() {
        Game game = createUi(null);
        AtomicInteger events = new AtomicInteger();
        game.findViewById(R.id.backgroundTouchView).setOnTouchListener((v, event) -> {
            events.incrementAndGet();
            return true;
        });
        long now = SystemClock.uptimeMillis();
        for (int action : new int[] {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            MotionEvent event = MotionEvent.obtain(now, now + action * 20L, action, 616, 48, 0);
            game.dispatchTouchEvent(event);
            event.recycle();
        }

        assertEquals(2, events.get());
        assertFalse(menu(game).isOpen());
    }

    @Test public void ownerGuestPanelPausesCaptureRestoresItAndClosesWithTheStream() {
        Game game = createUi(null, false, true);
        StreamGuestsController guests = ReflectionHelpers.getField(game, "streamGuests");
        InputCaptureProvider capture = new InputCaptureProvider() {};
        ReflectionHelpers.setField(game, "inputCaptureProvider", capture);
        ReflectionHelpers.callInstanceMethod(game, "setInputGrabState", ClassParameter.from(boolean.class, true));
        assertTrue(capture.isCapturingEnabled());
        menu(game).setOpen(true, false);
        Button action = menu(game).findViewById(R.id.streamSideGuests);
        assertEquals(View.VISIBLE, action.getVisibility());
        action.performClick();
        assertTrue(guests.isShowing());
        assertFalse(menu(game).isOpen());
        assertFalse(capture.isCapturingEnabled());
        ReflectionHelpers.callInstanceMethod(game, "setInputGrabState", ClassParameter.from(boolean.class, true));
        assertFalse(capture.isCapturingEnabled());
        guests.closePanel();
        game.onWindowFocusChanged(true);
        assertTrue(capture.isCapturingEnabled());
        guests.showPanel();
        ReflectionHelpers.callInstanceMethod(game, "stopConnection");
        assertFalse(guests.isShowing());
    }

    @Test public void guestsAndManualPcsDoNotReceiveTheOwnerAction() {
        Game manual = createUi(null);
        Game friend = createUi(null, false, false);
        assertEquals(View.GONE, menu(manual).findViewById(R.id.streamSideGuests).getVisibility());
        assertEquals(View.GONE, menu(friend).findViewById(R.id.streamSideGuests).getVisibility());
    }

    @Test public void closingGuestsDoesNotGrabAPreviouslyFreeCursor() {
        Game game = createUi(null, false, true);
        StreamGuestsController guests = ReflectionHelpers.getField(game, "streamGuests");
        InputCaptureProvider capture = new InputCaptureProvider() {};
        ReflectionHelpers.setField(game, "inputCaptureProvider", capture);
        ReflectionHelpers.callInstanceMethod(game, "setInputGrabState", ClassParameter.from(boolean.class, false));
        guests.showPanel();
        guests.closePanel();
        game.onWindowFocusChanged(true);
        assertFalse(capture.isCapturingEnabled());
    }

    @Test
    @Config(shadows = KeyboardBridge.class)
    public void openingGuestsReleasesHeldKeysAndDoesNotReplayTheirLateRelease() {
        Game game = createKeyboardGame();
        StreamGuestsController guests = ReflectionHelpers.getField(game, "streamGuests");
        assertTrue(game.handleKeyDown(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_W, 0)));
        assertEquals(List.of("87:down"), KeyboardBridge.events);
        guests.showPanel();
        assertEquals(List.of("87:down", "87:up"), KeyboardBridge.events);
        assertFalse(game.handleKeyDown(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0)));
        guests.closePanel();
        game.onWindowFocusChanged(true);
        assertTrue(game.handleKeyDown(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_W, 1)));
        assertTrue(game.handleKeyUp(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_W, 0)));
        assertEquals(List.of("87:down", "87:up"), KeyboardBridge.events);
        assertTrue(game.handleKeyDown(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_W, 0)));
        assertTrue(game.handleKeyUp(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_W, 0)));
        assertEquals(List.of("87:down", "87:up", "87:down", "87:up"), KeyboardBridge.events);
    }

    @Test
    @Config(shadows = KeyboardBridge.class)
    public void aKeyReleasedInsideTheDialogCanBePressedAgainWithoutAStaleModifier() {
        Game game = createKeyboardGame();
        StreamGuestsController guests = ReflectionHelpers.getField(game, "streamGuests");
        assertTrue(game.handleKeyDown(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, 0)));
        assertTrue(game.handleKeyDown(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_W, 0)));
        guests.showPanel();
        assertTrue(KeyboardBridge.events.contains("162:up"));
        assertTrue(KeyboardBridge.events.contains("87:up"));
        guests.closePanel();
        game.onWindowFocusChanged(true);
        // The dialog owned the physical KEY_UP, so the Activity only sees this new press.
        assertTrue(game.handleKeyDown(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_W, 0)));
        assertTrue(game.handleKeyUp(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_W, 0)));
        assertEquals(6, KeyboardBridge.events.size());
        assertEquals(0, (int) ReflectionHelpers.<Integer>getField(game, "modifierFlags"));
    }

    private Game createKeyboardGame() {
        KeyboardBridge.events.clear();
        Game game = createUi(null, false, true);
        ReflectionHelpers.setField(game, "conn", new NvConnection(game, null, 0, "test", null, null, null));
        ReflectionHelpers.setField(game, "keyboardTranslator", new KeyboardTranslator(
                ReflectionHelpers.getField(game, "prefConfig")));
        ReflectionHelpers.setField(game, "inputCaptureProvider", new InputCaptureProvider() {});
        ReflectionHelpers.callInstanceMethod(game, "setInputGrabState", ClassParameter.from(boolean.class, true));
        return game;
    }

    @Implements(MoonBridge.class)
    public static class KeyboardBridge extends SessionLoadingLifecycleTest.NativeBridge {
        static final List<String> events = new ArrayList<>();
        @Implementation public static void sendKeyboardInput(short key, byte direction, byte modifiers, byte flags) {
            events.add((key & 0xff) + ":" + (direction == KeyboardPacket.KEY_DOWN ? "down" : "up"));
        }
    }

    private KeyEvent key(int action, int code, int repeat) {
        long now = SystemClock.uptimeMillis();
        KeyEvent event = new KeyEvent(now, now, action, code, repeat);
        InputDevice device = ShadowInputDevice.makeInputDeviceNamed("Physical test keyboard");
        ReflectionHelpers.setField(device, "mSources", InputDevice.SOURCE_KEYBOARD);
        ReflectionHelpers.setField(device, "mKeyboardType", InputDevice.KEYBOARD_TYPE_ALPHABETIC);
        shadowOf(event).setDevice(device);
        return event;
    }

    @Test public void pipClosesGuestsWithoutRecapturingUntilTheStreamRegainsFocus() {
        Game game = createUi(null, false, true);
        StreamGuestsController guests = ReflectionHelpers.getField(game, "streamGuests");
        InputCaptureProvider capture = new InputCaptureProvider() {};
        ReflectionHelpers.setField(game, "inputCaptureProvider", capture);
        ReflectionHelpers.callInstanceMethod(game, "setInputGrabState", ClassParameter.from(boolean.class, true));
        guests.showPanel();
        ReflectionHelpers.setField(game, "isHidingOverlays", true);
        ReflectionHelpers.callInstanceMethod(game, "updateStreamSideMenu");
        assertFalse(guests.isShowing());
        game.onWindowFocusChanged(true);
        assertFalse(capture.isCapturingEnabled());
        ReflectionHelpers.setField(game, "isHidingOverlays", false);
        game.onWindowFocusChanged(true);
        assertTrue(capture.isCapturingEnabled());
        guests.showPanel();
        ReflectionHelpers.callInstanceMethod(game, "stopConnection");
        game.onWindowFocusChanged(true);
        assertFalse(capture.isCapturingEnabled());
    }

    private static final class GuestRequests implements AccountManager.StreamRequests {
        final boolean owner;
        final RequestScope scope;
        GuestRequests(boolean owner, RequestScope scope) { this.owner = owner; this.scope = scope; }
        @Override public AccountManager.StreamRequests withScope(RequestScope scope) { return new GuestRequests(owner, scope); }
        @Override public boolean isCurrentAccount() { return true; }
        @Override public boolean isOwnerSession() { return owner; }
        @Override public SpaceConnectApiClient.MicEnsureResponse ensureMicBridge() throws Exception {
            throw new java.io.IOException("No remote host in this UI test");
        }
        @Override public SpaceConnectApiClient.StreamIdentityResponse registerStream(StreamClientIdentity identity) throws Exception {
            throw new java.io.IOException("No remote host in this UI test");
        }
        @Override public SpaceConnectApiClient.StreamGuestsResponse streamGuests(String bindingId) throws Exception {
            throw new java.io.IOException("No remote host in this UI test");
        }
        @Override public SpaceConnectApiClient.StreamDisconnectResponse disconnectStreamGuest(String bindingId, String userId) throws Exception {
            throw new java.io.IOException("No remote host in this UI test");
        }
        @Override public void close() { scope.close(); }
    }

    @Test public void savedOpenStateRestoresAndBackClosesMenuWithoutFinishingGame() {
        Game game = createUi(null);
        menu(game).setOpen(true, false);
        Bundle saved = new Bundle();
        ReflectionHelpers.callInstanceMethod(game, "onSaveInstanceState", ClassParameter.from(Bundle.class, saved));
        Game restored = createUi(saved);
        assertTrue(menu(restored).isOpen());
        assertTrue(restored.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)));
        assertTrue(restored.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)));
        assertFalse(menu(restored).isOpen());
        assertFalse(restored.isFinishing());
    }

    @Test public void hidingControlsKeepsMenuReachableAfterIdle() {
        Game game = createUi(null, true);
        VirtualController controls = ReflectionHelpers.getField(game, "virtualController");
        assertTrue(controls.isVirtualGamepadVisible());
        game.toggleOnScreenControls();
        shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.SECONDS);
        assertFalse(controls.isVirtualGamepadVisible());
        assertEquals(View.VISIBLE, menu(game).findViewById(R.id.streamSideHandle).getVisibility());
        tapHandle(game);
        assertTrue(menu(game).isOpen());
        assertFalse(controls.isVirtualGamepadVisible());
        menu(game).setOpen(false, false);
        ReflectionHelpers.setField(game, "isHidingOverlays", true);
        ReflectionHelpers.callInstanceMethod(game, "updateStreamSideMenu");
        assertEquals(View.INVISIBLE, menu(game).findViewById(R.id.streamSideHandle).getVisibility());
        ReflectionHelpers.setField(game, "isHidingOverlays", false);
        ReflectionHelpers.callInstanceMethod(game, "updateStreamSideMenu");
        shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.SECONDS);
        assertEquals(View.VISIBLE, menu(game).findViewById(R.id.streamSideHandle).getVisibility());
    }

    @Test public void hidingAnEditingControllerRestoresMenuWithoutDiscardingLayoutMode() {
        Game game = createUi(null, true);
        VirtualController controls = ReflectionHelpers.getField(game, "virtualController");
        Button configure = ReflectionHelpers.getField(controls, "buttonConfigure");
        configure.performClick();
        assertEquals(VirtualController.ControllerMode.MoveButtons, controls.getControllerMode());
        assertEquals(View.INVISIBLE, menu(game).findViewById(R.id.streamSideHandle).getVisibility());
        game.toggleOnScreenControls();
        shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.SECONDS);
        assertEquals(View.VISIBLE, menu(game).findViewById(R.id.streamSideHandle).getVisibility());
        tapHandle(game);
        assertTrue(menu(game).isOpen());
        game.toggleOnScreenControls();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(VirtualController.ControllerMode.MoveButtons, controls.getControllerMode());
        assertEquals(View.VISIBLE, configure.getVisibility());
        assertEquals(View.INVISIBLE, menu(game).findViewById(R.id.streamSideHandle).getVisibility());
    }

    @Test public void hiddenLayoutEditorDoesNotSwallowRemoteTouch() {
        Game game = createUi(null, true);
        VirtualController controls = ReflectionHelpers.getField(game, "virtualController");
        Button configure = ReflectionHelpers.getField(controls, "buttonConfigure");
        configure.performClick();
        game.toggleOnScreenControls();
        List<String> forwarded = new ArrayList<>();
        TouchContext[] contexts = ReflectionHelpers.getField(game, "touchContextMap");
        for (int i = 0; i < contexts.length; i++) {
            final int index = i;
            contexts[i] = new TouchContext() {
                @Override public int getActionIndex() { return index; }
                @Override public void setPointerCount(int count) {}
                @Override public boolean touchDownEvent(int x, int y, long time, boolean newFinger) {
                    forwarded.add("down:" + index);
                    return true;
                }
                @Override public boolean touchMoveEvent(int x, int y, long time) { return true; }
                @Override public void touchUpEvent(int x, int y, long time) { forwarded.add("up:" + index); }
                @Override public void cancelTouch() { forwarded.add("cancel:" + index); }
                @Override public boolean isCancelled() { return false; }
            };
        }
        View surface = game.findViewById(R.id.surfaceView);
        ReflectionHelpers.setField(game, "streamView", surface);
        PreferenceConfiguration preferences = ReflectionHelpers.getField(game, "prefConfig");
        preferences.enableMultiTouchScreen = false;
        ReflectionHelpers.setField(game, "threeFingerDownTime", -10000L);
        long now = SystemClock.uptimeMillis();
        for (int action : new int[] {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            MotionEvent event = MotionEvent.obtain(now, now + action * 20L, action, 100, 120, 0);
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            assertTrue(game.onTouch(surface, event));
            event.recycle();
        }
        assertEquals(List.of("down:0", "up:0"), forwarded);
    }

    private void tapHandle(Game game) {
        View handle = menu(game).findViewById(R.id.streamSideHandle);
        Rect bounds = new Rect(0, 0, handle.getWidth(), handle.getHeight());
        ((ViewGroup) game.getWindow().getDecorView()).offsetDescendantRectToMyCoords(handle, bounds);
        long now = SystemClock.uptimeMillis();
        for (int action : new int[] {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            MotionEvent event = MotionEvent.obtain(now, now + action * 20L, action,
                    bounds.exactCenterX(), bounds.exactCenterY(), 0);
            assertTrue(game.dispatchTouchEvent(event));
            event.recycle();
        }
    }

    @Test public void deniedPermissionReenablesMicrophoneWithoutStartingCapture() {
        Game game = createUi(null);
        shadowOf(game.getApplication()).denyPermissions(Manifest.permission.RECORD_AUDIO);
        ReflectionHelpers.callInstanceMethod(game, "toggleMicForwarding");
        Button micButton = menu(game).findViewById(R.id.streamSideMicrophone);
        assertFalse(micButton.isEnabled());
        game.onRequestPermissionsResult(4200, new String[] {Manifest.permission.RECORD_AUDIO},
                new int[] {PackageManager.PERMISSION_DENIED});
        assertTrue(micButton.isEnabled());
        assertNull(ReflectionHelpers.getField(game, "micForwarder"));
        assertEquals(game.getString(R.string.game_mic_forward_denied), ShadowToast.getTextOfLatestToast());
    }

    @Test public void latePermissionGrantCannotRestartAnEndedSession() {
        Game game = createUi(null);
        shadowOf(game.getApplication()).denyPermissions(Manifest.permission.RECORD_AUDIO);
        ReflectionHelpers.callInstanceMethod(game, "toggleMicForwarding");
        ReflectionHelpers.callInstanceMethod(game, "stopConnection");
        game.onRequestPermissionsResult(4200, new String[] {Manifest.permission.RECORD_AUDIO},
                new int[] {PackageManager.PERMISSION_GRANTED});
        assertNull(ReflectionHelpers.getField(game, "micForwarder"));
    }

    @Test public void menuAndStatisticsObserveSameCaptureFailure() throws Exception {
        AtomicBoolean fail = new AtomicBoolean();
        ShadowAudioRecord.setSource(new ShadowAudioRecord.AudioRecordSource() {
            @Override public int readInByteArray(byte[] target, int offset, int size, boolean blocking) {
                return fail.get() ? AudioRecord.ERROR_DEAD_OBJECT : 0;
            }
        });
        Game game = createUi(null);
        shadowOf(game.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO);
        ReflectionHelpers.callInstanceMethod(game, "toggleMicForwarding");
        MicForwarder mic = ReflectionHelpers.getField(game, "micForwarder");
        awaitState(mic, MicForwarder.State.RUNNING);
        shadowOf(Looper.getMainLooper()).idle();
        Button button = menu(game).findViewById(R.id.streamSideMicrophone);
        TextView stats = game.findViewById(R.id.perfOverlayExtra);
        assertEquals(game.getString(R.string.stream_side_mic_on), button.getContentDescription());
        assertFalse(stats.getText().toString().contains(game.getString(R.string.perf_overlay_mic_inactive)));
        fail.set(true);
        awaitState(mic, MicForwarder.State.STOPPED);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(game.getString(R.string.stream_side_mic_off), button.getContentDescription());
        assertTrue(stats.getText().toString().contains(game.getString(R.string.perf_overlay_mic_inactive)));
        assertEquals(game.getString(R.string.stream_side_mic_failed), ShadowToast.getTextOfLatestToast());
    }

    @Test public void managedMicrophoneWaitsForTheBridgeBeforeCapturing() throws Exception {
        Game game = managedGame();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        SessionAccountSafetyTest.Api.duringMic = () -> {
            entered.countDown();
            try { assertTrue(release.await(4, TimeUnit.SECONDS)); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        };
        try {
            ReflectionHelpers.callInstanceMethod(game, "toggleMicForwarding");
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            ReflectionHelpers.callInstanceMethod(game, "toggleMicForwarding");
            assertNull("No audio may be captured before bridge preparation succeeds",
                    ReflectionHelpers.getField(game, "micForwarder"));
            assertFalse(menu(game).findViewById(R.id.streamSideMicrophone).isEnabled());
        } finally {
            release.countDown();
        }
        SessionAccountSafetyTest.awaitAccountWorker();
        shadowOf(Looper.getMainLooper()).idle();
        MicForwarder mic = ReflectionHelpers.getField(game, "micForwarder");
        assertNotNull(mic);
        awaitState(mic, MicForwarder.State.RUNNING);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(game.getString(R.string.stream_side_mic_on),
                menu(game).findViewById(R.id.streamSideMicrophone).getContentDescription());
    }

    @Test public void managedMicrophoneFailureLeavesCaptureOffAndReportsTheError() throws Exception {
        Game game = managedGame();
        SessionAccountSafetyTest.Api.failMicrophone();
        ReflectionHelpers.callInstanceMethod(game, "toggleMicForwarding");
        SessionAccountSafetyTest.awaitAccountWorker();
        shadowOf(Looper.getMainLooper()).idle();
        assertNull(ReflectionHelpers.getField(game, "micForwarder"));
        assertTrue(menu(game).findViewById(R.id.streamSideMicrophone).isEnabled());
        assertEquals("Agente indisponível.", ShadowToast.getTextOfLatestToast());
    }

    @Test public void friendMicrophoneCannotBypassHostAuthorizationWithLegacyCapture() throws Exception {
        Game game = managedGame(true);
        ReflectionHelpers.callInstanceMethod(game, "toggleMicForwarding");
        SessionAccountSafetyTest.awaitAccountWorker();
        shadowOf(Looper.getMainLooper()).idle();
        assertNull(ReflectionHelpers.getField(game, "micForwarder"));
        assertTrue(menu(game).findViewById(R.id.streamSideMicrophone).isEnabled());
        assertEquals(game.getString(R.string.game_mic_owner_only), ShadowToast.getTextOfLatestToast());
    }

    @Test public void managedMicrophoneRequiresPositiveBridgeAcknowledgement() throws Exception {
        for (Boolean accepted : new Boolean[] {false, null}) {
            Game game = managedGame();
            SessionAccountSafetyTest.Api.micAccepted = accepted;
            ReflectionHelpers.callInstanceMethod(game, "toggleMicForwarding");
            SessionAccountSafetyTest.awaitAccountWorker();
            shadowOf(Looper.getMainLooper()).idle();
            assertNull(ReflectionHelpers.getField(game, "micForwarder"));
            assertTrue(menu(game).findViewById(R.id.streamSideMicrophone).isEnabled());
            assertNotNull(ShadowToast.getTextOfLatestToast());
        }

    }

    @Test public void installingOrUnknownBridgeNeverStartsCapture() throws Exception {
        for (String state : new String[] {"installing", "unknown", "started_no_listener"}) {
            Game game = managedGame();
            SessionAccountSafetyTest.Api.micState = state;
            ReflectionHelpers.callInstanceMethod(game, "toggleMicForwarding");
            SessionAccountSafetyTest.awaitAccountWorker();
            shadowOf(Looper.getMainLooper()).idle();
            assertNull("The host is not ready: " + state, ReflectionHelpers.getField(game, "micForwarder"));
            assertTrue(menu(game).findViewById(R.id.streamSideMicrophone).isEnabled());
            assertNotNull(ShadowToast.getTextOfLatestToast());
        }

    }

    @Test public void installingMicrophoneWaitsThenUsesTheAuthenticatedGrant() throws Exception {
        Game game = managedGame();
        SessionAccountSafetyTest.Api.micState = "installing";
        SessionAccountSafetyTest.Api.micAccepted = false;
        ReflectionHelpers.callInstanceMethod(game, "toggleMicForwarding");
        SessionAccountSafetyTest.awaitAccountWorker();
        shadowOf(Looper.getMainLooper()).idle();
        assertNull(ReflectionHelpers.getField(game, "micForwarder"));
        assertFalse(menu(game).findViewById(R.id.streamSideMicrophone).isEnabled());
        SessionAccountSafetyTest.Api.micState = "ready";
        SessionAccountSafetyTest.Api.micAccepted = true;
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2));
        SessionAccountSafetyTest.awaitAccountWorker();
        shadowOf(Looper.getMainLooper()).idle();
        MicForwarder mic = ReflectionHelpers.getField(game, "micForwarder");
        assertNotNull(mic);
        awaitState(mic, MicForwarder.State.RUNNING);
    }

    @Test public void managedMicrophoneCannotUseAnAccountChangedAfterStreamingStarted() throws Exception {
        Game game = managedGame();
        AccountManager.logout(game);
        SessionAccountSafetyTest.saveAccountFixture(game, "second");
        ReflectionHelpers.callInstanceMethod(game, "toggleMicForwarding");
        SessionAccountSafetyTest.awaitAccountWorker();
        shadowOf(Looper.getMainLooper()).idle();
        assertNull(ReflectionHelpers.getField(game, "micForwarder"));
        assertEquals(game.getString(R.string.stream_account_changed), ShadowToast.getTextOfLatestToast());
    }

    @Test public void managedMicrophoneResponseCannotStartCaptureAfterClosingTheStream() throws Exception {
        Game game = managedGame();
        ReflectionHelpers.callInstanceMethod(game, "toggleMicForwarding");
        SessionAccountSafetyTest.awaitAccountWorker();
        ReflectionHelpers.callInstanceMethod(game, "stopConnection");
        shadowOf(Looper.getMainLooper()).idle();
        assertNull(ReflectionHelpers.getField(game, "micForwarder"));
    }

    private static void awaitState(MicForwarder mic, MicForwarder.State state) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (mic.getState() != state && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals(state, mic.getState());
    }
}
