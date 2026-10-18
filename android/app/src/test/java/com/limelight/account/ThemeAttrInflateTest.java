package com.limelight.account;

import android.app.Activity;
import android.view.LayoutInflater;

import com.limelight.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/**
 * Regressão do crash de boot da 0.3.28/0.3.29: attr de tema customizado novo
 * (appLogoDrawable) foi definido só no styles.xml base, mas os buckets
 * values-v21/v23/v29 SUBSTITUEM o AppBaseTheme inteiro — sem o attr lá, o app
 * morria ao inflar a splash ("Failed to resolve attribute").
 *
 * Este teste infla TODOS os layouts que usam attrs de tema, nos DOIS temas
 * (escuro/claro) e em buckets de API diferentes, pra nunca mais escapar.
 */
@RunWith(RobolectricTestRunner.class)
public class ThemeAttrInflateTest {

    private Activity activity;

    @Before
    public void setUp() {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class);
        activity = controller.setup().get();
    }

    private void inflateAll(int themeRes) {
        activity.setTheme(themeRes);
        LayoutInflater inflater = LayoutInflater.from(activity);
        int[] layouts = {
                R.layout.activity_splash,
                R.layout.activity_login,
                R.layout.activity_launcher,
                R.layout.activity_pc_view,
                R.layout.activity_app_view,
        };
        for (int layout : layouts) {
            inflater.inflate(layout, null); // explode se algum attr de tema não resolver
        }
    }

    @Test
    @Config(sdk = 29)
    public void layoutsInflate_darkTheme_api29() {
        inflateAll(R.style.AppTheme);
    }

    @Test
    @Config(sdk = 29)
    public void layoutsInflate_lightTheme_api29() {
        inflateAll(R.style.AppTheme_Light);
    }

    @Test
    @Config(sdk = 33)
    public void layoutsInflate_darkTheme_api33() {
        inflateAll(R.style.AppTheme);
    }

    @Test
    @Config(sdk = 33)
    public void layoutsInflate_lightTheme_api33() {
        inflateAll(R.style.AppTheme_Light);
    }
}
