package com.limelight;

import com.limelight.account.LoginActivity;
import com.limelight.utils.UiHelper;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.TextView;

// First screen shown on launch. Purely presentational (no initialization work depends on it) -
// just gives the "Space Connect" brand a proper entrance before handing off to LoginActivity,
// which still does the real logged-in/skip check.
public class SplashActivity extends Activity {

    private static final int MIN_SPLASH_DURATION_MS = 1800;
    private static final int TIP_ROTATION_INTERVAL_MS = 1900;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private String[] tips;
    private int tipIndex = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        UiHelper.applyPreferredTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        tips = getResources().getStringArray(R.array.splash_loading_tips);

        View logo = findViewById(R.id.splashLogo);
        View wordmark = findViewById(R.id.splashWordmark);

        ((TextView) wordmark).setTypeface(UiHelper.getDisplayTypeface(this));

        logo.setAlpha(0f);
        logo.setScaleX(0.7f);
        logo.setScaleY(0.7f);
        wordmark.setAlpha(0f);

        logo.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(500)
                .setInterpolator(new DecelerateInterpolator())
                .start();

        wordmark.animate()
                .alpha(1f)
                .setStartDelay(200)
                .setDuration(500)
                .start();

        View spinner = findViewById(R.id.splashSpinner);
        ObjectAnimator spin = ObjectAnimator.ofFloat(spinner, "rotation", 0f, 360f);
        spin.setDuration(900);
        spin.setRepeatCount(ObjectAnimator.INFINITE);
        spin.setInterpolator(new LinearInterpolator());
        spin.start();

        rotateTip((TextView) findViewById(R.id.splashTip));

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                goToLogin();
            }
        }, MIN_SPLASH_DURATION_MS);
    }

    private void rotateTip(final TextView tipView) {
        if (tips == null || tips.length == 0) {
            return;
        }

        tipView.setText(tips[tipIndex % tips.length]);
        tipIndex++;

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                rotateTip(tipView);
            }
        }, TIP_ROTATION_INTERVAL_MS);
    }

    private void goToLogin() {
        if (isFinishing()) {
            return;
        }
        startActivity(new Intent(this, LoginActivity.class));
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }
}
