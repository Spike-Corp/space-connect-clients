package com.limelight.ui;

import android.annotation.SuppressLint;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RotateDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ScrollView;

import com.limelight.R;
import com.limelight.utils.UiHelper;

import java.util.Collections;

/** Touch routing stays in the existing content FrameLayout, including pointer splitting. */
public final class StreamSideMenu extends FrameLayout {
    public interface Actions {
        void keyboard();
        void options();
        void microphone();
    }

    public interface BoundsListener {
        void onSafeBoundsChanged(Rect bounds);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ImageButton handle;
    private final ScrollView panel;
    private final View reservedArea;
    private final Button keyboard;
    private final Button options;
    private final Button microphone;
    private final int handleSize;
    private final int panelWidth;
    private final int topOffset;
    private final int gap;
    private final int touchSlop;
    private final int minimumFling;
    private final Rect insets = new Rect();
    private final Rect safeBounds = new Rect();
    private final Rect notifiedBounds = new Rect();
    private final int[] windowLocation = new int[2];
    private boolean available;
    private boolean gamepadVisible;
    private boolean editing;
    private boolean open;
    private boolean handleShown;
    private boolean touchActive;
    private final Runnable hideHandle = () -> {
        if (hasAccessibilityFocus()) {
            restartTimeouts();
        } else if (!gamepadVisible && !open && !touchActive) {
            setHandleVisible(false, true);
        }
    };
    private final Runnable closePanel = () -> {
        if (hasAccessibilityFocus()) restartTimeouts();
        else if (!touchActive) setOpen(false, true);
    };
    private final ContentObserver motionObserver = new ContentObserver(handler) {
        @Override public void onChange(boolean selfChange) {
            if (UiHelper.isMotionReduced(getContext())) {
                cancelPanelAnimation();
                setProgress(available && !editing && open ? 1f : 0f);
                handle.animate().cancel();
                handle.setAlpha(1f);
                updateHandleVisibility(false);
            }
        }
    };

    private Actions actions;
    private BoundsListener boundsListener;
    private ValueAnimator panelAnimation;
    private VelocityTracker velocity;
    private boolean revealGesture;
    private boolean dismissGesture;
    private boolean dragging;
    private boolean moved;
    private boolean dragStartedOpen;
    private boolean disposed;
    private boolean observing;
    private float progress;
    private float downX;
    private float downY;
    private float downProgress;
    private float closingTouchSlop;
    private int dragPointer = -1;

    @SuppressLint("ClickableViewAccessibility") // Native ImageButton.performClick handles touch and accessibility.
    public StreamSideMenu(Context context) {
        super(context);
        inflate(context, R.layout.view_stream_side_menu, this);
        setMotionEventSplittingEnabled(true);
        setElevation(dp(12));
        setFocusable(false);
        handleSize = getResources().getDimensionPixelSize(R.dimen.stream_side_handle_size);
        panelWidth = getResources().getDimensionPixelSize(R.dimen.stream_side_panel_width);
        topOffset = getResources().getDimensionPixelSize(R.dimen.stream_side_top_offset);
        gap = getResources().getDimensionPixelSize(R.dimen.stream_side_gap);
        ViewConfiguration configuration = ViewConfiguration.get(context);
        touchSlop = configuration.getScaledTouchSlop();
        minimumFling = configuration.getScaledMinimumFlingVelocity();
        handle = findViewById(R.id.streamSideHandle);
        panel = findViewById(R.id.streamSidePanel);
        reservedArea = findViewById(R.id.streamSideReservedArea);
        keyboard = findViewById(R.id.streamSideKeyboard);
        options = findViewById(R.id.streamSideOptions);
        microphone = findViewById(R.id.streamSideMicrophone);
        for (Button button : new Button[] {keyboard, options, microphone}) {
            button.setTypeface(UiHelper.getBodyTypeface(context));
            button.setCompoundDrawableTintList(ColorStateList.valueOf(
                    getResources().getColor(R.color.moonlight_foreground, context.getTheme())));
        }
        panel.setBackground(surfaceBackground(238, false));
        handle.setBackground(new InsetDrawable(surfaceBackground(220, true), handleSize / 2, 0, 0, 0));
        handle.setPadding(handleSize / 2 + dp(2), dp(14), dp(2), dp(14));
        RotateDrawable arrow = new RotateDrawable();
        arrow.setDrawable(handle.getDrawable());
        arrow.setFromDegrees(0f);
        arrow.setToDegrees(180f);
        handle.setImageDrawable(arrow);
        GradientDrawable reservedBackground = new GradientDrawable();
        int accent = getResources().getColor(R.color.moonlight_purple, context.getTheme());
        reservedBackground.setColor(withAlpha(accent, 45));
        reservedBackground.setStroke(dp(1), accent);
        reservedArea.setBackground(reservedBackground);
        handle.setOnClickListener(v -> setOpen(!open, true));
        handle.setOnTouchListener((v, event) -> {
            if (dragHandle(event)) v.performClick();
            return true;
        });
        keyboard.setOnClickListener(v -> {
            setOpen(false, true);
            if (actions != null) actions.keyboard();
        });
        options.setOnClickListener(v -> {
            setOpen(false, true);
            if (actions != null) actions.options();
        });
        microphone.setOnClickListener(v -> {
            microphone.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
            restartTimeouts();
            if (actions != null) actions.microphone();
        });
        setMicrophoneState(false, false);
        setProgress(0f);
        setHandleVisible(false, false);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    @SuppressLint("RtlHardcoded") // The drawer stays on the physical right edge, including RTL.
    private Drawable surfaceBackground(int alpha, boolean verticalAccent) {
        int surface = UiHelper.resolveThemeColor(getContext(), R.attr.appSurfaceColor);
        int blue = getResources().getColor(R.color.moonlight_gold, getContext().getTheme());
        int purple = getResources().getColor(R.color.moonlight_purple, getContext().getTheme());
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {withAlpha(surface, alpha), withAlpha(surface, Math.min(255, alpha + 12))});
        background.setCornerRadius(dp(12));
        background.setStroke(dp(1), UiHelper.resolveThemeColor(getContext(), R.attr.appBorderColor));
        GradientDrawable accent = new GradientDrawable(verticalAccent
                ? GradientDrawable.Orientation.TOP_BOTTOM : GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {blue, purple});
        accent.setCornerRadius(dp(12));
        LayerDrawable layers = new LayerDrawable(new Drawable[] {background, accent});
        layers.setLayerGravity(1, verticalAccent ? Gravity.LEFT : Gravity.TOP);
        if (verticalAccent) layers.setLayerWidth(1, dp(2));
        else layers.setLayerHeight(1, dp(2));
        return layers;
    }

    public void setActions(Actions actions) {
        this.actions = actions;
    }

    public void setBoundsListener(BoundsListener listener) {
        boundsListener = listener;
        notifiedBounds.setEmpty();
        requestLayout();
    }

    public int getReservedWidth() {
        return handleSize;
    }

    public void setControllerState(boolean visible, boolean inEditor) {
        if (gamepadVisible == visible && editing == inEditor) return;
        gamepadVisible = visible;
        editing = inEditor;
        if (editing) setOpen(false, false);
        reservedArea.setVisibility(available && visible && editing ? VISIBLE : INVISIBLE);
        updateHandleVisibility(false);
        restartTimeouts();
    }

    public void setAvailable(boolean value) {
        if (disposed || available == value) return;
        available = value;
        if (!available) touchActive = revealGesture = false;
        cancelDrag();
        cancelPanelAnimation();
        setProgress(available && !editing && open ? 1f : 0f);
        reservedArea.setVisibility(available && gamepadVisible && editing ? VISIBLE : INVISIBLE);
        updateHandleVisibility(false);
        restartTimeouts();
    }

    public void setMicrophoneState(boolean active, boolean busy) {
        microphone.setEnabled(!busy);
        microphone.setCompoundDrawablesWithIntrinsicBounds(0,
                active ? R.drawable.ic_microphone : R.drawable.ic_microphone_off, 0, 0);
        microphone.setCompoundDrawableTintList(ColorStateList.valueOf(getResources().getColor(
                active ? R.color.moonlight_purple : R.color.moonlight_muted_foreground, getContext().getTheme())));
        microphone.setContentDescription(getContext().getString(busy ? R.string.stream_side_mic_busy
                : active ? R.string.stream_side_mic_on : R.string.stream_side_mic_off));
    }

    public boolean isOpen() {
        return open;
    }

    public boolean isPanelShowing() {
        return available && !editing && (open || progress > 0f);
    }

    public void setOpen(boolean value, boolean animate) {
        if (disposed) return;
        open = value;
        cancelPanelAnimation();
        handle.setContentDescription(getContext().getString(
                open ? R.string.stream_side_close : R.string.stream_side_open));
        if (available && !editing) setHandleVisible(true, false);
        float target = available && !editing && open ? 1f : 0f;
        if (!animate || UiHelper.isMotionReduced(getContext()) || progress == target) {
            setProgress(target);
        } else {
            panelAnimation = ValueAnimator.ofFloat(progress, target);
            panelAnimation.setDuration(250);
            panelAnimation.setInterpolator(new DecelerateInterpolator());
            panelAnimation.addUpdateListener(animation -> setProgress((Float) animation.getAnimatedValue()));
            panelAnimation.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) {
                    if (animation == panelAnimation) panelAnimation = null;
                }
            });
            panelAnimation.start();
        }
        restartTimeouts();
    }

    private void setProgress(float value) {
        progress = Math.max(0f, Math.min(1f, value));
        panel.setTranslationX((panelWidth + insets.right) * (1f - progress));
        panel.setVisibility(progress > 0f ? VISIBLE : INVISIBLE);
        panel.setImportantForAccessibility(progress > 0f
                ? IMPORTANT_FOR_ACCESSIBILITY_AUTO : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        // Rotate only the icon: rotating the touch target also reverses drag velocity.
        handle.setImageLevel(Math.round(10000f * progress));
    }

    private void cancelPanelAnimation() {
        if (panelAnimation != null) {
            panelAnimation.removeAllListeners();
            panelAnimation.cancel();
            panelAnimation = null;
        }
    }

    private void updateHandleVisibility(boolean animate) {
        setHandleVisible(available && !editing && (gamepadVisible || open || handleShown), animate);
    }

    private void setHandleVisible(boolean show, boolean animate) {
        handleShown = show;
        handle.animate().cancel();
        if (show) {
            boolean wasHidden = handle.getVisibility() != VISIBLE;
            handle.setVisibility(VISIBLE);
            if (animate && wasHidden && !UiHelper.isMotionReduced(getContext())) {
                handle.setAlpha(0f);
                handle.animate().alpha(1f).setDuration(200).start();
            } else {
                handle.setAlpha(1f);
            }
        } else {
            handle.setVisibility(INVISIBLE);
            handle.setAlpha(1f);
        }
        updateGestureExclusion();
    }

    private boolean hasAccessibilityFocus() {
        return handle.isAccessibilityFocused() || keyboard.isAccessibilityFocused()
                || options.isAccessibilityFocused() || microphone.isAccessibilityFocused();
    }

    private long timeout(int millis) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            AccessibilityManager manager = getContext().getSystemService(AccessibilityManager.class);
            if (manager != null) return manager.getRecommendedTimeoutMillis(millis,
                    AccessibilityManager.FLAG_CONTENT_CONTROLS | AccessibilityManager.FLAG_CONTENT_TEXT);
        }
        return millis;
    }

    private void restartTimeouts() {
        handler.removeCallbacks(hideHandle);
        handler.removeCallbacks(closePanel);
        if (disposed || !available || editing || touchActive || dragging) return;
        if (open) handler.postDelayed(closePanel, timeout(6000));
        else if (!gamepadVisible && handleShown) handler.postDelayed(hideHandle, timeout(3000));
    }

    /** Called before normal Activity dispatch, without taking ownership of any pointer. */
    public void observeTouch(MotionEvent event) {
        if (disposed || !available || editing) return;
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            revealGesture = !handleShown && !isPanelShowing();
            touchActive = true;
            handler.removeCallbacks(hideHandle);
            handler.removeCallbacks(closePanel);
            setHandleVisible(true, true);
        }
    }

    /** Keep even an UP over the newly revealed handle on its original target. */
    public void afterTouchDispatch(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_UP
                || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            revealGesture = false;
            touchActive = false;
            restartTimeouts();
        }
    }

    private static boolean contains(View view, MotionEvent event) {
        return view.getVisibility() == VISIBLE && event.getX() >= view.getX()
                && event.getX() < view.getX() + view.getWidth() && event.getY() >= view.getY()
                && event.getY() < view.getY() + view.getHeight();
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (dismissGesture) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) dismissGesture = false;
            return true;
        }
        if (disposed || !available || editing || revealGesture) return false;
        if (action == MotionEvent.ACTION_DOWN) {
            boolean inHandle = contains(handle, event);
            boolean inPanel = contains(panel, event);
            if (isPanelShowing() && !inHandle && !inPanel) {
                dismissGesture = true;
                setOpen(false, true);
                return true;
            }
            if (!inHandle && !inPanel) return false;
        }
        return super.dispatchTouchEvent(event);
    }

    private boolean dragHandle(MotionEvent event) {
        int action = event.getActionMasked();
        boolean tapped = false;
        if (action == MotionEvent.ACTION_DOWN) {
            cancelDrag();
            cancelPanelAnimation();
            dragPointer = event.getPointerId(0);
            downX = event.getRawX();
            downY = event.getRawY();
            downProgress = progress;
            dragStartedOpen = open;
            // Leave room to recognize a rightward drag before reaching the screen edge.
            closingTouchSlop = Math.min(touchSlop,
                    Math.max(1f, (handle.getWidth() - event.getX()) / 2f));
            dragging = moved = false;
            velocity = VelocityTracker.obtain();
            handle.setPressed(true);
        }
        if (dragPointer == -1) return false;
        if (action == MotionEvent.ACTION_POINTER_UP
                && event.getPointerId(event.getActionIndex()) == dragPointer) action = MotionEvent.ACTION_UP;
        if (velocity != null) velocity.addMovement(event);
        if (action == MotionEvent.ACTION_MOVE) {
            float delta = event.getRawX() - downX;
            float horizontalSlop = dragStartedOpen && delta > 0 ? closingTouchSlop : touchSlop;
            moved |= Math.abs(delta) > horizontalSlop || Math.abs(event.getRawY() - downY) > touchSlop;
            dragging |= Math.abs(delta) > horizontalSlop;
            if (dragging) {
                setProgress(downProgress - delta / Math.max(1, panelWidth + insets.right));
                handle.setPressed(false);
            }
        } else if (action == MotionEvent.ACTION_UP) {
            if (dragging) {
                velocity.computeCurrentVelocity(1000);
                float speed = velocity.getXVelocity(dragPointer);
                boolean shouldOpen = progress >= .5f;
                if (Math.abs(speed) >= minimumFling) shouldOpen = speed < 0;
                else if (dragStartedOpen && event.getRawX() - downX > closingTouchSlop) {
                    // The fixed edge handle cannot travel half the panel width to close.
                    shouldOpen = false;
                }
                setOpen(shouldOpen, true);
            } else if (!moved) {
                tapped = true;
            }
            cancelDrag();
            restartTimeouts();
        } else if (action == MotionEvent.ACTION_CANCEL) {
            setOpen(dragStartedOpen, true);
            cancelDrag();
            restartTimeouts();
        }
        return tapped;
    }

    private void cancelDrag() {
        if (velocity != null) velocity.recycle();
        velocity = null;
        dragging = moved = false;
        dragPointer = -1;
        handle.setPressed(false);
    }

    @Override public WindowInsets onApplyWindowInsets(WindowInsets value) {
        Rect previous = new Rect(insets);
        insets.set(value.getSystemWindowInsetLeft(), value.getSystemWindowInsetTop(),
                value.getSystemWindowInsetRight(), value.getSystemWindowInsetBottom());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && value.getDisplayCutout() != null) {
            insets.left = Math.max(insets.left, value.getDisplayCutout().getSafeInsetLeft());
            insets.top = Math.max(insets.top, value.getDisplayCutout().getSafeInsetTop());
            insets.right = Math.max(insets.right, value.getDisplayCutout().getSafeInsetRight());
            insets.bottom = Math.max(insets.bottom, value.getDisplayCutout().getSafeInsetBottom());
        }
        if (!previous.equals(insets) && dragPointer != -1) {
            cancelDrag();
            cancelPanelAnimation();
            setProgress(open ? 1f : 0f);
        }
        requestLayout();
        return value;
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int height = MeasureSpec.getSize(heightSpec);
        getLocationInWindow(windowLocation);
        int right = Math.max(0, insets.right - Math.max(0,
                getRootView().getWidth() - windowLocation[0] - width));
        int bottom = Math.max(0, insets.bottom - Math.max(0,
                getRootView().getHeight() - windowLocation[1] - height));
        safeBounds.set(Math.max(0, insets.left - windowLocation[0]),
                Math.max(0, insets.top - windowLocation[1]), width - right, height - bottom);
        int handleTop = Math.max(safeBounds.top,
                Math.min(safeBounds.top + topOffset, safeBounds.bottom - handleSize));
        int panelTop = handleTop + handleSize + gap;
        handle.measure(MeasureSpec.makeMeasureSpec(handleSize, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(handleSize, MeasureSpec.EXACTLY));
        panel.measure(MeasureSpec.makeMeasureSpec(panelWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.max(0, safeBounds.bottom - panelTop), MeasureSpec.AT_MOST));
        reservedArea.measure(MeasureSpec.makeMeasureSpec(handleSize, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.max(0, safeBounds.height()), MeasureSpec.EXACTLY));
        setMeasuredDimension(width, height);
    }

    @SuppressLint("DrawAllocation") // Only changed safe bounds notify the listener, with an owned snapshot.
    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        if (changed && dragPointer != -1) {
            cancelDrag();
            cancelPanelAnimation();
            setProgress(open ? 1f : 0f);
        }
        int handleTop = Math.max(safeBounds.top,
                Math.min(safeBounds.top + topOffset, safeBounds.bottom - handleSize));
        handle.layout(safeBounds.right - handleSize, handleTop, safeBounds.right, handleTop + handleSize);
        int panelTop = handleTop + handleSize + gap;
        panel.layout(safeBounds.right - panelWidth, panelTop,
                safeBounds.right, panelTop + panel.getMeasuredHeight());
        reservedArea.layout(safeBounds.right - handleSize, safeBounds.top, safeBounds.right, safeBounds.bottom);
        setProgress(progress);
        updateGestureExclusion();
        if (boundsListener != null && !safeBounds.equals(notifiedBounds) && !safeBounds.isEmpty()) {
            notifiedBounds.set(safeBounds);
            boundsListener.onSafeBoundsChanged(new Rect(safeBounds));
        }
    }

    private void updateGestureExclusion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            handle.setSystemGestureExclusionRects(handleShown
                    ? Collections.singletonList(new Rect(0, 0, handleSize, handleSize))
                    : Collections.emptyList());
        }
    }

    @Override protected void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        float textSize = getResources().getDimension(R.dimen.stream_side_text_size);
        for (Button button : new Button[] {keyboard, options, microphone}) {
            button.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize);
        }
        requestLayout();
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
        cancelPanelAnimation();
        cancelDrag();
        handle.animate().cancel();
        actions = null;
        boundsListener = null;
        available = false;
        setProgress(0f);
        setHandleVisible(false, false);
        reservedArea.setVisibility(INVISIBLE);
    }
}
