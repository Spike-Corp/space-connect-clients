package com.limelight.account;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

import java.util.Locale;

public final class RgbRoleBadgeView extends TextView {
    private static final int[] RGB_COLORS = {
            Color.rgb(255, 43, 214),
            Color.rgb(0, 229, 255),
            Color.rgb(124, 58, 237),
            Color.rgb(255, 234, 0),
            Color.rgb(0, 255, 133),
    };

    private static final long COLOR_DURATION_MS = 850;
    private static final long FRAME_MS = 33;

    private final Paint surfacePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final Handler animHandler = new Handler(Looper.getMainLooper());
    private boolean animating;
    private long animStartMs;

    // Keep the existing color-only identity cycle independent of system motion scaling.
    private final Runnable animTick = new Runnable() {
        @Override
        public void run() {
            if (!animating) {
                return;
            }
            long elapsed = SystemClock.uptimeMillis() - animStartMs;
            int index = (int) ((elapsed / COLOR_DURATION_MS) % RGB_COLORS.length);
            float fraction = (elapsed % COLOR_DURATION_MS) / (float) COLOR_DURATION_MS;
            int from = RGB_COLORS[index];
            int to = RGB_COLORS[(index + 1) % RGB_COLORS.length];
            setBadgeColor(Color.rgb(
                    Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * fraction),
                    Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * fraction),
                    Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * fraction)));
            animHandler.postDelayed(this, FRAME_MS);
        }
    };

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
        setHorizontallyScrolling(false);
        setTypeface(getTypeface(), Typeface.BOLD);
        setLetterSpacing(0.12f);
        surfacePaint.setColor(Color.rgb(19, 13, 27));
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(getResources().getDisplayMetrics().density * 1.5f);
        setBadgeColor(RGB_COLORS[0]);
        setWillNotDraw(false);
    }

    public void setRole(String role) {
        String clean = role == null ? "" : role.trim();
        // Todo cargo exibido por este selo é de membro da equipe. Prefixa "STAFF"
        // pra deixar claro pra quem não conhece os cargos (MASTER/ADMIN/MOD...) —
        // pedido do Davi: sempre mostrar STAFF junto do cargo no Android.
        String label = clean.isEmpty()
                ? ""
                : "STAFF · " + clean.replace('_', ' ').toUpperCase(Locale.ROOT);
        setText(label);
        setVisibility(label.isEmpty() ? View.GONE : View.VISIBLE);
        if (label.isEmpty()) {
            stopAnimator();
        } else if (isAttachedToWindow()) {
            startAnimator();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float inset = borderPaint.getStrokeWidth();
        bounds.set(inset, inset, getWidth() - inset, getHeight() - inset);
        float radius = getHeight() / 2f;
        canvas.drawRoundRect(bounds, radius, radius, surfacePaint);

        canvas.drawRoundRect(bounds, radius, radius, borderPaint);
        super.onDraw(canvas);
    }

    private void setBadgeColor(int color) {
        borderPaint.setColor(color);
        // TextView must invalidate its cached glyphs, not just its Paint's shader.
        setTextColor(color);
        invalidate();
    }

    @Override public void onVisibilityAggregated(boolean visible) {
        super.onVisibilityAggregated(visible);
        if (animHandler == null) return;
        if (visible && length() > 0) startAnimator();
        else stopAnimator();
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
        if (animating || !isAttachedToWindow() || !isShown()
                || getWindowVisibility() != View.VISIBLE) {
            return;
        }
        animating = true;
        animStartMs = SystemClock.uptimeMillis();
        animHandler.post(animTick);
    }

    private void stopAnimator() {
        animating = false;
        animHandler.removeCallbacks(animTick);
    }
}
