package com.limelight.session;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.limelight.R;
import com.limelight.utils.UiHelper;

/** Shared by the loading activity and Game, above (not instead of) the stream surface. */
public final class SessionOverlayView extends FrameLayout {
    static final long TEXT_FADE_MS = 300;
    static final long REVEAL_MS = 500;
    private final TextView[] labels = new TextView[2];
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ContentObserver motionObserver = new ContentObserver(handler) {
        @Override public void onChange(boolean selfChange) { updateMotion(); }
    };
    private final View logo;
    private final View spinner;
    private final Button cancel;
    private AnimatorSet pulse;
    private ObjectAnimator spin;
    private AnimatorSet textFade;
    private ObjectAnimator reveal;
    private int currentLabel;
    private CharSequence currentMessage;
    private CharSequence pendingMessage;
    private boolean reducedMotion;
    private boolean observing;
    private boolean disposed;
    private boolean firstFrame;

    public SessionOverlayView(Context context) { this(context, null); }

    public SessionOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        inflate(context, R.layout.view_session_loading, this);
        setBackgroundResource(R.drawable.bg_session_loading);
        setClickable(true);
        setFocusable(false);
        setElevation(100 * getResources().getDisplayMetrics().density);
        logo = findViewById(R.id.sessionLogo);
        spinner = findViewById(R.id.sessionSpinner);
        cancel = findViewById(R.id.sessionCancel);
        labels[0] = findViewById(R.id.sessionMessage);
        labels[1] = findViewById(R.id.sessionNextMessage);
        labels[0].setTypeface(UiHelper.getBodyTypeface(context));
        labels[1].setTypeface(UiHelper.getBodyTypeface(context));
        ((TextView) findViewById(R.id.sessionWordmark)).setTypeface(UiHelper.getDisplayTypeface(context));
        cancel.setTypeface(UiHelper.getBodyTypeface(context));
        setOnApplyWindowInsetsListener((view, insets) -> {
            int safe = (int) (24 * getResources().getDisplayMetrics().density);
            view.setPadding(Math.max(safe, insets.getSystemWindowInsetLeft()),
                    Math.max(safe, insets.getSystemWindowInsetTop()),
                    Math.max(safe, insets.getSystemWindowInsetRight()),
                    Math.max(safe, insets.getSystemWindowInsetBottom()));
            return insets;
        });
        reducedMotion = isMotionReduced(context);
    }

    public void setOnCancel(Runnable action) {
        cancel.setOnClickListener(v -> action.run());
        cancel.requestFocus();
    }

    public void showMessage(CharSequence message) {
        if (disposed || firstFrame || (message != null && message.equals(currentMessage))) return;
        if (textFade != null) {
            pendingMessage = message;
            return;
        }
        currentMessage = message;
        TextView old = labels[currentLabel];
        TextView next = labels[1 - currentLabel];
        // Only change the invisible buffer. The visible label is never replaced mid-frame.
        next.setText(message);
        next.setVisibility(VISIBLE);
        next.setAlpha(0f);
        next.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        next.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        old.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_NONE);
        old.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        currentLabel = 1 - currentLabel;
        if (reducedMotion) {
            old.setAlpha(0f);
            next.setAlpha(1f);
            return;
        }
        AnimatorSet fade = new AnimatorSet();
        fade.playTogether(ObjectAnimator.ofFloat(old, View.ALPHA, old.getAlpha(), 0f),
                ObjectAnimator.ofFloat(next, View.ALPHA, 0f, 1f));
        fade.setDuration(TEXT_FADE_MS);
        fade.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (textFade != animation || disposed) return;
                textFade = null;
                CharSequence pending = pendingMessage;
                pendingMessage = null;
                if (pending != null) showMessage(pending);
            }
        });
        textFade = fade;
        fade.start();
    }

    public void onFirstFrameRendered(Runnable revealed) {
        if (disposed || firstFrame) return;
        firstFrame = true;
        stopLoops();
        if (reducedMotion) {
            setVisibility(GONE);
            revealed.run();
            return;
        }
        reveal = ObjectAnimator.ofFloat(this, View.ALPHA, 1f, 0f);
        reveal.setDuration(REVEAL_MS);
        reveal.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (disposed) return;
                setVisibility(GONE);
                revealed.run();
            }
        });
        reveal.start();
    }

    public boolean isCoveringStream() {
        return !disposed && getVisibility() == VISIBLE;
    }

    static boolean isMotionReduced(Context context) {
        return Settings.Global.getFloat(context.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
                || Settings.Global.getFloat(context.getContentResolver(), Settings.Global.TRANSITION_ANIMATION_SCALE, 1f) == 0f
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ValueAnimator.areAnimatorsEnabled());
    }

    private void updateMotion() {
        if (disposed) return;
        reducedMotion = isMotionReduced(getContext());
        stopLoops();
        if (reducedMotion) {
            if (textFade != null) {
                textFade.removeAllListeners();
                textFade.cancel();
                textFade = null;
            }
            labels[currentLabel].setAlpha(1f);
            labels[1 - currentLabel].setAlpha(0f);
            CharSequence pending = pendingMessage;
            pendingMessage = null;
            if (pending != null) showMessage(pending);
            if (reveal != null && reveal.isStarted()) reveal.end();
            return;
        }
        if (firstFrame) return;
        ObjectAnimator scaleX = loop(logo, View.SCALE_X, 1f, 1.05f);
        ObjectAnimator scaleY = loop(logo, View.SCALE_Y, 1f, 1.05f);
        ObjectAnimator alpha = loop(logo, View.ALPHA, .8f, 1f);
        pulse = new AnimatorSet();
        pulse.playTogether(scaleX, scaleY, alpha);
        pulse.setDuration(1600);
        pulse.setInterpolator(new AccelerateDecelerateInterpolator());
        pulse.start();
        spin = ObjectAnimator.ofFloat(spinner, View.ROTATION, 0f, 360f);
        spin.setDuration(1100);
        spin.setRepeatCount(ValueAnimator.INFINITE);
        spin.setInterpolator(new LinearInterpolator());
        spin.start();
    }

    private static ObjectAnimator loop(View view, android.util.Property<View, Float> property, float from, float to) {
        ObjectAnimator animator = ObjectAnimator.ofFloat(view, property, from, to);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.REVERSE);
        return animator;
    }

    private void stopLoops() {
        if (pulse != null) pulse.cancel();
        if (spin != null) spin.cancel();
        pulse = null;
        spin = null;
        logo.setScaleX(1f);
        logo.setScaleY(1f);
        logo.setAlpha(1f);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (disposed) return;
        getContext().getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, motionObserver);
        getContext().getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.TRANSITION_ANIMATION_SCALE), false, motionObserver);
        observing = true;
        requestApplyInsets();
        updateMotion();
    }

    @Override protected void onDetachedFromWindow() {
        dispose();
        super.onDetachedFromWindow();
    }

    public void dispose() {
        if (disposed) return;
        disposed = true;
        if (observing) getContext().getContentResolver().unregisterContentObserver(motionObserver);
        observing = false;
        handler.removeCallbacksAndMessages(null);
        stopLoops();
        if (textFade != null) {
            textFade.removeAllListeners();
            textFade.cancel();
            textFade = null;
        }
        if (reveal != null) {
            reveal.removeAllListeners();
            reveal.cancel();
            reveal = null;
        }
        pendingMessage = null;
        cancel.setOnClickListener(null);
    }
}
