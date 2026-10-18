package com.limelight.account;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.time.Duration;

/**
 * Regressão do bug reportado pelo Davi (0.3.25): o selo STAFF ficava congelado
 * em aparelhos com a escala global de animações em 0 (opções do desenvolvedor /
 * "remover animações" da MIUI). O motor da animação é Handler.postDelayed —
 * precisa provar que a fase avança MESMO com a escala zerada.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class RgbRoleBadgeViewTest {

    private Activity activity;
    private RgbRoleBadgeView badge;

    @Before
    public void setUp() {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class);
        activity = controller.setup().get();
        badge = new RgbRoleBadgeView(activity);
        activity.setContentView(badge);
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
    public void animatesEvenWithAnimatorScaleZero() {
        // Device do Davi: animações do sistema DESLIGADAS
        Settings.Global.putFloat(activity.getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 0f);

        badge.setRole("master");

        // Deixa o looper ocioso pra o primeiro tick agendar, depois avança o tempo
        shadowOf(Looper.getMainLooper()).idle();
        float before = getPhaseViaPaint();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));
        float after = getPhaseViaPaint();

        assertTrue("a animação deveria avançar mesmo com a escala global em 0",
                after > before);
    }

    // Lê a fase atual executando um draw contra um canvas medidor
    private float getPhaseViaPaint() {
        // força layout mínimo pro gradiente existir
        badge.measure(
                View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(40, View.MeasureSpec.EXACTLY));
        badge.layout(0, 0, 200, 40);
        // phase é privado — espia via reflexão (teste de regressão, não de API)
        try {
            java.lang.reflect.Field f = RgbRoleBadgeView.class.getDeclaredField("phase");
            f.setAccessible(true);
            return f.getFloat(badge);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
