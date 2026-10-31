package com.limelight.binding.input.capture;

import android.app.Activity;
import android.os.Looper;
import android.view.InputDevice;
import android.view.View;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowInputDevice;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = AndroidNativePointerCaptureProviderTest.MouseDevices.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class AndroidNativePointerCaptureProviderTest {
    private ActivityController<Activity> lifecycle;
    private CaptureView view;
    private AndroidNativePointerCaptureProvider capture;

    @Before public void setUp() {
        lifecycle = Robolectric.buildActivity(Activity.class).setup();
        view = new CaptureView(lifecycle.get());
        lifecycle.get().setContentView(view);
        capture = new AndroidNativePointerCaptureProvider(lifecycle.get(), view);
        capture.enableCapture();
        assertTrue(view.requests > 0);
        view.requests = 0;
    }

    @After public void tearDown() {
        capture.disableCapture();
        capture.destroy();
        lifecycle.pause().stop().destroy();
    }

    @Test public void pendingFocusAndDeviceCallbacksCannotCaptureOverADialog() {
        capture.onWindowFocusChanged(true);
        capture.disableCapture();
        capture.onInputDeviceAdded(17);
        shadowOf(Looper.getMainLooper()).idleFor(600, TimeUnit.MILLISECONDS);
        assertEquals(0, view.requests);
    }

    @Test public void losingFocusCancelsThePendingRecapture() {
        capture.onWindowFocusChanged(true);
        view.focused = false;
        capture.onWindowFocusChanged(false);
        shadowOf(Looper.getMainLooper()).idleFor(600, TimeUnit.MILLISECONDS);
        assertEquals(0, view.requests);
    }

    @Test public void destroyingTheProviderCancelsThePendingRecapture() {
        capture.onWindowFocusChanged(true);
        capture.destroy();
        shadowOf(Looper.getMainLooper()).idleFor(600, TimeUnit.MILLISECONDS);
        assertEquals(0, view.requests);
    }

    private static final class CaptureView extends View {
        boolean focused = true;
        int requests;
        CaptureView(Activity activity) { super(activity); }
        @Override public boolean hasWindowFocus() { return focused; }
        @Override public void requestPointerCapture() { requests++; }
    }

    @Implements(InputDevice.class)
    public static class MouseDevices extends ShadowInputDevice {
        @Implementation protected static int[] getDeviceIds() { return new int[] {17}; }
        @Implementation protected static InputDevice getDevice(int id) {
            InputDevice mouse = makeInputDeviceNamed("Test mouse");
            ReflectionHelpers.setField(mouse, "mSources", InputDevice.SOURCE_MOUSE);
            return mouse;
        }
    }
}
