package com.limelight.session;

import android.app.Activity;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;
import com.limelight.R;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowChoreographer;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SessionOverlayViewTest {
    private ActivityController<Activity> activity;
    private SessionOverlayView overlay;

    @Before public void setup() {
        ShadowChoreographer.setPaused(true);
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16));
        activity = Robolectric.buildActivity(Activity.class).setup();
        Settings.Global.putFloat(activity.get().getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        Settings.Global.putFloat(activity.get().getContentResolver(), Settings.Global.TRANSITION_ANIMATION_SCALE, 1f);
        overlay = new SessionOverlayView(activity.get());
        activity.get().setContentView(overlay);
        activity.visible();
    }

    @After public void cleanup() {
        overlay.dispose();
        activity.pause().stop().destroy();
    }

    @Test public void crossfadesTwoLabelsWithoutReplacingVisibleText() {
        overlay.showMessage("Queue 2/5");
        idle(400);
        TextView first = overlay.findViewById(R.id.sessionNextMessage);
        TextView next = overlay.findViewById(R.id.sessionMessage);
        assertEquals(1f, first.getAlpha(), .01f);
        overlay.showMessage("Booting");
        assertEquals("Queue 2/5", first.getText().toString());
        assertEquals("Booting", next.getText().toString());
        assertEquals(0f, next.getAlpha(), .01f);
        idle(160);
        assertTrue(first.getAlpha() > 0f && first.getAlpha() < 1f);
        assertTrue(next.getAlpha() > 0f && next.getAlpha() < 1f);
        idle(300);
        assertEquals(1f, next.getAlpha(), .01f);
        assertTrue(overlay.isCoveringStream());
    }

    @Test public void staysAboveSurfaceUntilRenderedFrameThenFadesFor500ms() {
        overlay.showMessage("Starting stream");
        idle(1000);
        assertEquals(View.VISIBLE, overlay.getVisibility());
        assertEquals(1f, overlay.getAlpha(), .01f);
        AtomicInteger revealed = new AtomicInteger();
        overlay.onFirstFrameRendered(revealed::incrementAndGet);
        idle(400);
        assertEquals(View.VISIBLE, overlay.getVisibility());
        assertEquals(0, revealed.get());
        idle(160);
        assertEquals(View.GONE, overlay.getVisibility());
        assertEquals(1, revealed.get());
        overlay.onFirstFrameRendered(revealed::incrementAndGet);
        assertEquals(1, revealed.get());
    }

    @Test public void reducedMotionSkipsPulseCrossfadeAndRevealAnimation() {
        Settings.Global.putFloat(activity.get().getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 0f);
        idle(20);
        overlay.showMessage("Preparing");
        overlay.showMessage("Connecting");
        assertEquals(1f, overlay.findViewById(R.id.sessionLogo).getScaleX(), .01f);
        AtomicInteger revealed = new AtomicInteger();
        overlay.onFirstFrameRendered(revealed::incrementAndGet);
        assertEquals(View.GONE, overlay.getVisibility());
        assertEquals(1, revealed.get());
    }

    @Test public void cancellationDuringFadeDropsCompletionAndLateFrame() {
        AtomicInteger revealed = new AtomicInteger();
        overlay.onFirstFrameRendered(revealed::incrementAndGet);
        idle(100);
        overlay.dispose();
        idle(1000);
        overlay.onFirstFrameRendered(revealed::incrementAndGet);
        assertEquals(0, revealed.get());
    }

    @Test
    @Config(sdk = 24, qualifiers = "w640dp-h360dp-land")
    public void landscapePhoneCancelRemainsFocusableAndReachableOnMinSdk() {
        AtomicInteger cancelled = new AtomicInteger();
        overlay.setOnCancel(cancelled::incrementAndGet);
        overlay.measure(View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY));
        overlay.layout(0, 0, 640, 360);
        View cancel = overlay.findViewById(R.id.sessionCancel);
        assertTrue(cancel.isFocusable());
        assertTrue(cancel.getHeight() >= 48);
        assertTrue(cancel.getBottom() <= overlay.getHeight());
        assertTrue(cancel.performClick());
        assertEquals(1, cancelled.get());
    }

    private void idle(long ms) {
        while (ms > 0) {
            long frame = Math.min(16, ms);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(frame));
            ms -= frame;
        }
    }
}
