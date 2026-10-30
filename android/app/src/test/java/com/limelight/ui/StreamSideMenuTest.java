package com.limelight.ui;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ScrollView;

import com.limelight.R;
import com.limelight.binding.input.virtual_controller.VirtualController;
import com.limelight.binding.input.virtual_controller.VirtualControllerElement;
import com.limelight.nvstream.input.ControllerPacket;
import com.limelight.session.SessionLoadingLifecycleTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowChoreographer;
import org.robolectric.util.ReflectionHelpers;

import java.io.File;
import java.io.FileOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "pt-rBR-w640dp-h360dp-land-mdpi",
        shadows = SessionLoadingLifecycleTest.NativeBridge.class)
public class StreamSideMenuTest {
    private ActivityController<Host> activity;
    private Host host;
    private StreamSideMenu menu;
    private long gestureTime;

    public static class Host extends Activity {
        FrameLayout root;
        StreamSideMenu menu;
        final List<String> received = new ArrayList<>();

        @Override public void onCreate(Bundle state) {
            setTheme(R.style.StreamTheme);
            super.onCreate(state);
            requestWindowFeature(Window.FEATURE_NO_TITLE);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            root = new FrameLayout(this);
            root.setMotionEventSplittingEnabled(true);
            root.setBackgroundColor(Color.rgb(32, 38, 48));
            View game = new View(this);
            game.setOnTouchListener((v, event) -> {
                String value = event.getActionMasked() + ":";
                for (int i = 0; i < event.getPointerCount(); i++) value += event.getPointerId(i) + ",";
                received.add(value);
                return true;
            });
            root.addView(game, new FrameLayout.LayoutParams(-1, -1));
            menu = new StreamSideMenu(this);
            root.addView(menu, new FrameLayout.LayoutParams(-1, -1));
            setContentView(root);
            menu.setAvailable(true);
        }

        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            menu.observeTouch(event);
            try {
                return super.dispatchTouchEvent(event);
            } finally {
                menu.afterTouchDispatch(event);
            }
        }
    }

    @Before public void setup() {
        ShadowChoreographer.setPaused(true);
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16));
        activity = Robolectric.buildActivity(Host.class).setup().visible();
        host = activity.get();
        menu = host.menu;
        Settings.Global.putFloat(host.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        Settings.Global.putFloat(host.getContentResolver(), Settings.Global.TRANSITION_ANIMATION_SCALE, 1f);
        layout(host.getResources().getDisplayMetrics().widthPixels,
                host.getResources().getDisplayMetrics().heightPixels);
        idle(32);
    }

    @After public void cleanup() {
        menu.dispose();
        activity.pause().stop().destroy();
    }

    private void layout(int width, int height) {
        View decor = host.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, width, height);
    }

    private void idle(long millis) {
        while (millis > 0) {
            long frame = Math.min(16, millis);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(frame));
            millis -= frame;
        }
    }

    private void event(int action, float x, float y) {
        if (action == MotionEvent.ACTION_DOWN) gestureTime = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(gestureTime, SystemClock.uptimeMillis(), action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        host.dispatchTouchEvent(event);
        event.recycle();
    }

    private void tap(float x, float y) {
        event(MotionEvent.ACTION_DOWN, x, y);
        idle(20);
        event(MotionEvent.ACTION_UP, x, y);
        idle(1);
    }

    private void tapView(int id) {
        View view = menu.findViewById(id);
        int[] location = new int[2];
        view.getLocationInWindow(location);
        tap(location[0] + view.getWidth() / 2f, location[1] + view.getHeight() / 2f);
    }

    @Test public void revealOverHiddenHandlePassesWholeGestureThenNextTapOpens() {
        View handle = menu.findViewById(R.id.streamSideHandle);
        assertEquals(View.INVISIBLE, handle.getVisibility());
        tap(616, 48);
        assertEquals(List.of("0:0,", "1:0,"), host.received);
        assertFalse(menu.isOpen());
        assertEquals(View.VISIBLE, handle.getVisibility());
        tap(616, 48);
        idle(300);
        assertTrue(menu.isOpen());
        assertEquals(2, host.received.size());
        assertEquals(10000, ((ImageButton) handle).getDrawable().getLevel());
    }

    @Test public void handleTimesOutButVirtualGamepadKeepsItVisible() {
        tap(100, 100);
        idle(2900);
        assertEquals(View.VISIBLE, menu.findViewById(R.id.streamSideHandle).getVisibility());
        idle(150);
        assertEquals(View.INVISIBLE, menu.findViewById(R.id.streamSideHandle).getVisibility());
        menu.setControllerState(true, false);
        idle(10000);
        assertEquals(View.VISIBLE, menu.findViewById(R.id.streamSideHandle).getVisibility());
        menu.setControllerState(false, false);
        idle(3100);
        assertEquals(View.INVISIBLE, menu.findViewById(R.id.streamSideHandle).getVisibility());
    }

    @Test public void reservedStripOutsideHandleDoesNotSwallowGameplay() {
        menu.setControllerState(true, false);
        tap(616, 220);
        assertEquals(List.of("0:0,", "1:0,"), host.received);
        assertFalse(menu.isOpen());
    }

    @Test public void outsideDismissalConsumesDownMoveAndUpButNotFollowingGesture() {
        menu.setControllerState(true, false);
        menu.setOpen(true, false);
        event(MotionEvent.ACTION_DOWN, 200, 150);
        event(MotionEvent.ACTION_MOVE, 220, 160);
        idle(300);
        event(MotionEvent.ACTION_UP, 220, 160);
        assertFalse(menu.isOpen());
        assertTrue(host.received.isEmpty());
        tap(200, 150);
        assertEquals(List.of("0:0,", "1:0,"), host.received);
    }

    @Test public void dragTracksFingerInBothDirectionsAndCancelRestoresState() {
        menu.setControllerState(true, false);
        event(MotionEvent.ACTION_DOWN, 616, 48);
        idle(80);
        event(MotionEvent.ACTION_MOVE, 572, 48);
        float translation = menu.findViewById(R.id.streamSidePanel).getTranslationX();
        assertTrue(translation > 0 && translation < 88);
        event(MotionEvent.ACTION_UP, 520, 48);
        idle(300);
        assertTrue(menu.isOpen());
        assertEquals(0f, menu.findViewById(R.id.streamSidePanel).getTranslationX(), .01f);
        event(MotionEvent.ACTION_DOWN, 616, 48);
        idle(80);
        event(MotionEvent.ACTION_MOVE, 660, 48);
        event(MotionEvent.ACTION_CANCEL, 660, 48);
        idle(300);
        assertTrue(menu.isOpen());
        event(MotionEvent.ACTION_DOWN, 616, 48);
        idle(80);
        event(MotionEvent.ACTION_MOVE, 710, 48);
        event(MotionEvent.ACTION_UP, 710, 48);
        idle(300);
        assertFalse(menu.isOpen());
        assertTrue(host.received.isEmpty());
    }

    @Test public void actionsReuseCallbacksAndMicrophoneRestartsIdleTimeout() {
        AtomicInteger keyboards = new AtomicInteger();
        AtomicInteger options = new AtomicInteger();
        AtomicInteger microphones = new AtomicInteger();
        menu.setActions(new StreamSideMenu.Actions() {
            @Override public void keyboard() { keyboards.incrementAndGet(); }
            @Override public void options() { options.incrementAndGet(); }
            @Override public void microphone() { microphones.incrementAndGet(); }
        });
        menu.setControllerState(true, false);
        menu.setOpen(true, false);
        tapView(R.id.streamSideKeyboard);
        assertEquals(1, keyboards.get());
        assertFalse(menu.isOpen());
        menu.setOpen(true, false);
        tapView(R.id.streamSideOptions);
        assertEquals(1, options.get());
        assertFalse(menu.isOpen());
        menu.setOpen(true, false);
        idle(5000);
        tapView(R.id.streamSideMicrophone);
        assertEquals(1, microphones.get());
        assertTrue(menu.isOpen());
        idle(5000);
        assertTrue(menu.isOpen());
        idle(1100);
        assertFalse(menu.isOpen());
        assertTrue(host.received.isEmpty());
    }

    @Test public void editorAndPipHideHitTargetsAndReducedMotionFinishesImmediately() {
        menu.setControllerState(true, false);
        Settings.Global.putFloat(host.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 0f);
        idle(1);
        menu.setOpen(true, true);
        assertEquals(0f, menu.findViewById(R.id.streamSidePanel).getTranslationX(), .01f);
        menu.setAvailable(false);
        assertEquals(View.INVISIBLE, menu.findViewById(R.id.streamSideHandle).getVisibility());
        assertTrue(menu.isOpen());
        idle(10000);
        menu.setAvailable(true);
        assertTrue(menu.isPanelShowing());
        layout(800, 400);
        assertTrue(menu.isOpen());
        assertEquals(752, menu.findViewById(R.id.streamSideHandle).getLeft());
        menu.setControllerState(true, true);
        assertFalse(menu.isOpen());
        assertEquals(View.INVISIBLE, menu.findViewById(R.id.streamSideHandle).getVisibility());
        assertEquals(View.VISIBLE, menu.findViewById(R.id.streamSideReservedArea).getVisibility());
    }

    @Test @Config(sdk = 29)
    public void gestureExclusionFollowsHandleAndSafeInsets() {
        menu.onApplyWindowInsets(new WindowInsets.Builder()
                .setSystemWindowInsets(Insets.of(20, 10, 30, 12)).build());
        layout(640, 360);
        menu.setControllerState(true, false);
        View handle = menu.findViewById(R.id.streamSideHandle);
        assertEquals(562, handle.getLeft());
        assertEquals(34, handle.getTop());
        assertEquals(List.of(new Rect(0, 0, 48, 48)), handle.getSystemGestureExclusionRects());
        menu.setControllerState(false, false);
        idle(3100);
        assertTrue(handle.getSystemGestureExclusionRects().isEmpty());
    }

    @Test @Config(sdk = 29)
    public void changedInsetsCancelHandleGestureAndIgnoreItsLateRelease() {
        menu.setControllerState(true, false);
        event(MotionEvent.ACTION_DOWN, 616, 48);
        menu.onApplyWindowInsets(new WindowInsets.Builder()
                .setSystemWindowInsets(Insets.of(0, 0, 24, 0)).build());
        layout(640, 360);
        event(MotionEvent.ACTION_UP, 616, 48);
        idle(300);
        assertFalse(menu.isOpen());
        tap(592, 48);
        idle(300);
        assertTrue(menu.isOpen());
        assertTrue(host.received.isEmpty());
    }

    @Test @Config(sdk = 24, qualifiers = "pt-rBR-w480dp-h240dp-land-mdpi")
    public void largeFontOnShortScreenKeepsMicrophoneReachableThroughScrolling() {
        RuntimeEnvironment.setFontScale(1.6f);
        menu.dispatchConfigurationChanged(host.getResources().getConfiguration());
        menu.setControllerState(true, false);
        menu.setOpen(true, false);
        layout(480, 240);
        idle(32);
        ScrollView panel = menu.findViewById(R.id.streamSidePanel);
        Button mic = menu.findViewById(R.id.streamSideMicrophone);
        assertTrue(mic.getTextSize() >= 19f);
        assertTrue(panel.getBottom() <= 240);
        assertTrue(panel.canScrollVertically(1));
        AtomicInteger clicks = new AtomicInteger();
        menu.setActions(new StreamSideMenu.Actions() {
            @Override public void keyboard() {}
            @Override public void options() {}
            @Override public void microphone() { clicks.incrementAndGet(); }
        });
        panel.fullScroll(View.FOCUS_DOWN);
        idle(400);
        tapView(R.id.streamSideMicrophone);
        assertEquals(1, clicks.get());
        assertTrue(menu.isOpen());
        assertTrue(host.received.isEmpty());
    }

    private void pointers(int action, int[] ids, float[] xs, float[] ys) {
        if ((action & MotionEvent.ACTION_MASK) == MotionEvent.ACTION_DOWN) gestureTime = SystemClock.uptimeMillis();
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[ids.length];
        MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[ids.length];
        for (int i = 0; i < ids.length; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = ids[i];
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coordinates[i] = new MotionEvent.PointerCoords();
            coordinates[i].x = xs[i];
            coordinates[i].y = ys[i];
            coordinates[i].pressure = 1;
            coordinates[i].size = 1;
        }
        MotionEvent event = MotionEvent.obtain(gestureTime, SystemClock.uptimeMillis(), action, ids.length,
                properties, coordinates, 0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        host.dispatchTouchEvent(event);
        event.recycle();
    }

    @Test public void secondFingerOpensMenuWithoutCancellingExistingGameplayPointer() {
        menu.setControllerState(true, false);
        pointers(MotionEvent.ACTION_DOWN, new int[] {0}, new float[] {100}, new float[] {150});
        pointers(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                new int[] {0, 1}, new float[] {100, 616}, new float[] {150, 48});
        idle(20);
        pointers(MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                new int[] {0, 1}, new float[] {100, 616}, new float[] {150, 48});
        idle(300);
        assertTrue(menu.isOpen());
        pointers(MotionEvent.ACTION_UP, new int[] {0}, new float[] {110}, new float[] {155});
        assertEquals("0:0,", host.received.get(0));
        assertEquals("1:0,", host.received.get(host.received.size() - 1));
        assertTrue(host.received.stream().noneMatch(value -> value.startsWith("3:") || value.contains("1,")));
    }

    @Test public void outsideDismissalDoesNotReleaseOrStickHeldVirtualGamepadButton() {
        VirtualController controls = new VirtualController(null, host.root, host);
        try {
            controls.refreshLayout();
            controls.show();
            menu.setControllerState(true, false);
            menu.bringToFront();
            layout(640, 360);
            idle(32);
            VirtualControllerElement button = controls.getElements().stream()
                    .filter(value -> ReflectionHelpers.<Integer>getField(value, "elementId")
                            == VirtualControllerElement.EID_A)
                    .findFirst().orElseThrow();
            int[] location = new int[2];
            button.getLocationInWindow(location);
            float x = location[0] + button.getWidth() / 2f;
            float y = location[1] + button.getHeight() / 2f;
            pointers(MotionEvent.ACTION_DOWN, new int[] {0}, new float[] {x}, new float[] {y});
            assertNotEquals(0, controls.getControllerInputContext().inputMap & ControllerPacket.A_FLAG);
            pointers(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    new int[] {0, 1}, new float[] {x, 616}, new float[] {y, 48});
            idle(20);
            pointers(MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    new int[] {0, 1}, new float[] {x, 616}, new float[] {y, 48});
            idle(300);
            assertTrue(menu.isOpen());
            pointers(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    new int[] {0, 1}, new float[] {x, 300}, new float[] {y, 150});
            pointers(MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    new int[] {0, 1}, new float[] {x, 300}, new float[] {y, 150});
            idle(300);
            assertFalse(menu.isOpen());
            assertNotEquals(0, controls.getControllerInputContext().inputMap & ControllerPacket.A_FLAG);
            pointers(MotionEvent.ACTION_UP, new int[] {0}, new float[] {x}, new float[] {y});
            assertEquals(0, controls.getControllerInputContext().inputMap & ControllerPacket.A_FLAG);
            assertTrue(host.received.isEmpty());
        } finally {
            controls.dispose();
        }
    }

    @Test public void disposalCancelsPendingAnimationAndTimers() {
        tap(100, 100);
        menu.setOpen(true, true);
        menu.dispose();
        idle(10000);
        assertEquals(View.INVISIBLE, menu.findViewById(R.id.streamSideHandle).getVisibility());
        assertEquals(View.INVISIBLE, menu.findViewById(R.id.streamSidePanel).getVisibility());
    }

    @Test public void accessibilityClicksOpenMenuAndInvokeExistingActionWithoutTouch() {
        AtomicInteger options = new AtomicInteger();
        menu.setActions(new StreamSideMenu.Actions() {
            @Override public void keyboard() {}
            @Override public void options() { options.incrementAndGet(); }
            @Override public void microphone() {}
        });
        menu.setControllerState(true, false);
        assertTrue(menu.findViewById(R.id.streamSideHandle)
                .performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
        idle(300);
        assertTrue(menu.isOpen());
        assertTrue(menu.findViewById(R.id.streamSideOptions)
                .performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
        assertEquals(1, options.get());
        assertFalse(menu.isOpen());
        assertTrue(host.received.isEmpty());
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void renderOpenMenuWithDefaultControls() throws Exception {
        VirtualController controls = new VirtualController(null, host.root, host);
        try {
            controls.refreshLayout();
            controls.show();
            menu.setControllerState(true, false);
            menu.setOpen(true, false);
            menu.bringToFront();
            layout(640, 360);
            savePreview("open-pt-br.png", 640, 360);
            menu.setOpen(false, false);
            savePreview("closed-pt-br.png", 640, 360);
            controls.hide();
            menu.setControllerState(false, false);
            RuntimeEnvironment.setQualifiers("pt-rBR-w480dp-h240dp-land-mdpi");
            RuntimeEnvironment.setFontScale(1.6f);
            menu.dispatchConfigurationChanged(host.getResources().getConfiguration());
            layout(480, 240);
            menu.setOpen(true, false);
            idle(32);
            ((ScrollView) menu.findViewById(R.id.streamSidePanel)).fullScroll(View.FOCUS_DOWN);
            idle(400);
            layout(480, 240);
            ScrollView panel = menu.findViewById(R.id.streamSidePanel);
            panel.scrollTo(0, panel.getChildAt(0).getHeight());
            savePreview("large-font-pt-br.png", 480, 240);
            Button mic = menu.findViewById(R.id.streamSideMicrophone);
            assertTrue(mic.getHeight() >= 48);
            assertEquals("Microfone", mic.getText().toString());
        } finally {
            controls.dispose();
        }
    }

    private void savePreview(String name, int width, int height) throws Exception {
        assertEquals(width, menu.getWidth());
        assertEquals(height, menu.getHeight());
        Bitmap bitmap = Bitmap.createBitmap(width * 2, height * 2, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.scale(2, 2);
        host.root.draw(canvas);
        File directory = new File("build/reports/stream-side-menu");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(directory, name))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally {
            bitmap.recycle();
        }
    }
}
