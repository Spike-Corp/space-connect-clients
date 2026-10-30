/**
 * Created by Karim Mreisi.
 */

package com.limelight.binding.input.virtual_controller;

import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.binding.input.ControllerHandler;

import java.util.ArrayList;
import java.util.List;

public class VirtualController {
    public static class ControllerInputContext {
        public short inputMap = 0x0000;
        public byte leftTrigger = 0x00;
        public byte rightTrigger = 0x00;
        public short rightStickX = 0x0000;
        public short rightStickY = 0x0000;
        public short leftStickX = 0x0000;
        public short leftStickY = 0x0000;
    }

    public enum ControllerMode {
        Active,
        MoveButtons,
        ResizeButtons
    }

    private static final boolean _PRINT_DEBUG_INFORMATION = false;

    private final ControllerHandler controllerHandler;
    private final Context context;
    private final Handler handler;

    private final Runnable delayedRetransmitRunnable = new Runnable() {
        @Override
        public void run() {
            sendControllerInputContextInternal();
        }
    };

    private FrameLayout frame_layout = null;

    ControllerMode currentMode = ControllerMode.Active;
    ControllerInputContext inputContext = new ControllerInputContext();

    private Button buttonConfigure = null;

    private List<VirtualControllerElement> elements = new ArrayList<>();
    private final Rect usableBounds = new Rect();
    private boolean visible;
    private boolean adjustmentNotified;
    private Runnable stateListener;

    public VirtualController(final ControllerHandler controllerHandler, FrameLayout layout, final Context context) {
        this.controllerHandler = controllerHandler;
        this.frame_layout = layout;
        this.context = context;
        this.handler = new Handler(Looper.getMainLooper());
        DisplayMetrics screen = context.getResources().getDisplayMetrics();
        usableBounds.set(0, 0, Math.max(1, screen.widthPixels
                - context.getResources().getDimensionPixelSize(R.dimen.stream_side_handle_size)), screen.heightPixels);

        buttonConfigure = new Button(context);
        buttonConfigure.setAlpha(0.25f);
        buttonConfigure.setFocusable(false);
        buttonConfigure.setBackgroundResource(R.drawable.ic_settings);
        buttonConfigure.setContentDescription(context.getString(R.string.stream_side_edit_move));
        buttonConfigure.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int message;

                if (currentMode == ControllerMode.Active){
                    releaseInputs();
                    currentMode = ControllerMode.MoveButtons;
                    message = R.string.stream_side_edit_move;
                } else if (currentMode == ControllerMode.MoveButtons) {
                    currentMode = ControllerMode.ResizeButtons;
                    message = R.string.stream_side_edit_resize;
                } else {
                    if (!isLayoutInsideBounds()) {
                        Toast.makeText(context, R.string.stream_side_layout_conflict, Toast.LENGTH_LONG).show();
                        return;
                    }
                    currentMode = ControllerMode.Active;
                    VirtualControllerConfigurationLoader.saveProfile(VirtualController.this, context);
                    message = R.string.stream_side_edit_saved;
                }

                Toast.makeText(context, message, Toast.LENGTH_SHORT).show();

                buttonConfigure.invalidate();

                for (VirtualControllerElement element : elements) {
                    element.invalidate();
                }
                notifyStateChanged();
            }
        });

    }

    Handler getHandler() {
        return handler;
    }

    public void hide() {
        if (visible) releaseInputs();
        visible = false;
        for (VirtualControllerElement element : elements) {
            element.setVisibility(View.INVISIBLE);
        }

        buttonConfigure.setVisibility(View.INVISIBLE);
        notifyStateChanged();
    }

    public void show() {
        visible = true;
        for (VirtualControllerElement element : elements) {
            element.setVisibility(View.VISIBLE);
        }

        buttonConfigure.setVisibility(View.VISIBLE);
        notifyStateChanged();
    }

    public boolean isVirtualGamepadVisible() {
        return visible && !elements.isEmpty();
    }

    public void setStateListener(Runnable listener) {
        stateListener = listener;
        notifyStateChanged();
    }

    private void notifyStateChanged() {
        if (stateListener != null) stateListener.run();
    }

    public Rect getUsableBounds() {
        return new Rect(usableBounds);
    }

    public void setSafeArea(Rect safeArea, int reservedWidth) {
        Rect next = new Rect(safeArea);
        next.right = Math.max(next.left + 1, next.right - reservedWidth);
        if (next.equals(usableBounds) || next.isEmpty()) return;
        usableBounds.set(next);
        fitLayout();
    }

    private boolean isLayoutInsideBounds() {
        for (VirtualControllerElement element : elements) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) element.getLayoutParams();
            if (params.width <= 0 || params.height <= 0 || !usableBounds.contains(
                    params.leftMargin, params.topMargin, params.leftMargin + params.width,
                    params.topMargin + params.height)) return false;
        }
        return true;
    }

    private void fitLayout() {
        int result = VirtualControllerConfigurationLoader.fitToBounds(elements, usableBounds);
        if (result < 0) {
            releaseInputs();
            currentMode = ControllerMode.MoveButtons;
            Toast.makeText(context, R.string.stream_side_layout_conflict, Toast.LENGTH_LONG).show();
        } else if (result > 0 && !adjustmentNotified) {
            adjustmentNotified = true;
            Toast.makeText(context, R.string.stream_side_layout_adjusted, Toast.LENGTH_LONG).show();
        }
        buttonConfigure.bringToFront();
        notifyStateChanged();
    }

    private void releaseInputs() {
        long now = SystemClock.uptimeMillis();
        MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
        for (VirtualControllerElement element : elements) element.dispatchTouchEvent(cancel);
        cancel.recycle();
        inputContext = new ControllerInputContext();
        sendControllerInputContext();
    }

    public void dispose() {
        stateListener = null;
        handler.removeCallbacksAndMessages(null);
    }

    public void removeElements() {
        for (VirtualControllerElement element : elements) {
            frame_layout.removeView(element);
        }
        elements.clear();

        frame_layout.removeView(buttonConfigure);
    }

    public void setOpacity(int opacity) {
        for (VirtualControllerElement element : elements) {
            element.setOpacity(opacity);
        }
    }


    public void addElement(VirtualControllerElement element, int x, int y, int width, int height) {
        elements.add(element);
        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(width, height);
        layoutParams.setMargins(x, y, 0, 0);

        frame_layout.addView(element, layoutParams);
        element.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
    }

    public List<VirtualControllerElement> getElements() {
        return elements;
    }

    private static final void _DBG(String text) {
        if (_PRINT_DEBUG_INFORMATION) {
            LimeLog.info("VirtualController: " + text);
        }
    }

    public void refreshLayout() {
        removeElements();

        DisplayMetrics screen = context.getResources().getDisplayMetrics();

        int buttonSize = (int)(screen.heightPixels*0.06f);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(buttonSize, buttonSize);
        params.leftMargin = 15;
        params.topMargin = 15;
        frame_layout.addView(buttonConfigure, params);
        buttonConfigure.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);

        // Start with the default layout
        VirtualControllerConfigurationLoader.createDefaultLayout(this, context);

        // Apply user preferences onto the default layout
        VirtualControllerConfigurationLoader.loadFromPreferences(this, context);
        fitLayout();
    }

    public ControllerMode getControllerMode() {
        return currentMode;
    }

    public ControllerInputContext getControllerInputContext() {
        return inputContext;
    }

    private void sendControllerInputContextInternal() {
        _DBG("INPUT_MAP + " + inputContext.inputMap);
        _DBG("LEFT_TRIGGER " + inputContext.leftTrigger);
        _DBG("RIGHT_TRIGGER " + inputContext.rightTrigger);
        _DBG("LEFT STICK X: " + inputContext.leftStickX + " Y: " + inputContext.leftStickY);
        _DBG("RIGHT STICK X: " + inputContext.rightStickX + " Y: " + inputContext.rightStickY);

        if (controllerHandler != null) {
            controllerHandler.reportOscState(
                    inputContext.inputMap,
                    inputContext.leftStickX,
                    inputContext.leftStickY,
                    inputContext.rightStickX,
                    inputContext.rightStickY,
                    inputContext.leftTrigger,
                    inputContext.rightTrigger
            );
        }
    }

    void sendControllerInputContext() {
        // Cancel retransmissions of prior gamepad inputs
        handler.removeCallbacks(delayedRetransmitRunnable);

        sendControllerInputContextInternal();

        // HACK: GFE sometimes discards gamepad packets when they are received
        // very shortly after another. This can be critical if an axis zeroing packet
        // is lost and causes an analog stick to get stuck. To avoid this, we retransmit
        // the gamepad state a few times unless another input event happens before then.
        handler.postDelayed(delayedRetransmitRunnable, 25);
        handler.postDelayed(delayedRetransmitRunnable, 50);
        handler.postDelayed(delayedRetransmitRunnable, 75);
    }
}
