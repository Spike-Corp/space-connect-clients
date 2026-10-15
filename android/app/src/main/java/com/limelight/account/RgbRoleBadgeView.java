package com.limelight.account;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.TextView;

import java.util.Locale;

public final class RgbRoleBadgeView extends TextView {
    private static final int[] RGB_COLORS = {
            Color.rgb(0, 229, 255),
            Color.rgb(124, 58, 237),
            Color.rgb(255, 43, 214),
            Color.rgb(255, 234, 0),
            Color.rgb(0, 255, 133),
            Color.rgb(0, 229, 255),
    };

    private final Paint surfacePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final Matrix shaderMatrix = new Matrix();
    private LinearGradient gradient;
    private ValueAnimator animator;
    private float phase;

    public RgbRoleBadgeView(Context context) {
        this(context, null);
    }

    public RgbRoleBadgeView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public RgbRoleBadgeView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setGravity(Gravity.CENTER);
        setTextSize(9);
        setAllCaps(true);
        setSingleLine(true);
        setTypeface(getTypeface(), Typeface.BOLD);
        setLetterSpacing(0.12f);
        surfacePaint.setColor(Color.rgb(19, 13, 27));
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(getResources().getDisplayMetrics().density * 1.5f);
        setWillNotDraw(false);
    }

    public void setRole(String role) {
        String clean = role == null ? "" : role.trim();
        setText(clean.replace('_', ' ').toUpperCase(Locale.ROOT));
        setVisibility(clean.isEmpty() ? View.GONE : View.VISIBLE);
        if (clean.isEmpty()) {
            stopAnimator();
        } else if (isAttachedToWindow()) {
            startAnimator();
        }
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (width > 0) {
            gradient = new LinearGradient(0, 0, width * 2f, 0,
                    RGB_COLORS, null, Shader.TileMode.REPEAT);
            borderPaint.setShader(gradient);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float inset = borderPaint.getStrokeWidth();
        bounds.set(inset, inset, getWidth() - inset, getHeight() - inset);
        float radius = getHeight() / 2f;
        canvas.drawRoundRect(bounds, radius, radius, surfacePaint);

        if (gradient != null) {
            shaderMatrix.setTranslate(-getWidth() * 2f * phase, 0);
            gradient.setLocalMatrix(shaderMatrix);
            canvas.drawRoundRect(bounds, radius, radius, borderPaint);
            getPaint().setShader(gradient);
        }
        super.onDraw(canvas);
        getPaint().setShader(null);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (getVisibility() == View.VISIBLE && length() > 0) {
            startAnimator();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stopAnimator();
        super.onDetachedFromWindow();
    }

    private void startAnimator() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ValueAnimator.areAnimatorsEnabled()) {
            phase = 0f;
            invalidate();
            return;
        }
        if (animator != null && animator.isRunning()) {
            return;
        }
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(3200);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(value -> {
            phase = (float) value.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    private void stopAnimator() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }
}
