package com.limelight.account;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.FrameLayout;

import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.time.Duration;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class RgbRoleBadgeViewTest {

    private ActivityController<Activity> controller;
    private Activity activity;
    private RgbRoleBadgeView badge;

    @Before
    public void setUp() {
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        badge = new RgbRoleBadgeView(activity);
        badge.setTextSize(18);
        FrameLayout root = new FrameLayout(activity);
        root.addView(badge, new FrameLayout.LayoutParams(200, 40));
        activity.setContentView(root);
        controller.visible().windowFocusChanged(true);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32));
    }

    @After public void tearDown() {
        controller.pause().stop().destroy();
    }

    @Test
    public void showsStaffPrefixWithRole() {
        badge.setRole("admin");
        assertEquals("STAFF · ADMIN", badge.getText().toString());
        assertEquals(View.VISIBLE, badge.getVisibility());
    }

    @Test
    public void hidesWhenNoRole() {
        badge.setRole(null);
        assertEquals(View.GONE, badge.getVisibility());
    }

    @Test
    public void renderedTextAndBorderChangeColorTogetherEvenWithAnimatorScaleZero() {
        Settings.Global.putFloat(activity.getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 0f);
        badge.setRole("master");
        assertTrue("Badge must be attached and shown: attached=" + badge.isAttachedToWindow()
                + ", shown=" + badge.isShown() + ", window=" + badge.getWindowVisibility(),
                badge.isAttachedToWindow() && badge.isShown() && badge.getWindowVisibility() == View.VISIBLE);
        shadowOf(Looper.getMainLooper()).idle();
        int before = badge.getCurrentTextColor();
        assertRenderedTextAndBorder(before);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(950));
        int after = badge.getCurrentTextColor();
        assertNotEquals("The visible badge must animate its text color", before, after);
        assertRenderedTextAndBorder(after);
    }

    @Test public void animationStopsWhenHiddenAndRestartsWhenVisible() {
        badge.setRole("staff");
        shadowOf(Looper.getMainLooper()).idle();
        int before = badge.getCurrentTextColor();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(950));
        int active = badge.getCurrentTextColor();
        assertNotEquals("The visible badge must animate its text color", before, active);
        View decor = activity.getWindow().getDecorView();
        decor.setVisibility(View.GONE);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        assertEquals(active, badge.getCurrentTextColor());
        decor.setVisibility(View.VISIBLE);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350));
        assertNotEquals(active, badge.getCurrentTextColor());
    }

    private void assertRenderedTextAndBorder(int color) {
        badge.measure(
                View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(40, View.MeasureSpec.EXACTLY));
        badge.layout(0, 0, 200, 40);
        Bitmap bitmap = Bitmap.createBitmap(200, 40, Bitmap.Config.ARGB_8888);
        try {
            badge.draw(new Canvas(bitmap));
            int glyphPixels = 0;
            int borderPixels = 0;
            for (int y = 0; y < 40; y++) {
                for (int x = 20; x < 180; x++) {
                    if (bitmap.getPixel(x, y) != color) continue;
                    if (y >= 10 && y < 30) glyphPixels++;
                    if (y < 4 || y >= 36) borderPixels++;
                }
            }
            assertTrue("Text must render in the animated color", glyphPixels > 3);
            assertTrue("Border must follow the same color as the text", borderPixels > 10);
        } finally {
            bitmap.recycle();
        }
    }
}
