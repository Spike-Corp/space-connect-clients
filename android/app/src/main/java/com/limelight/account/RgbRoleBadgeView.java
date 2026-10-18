package com.limelight.account;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.View;
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

    private static final long PERIOD_MS = 3200;

    private final Paint surfacePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final Matrix shaderMatrix = new Matrix();
    private LinearGradient gradient;
    private float phase;
    private boolean animating;
    private long animStartMs;

    // Choreographer em vez de ValueAnimator: ValueAnimator respeita a escala global
    // de animações do Android (quem desativa em "opções do desenvolvedor" ou no modo
    // economia da MIUI fica com o selo congelado, sem o efeito RGB — foi o que o
    // Davi viu no celular dele). O selo é identidade de marca (igual ao desktop,
    // que pulsa sempre), então animamos pelo vsync real, imune a essa config.
    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!animating) {
                return;
            }
            long elapsedMs = (frameTimeNanos / 1_000_000L) - animStartMs;
            phase = ((elapsedMs % PERIOD_MS) + PERIOD_MS) % PERIOD_MS / (float) PERIOD_MS;
            invalidate();
            Choreographer.getInstance().postFrameCallback(this);
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
        setTypeface(getTypeface(), Typeface.BOLD);
        setLetterSpacing(0.12f);
        surfacePaint.setColor(Color.rgb(19, 13, 27));
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(getResources().getDisplayMetrics().density * 1.5f);
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
        if (animating) {
            return;
        }
        animating = true;
        animStartMs = SystemClock.uptimeMillis();
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    private void stopAnimator() {
        animating = false;
        Choreographer.getInstance().removeFrameCallback(frameCallback);
    }
}
