package com.limelight.account;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
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
            Color.rgb(0, 229, 255),
            Color.rgb(124, 58, 237),
            Color.rgb(255, 43, 214),
            Color.rgb(255, 234, 0),
            Color.rgb(0, 255, 133),
            Color.rgb(0, 229, 255),
    };

    private static final long PERIOD_MS = 3200;
    private static final long FRAME_MS = 33; // ~30fps — suave pra um gradiente deslizante

    private final Paint surfacePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final Handler animHandler = new Handler(Looper.getMainLooper());
    private float phase;
    private boolean animating;
    private long animStartMs;

    // Handler.postDelayed como motor da animação. NÃO usar ValueAnimator nem
    // Choreographer aqui: os dois são afetados pela escala global de animações
    // do aparelho (opções do desenvolvedor / "remover animações" da MIUI), e o
    // selo congelava — foi o que o Davi viu no Xiaomi dele em 0.3.25. O selo é
    // identidade de marca (no desktop o QML pulsa sempre), então animamos pelo
    // relógio real do main looper, imune a qualquer config de animação.
    private final Runnable animTick = new Runnable() {
        @Override
        public void run() {
            if (!animating) {
                return;
            }
            long elapsed = SystemClock.uptimeMillis() - animStartMs;
            phase = (float) ((elapsed % PERIOD_MS) / (double) PERIOD_MS);
            // invalidate() direto (o tick já roda no main looper) força o onDraw na
            // próxima traversal. NÃO usar postInvalidateOnAnimation: além do custo,
            // o que travava o selo no Xiaomi do Davi era o canvas acelerado por
            // hardware reaproveitar o MESMO Shader em cache — por isso agora o
            // onDraw recria o LinearGradient a cada frame (shader novo = redesenho
            // de verdade), imune a config de animação do aparelho.
            invalidate();
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
    protected void onDraw(Canvas canvas) {
        float inset = borderPaint.getStrokeWidth();
        bounds.set(inset, inset, getWidth() - inset, getHeight() - inset);
        float radius = getHeight() / 2f;
        canvas.drawRoundRect(bounds, radius, radius, surfacePaint);

        int w = getWidth();
        if (w > 0) {
            // Recria o gradiente a cada frame com o deslocamento do phase embutido
            // nas coordenadas. Shader NOVO toda vez → o renderer por hardware não
            // reaproveita cache (causa do selo estático no Android) e a animação
            // aparece de verdade. REPEAT faz o loop ficar sem emenda.
            float shift = w * 2f * phase;
            LinearGradient g = new LinearGradient(-shift, 0, w * 2f - shift, 0,
                    RGB_COLORS, null, Shader.TileMode.REPEAT);
            borderPaint.setShader(g);
            canvas.drawRoundRect(bounds, radius, radius, borderPaint);
            getPaint().setShader(g);
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
        animHandler.post(animTick);
    }

    private void stopAnimator() {
        animating = false;
        animHandler.removeCallbacks(animTick);
    }
}
