package com.limelight.binding.input.virtual_controller.keyboard;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import com.limelight.Game;
import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.preferences.PreferenceConfiguration;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

public class KeyBoardLayoutController {
    private static final Set<Integer> MODIFIER_KEY_CODES = new HashSet<>();
    private static final Set<Integer> SPECIAL_KEY_CODES = new HashSet<>();
    private static final long POPUP_DURATION_MS = 75;

    private static final String ASSET_MAIN = "config/default_full_keyboard_main.json";
    private static final String ASSET_FUNC = "config/default_full_keyboard_func.json";
    private static final String ASSET_NUM = "config/default_full_keyboard_num.json";

    static {
        MODIFIER_KEY_CODES.add(KeyEvent.KEYCODE_ALT_LEFT);
        MODIFIER_KEY_CODES.add(KeyEvent.KEYCODE_ALT_RIGHT);
        MODIFIER_KEY_CODES.add(KeyEvent.KEYCODE_CTRL_LEFT);
        MODIFIER_KEY_CODES.add(KeyEvent.KEYCODE_CTRL_RIGHT);
        MODIFIER_KEY_CODES.add(KeyEvent.KEYCODE_SHIFT_LEFT);
        MODIFIER_KEY_CODES.add(KeyEvent.KEYCODE_SHIFT_RIGHT);
        MODIFIER_KEY_CODES.add(KeyEvent.KEYCODE_META_LEFT);
        MODIFIER_KEY_CODES.add(KeyEvent.KEYCODE_META_RIGHT);

        SPECIAL_KEY_CODES.add(KeyEvent.KEYCODE_TAB);
        SPECIAL_KEY_CODES.add(KeyEvent.KEYCODE_ENTER);
        SPECIAL_KEY_CODES.add(KeyEvent.KEYCODE_SPACE);
        SPECIAL_KEY_CODES.add(KeyEvent.KEYCODE_DEL);
        SPECIAL_KEY_CODES.add(KeyEvent.KEYCODE_FORWARD_DEL);
        SPECIAL_KEY_CODES.add(KeyEvent.KEYCODE_ESCAPE);
        SPECIAL_KEY_CODES.add(KeyEvent.KEYCODE_CAPS_LOCK);
        SPECIAL_KEY_CODES.add(KeyEvent.KEYCODE_INSERT);
        SPECIAL_KEY_CODES.add(KeyEvent.KEYCODE_DPAD_UP);
        SPECIAL_KEY_CODES.add(KeyEvent.KEYCODE_DPAD_DOWN);
        SPECIAL_KEY_CODES.add(KeyEvent.KEYCODE_DPAD_LEFT);
        SPECIAL_KEY_CODES.add(KeyEvent.KEYCODE_DPAD_RIGHT);
        SPECIAL_KEY_CODES.add(KeyEvent.KEYCODE_PAGE_UP);
        SPECIAL_KEY_CODES.add(KeyEvent.KEYCODE_PAGE_DOWN);
        SPECIAL_KEY_CODES.add(KeyEvent.KEYCODE_MOVE_HOME);
        SPECIAL_KEY_CODES.add(KeyEvent.KEYCODE_MOVE_END);
    }

    private final Context context;
    private final PreferenceConfiguration prefConfig;
    private final FrameLayout frame_layout;
    private final Handler handler;
    private final View keyboardView;
    // Reference to the hosting Game activity (always the context this controller is built with)
    // used by sendKeyEvent() to forward key/mouse input into the active stream.
    private final Game game;
    /** Meta (Shift/Ctrl/Alt/Win) só deste painel — usado em KeyEvent para não misturar com teclado físico. */
    private int softPanelMetaState = 0;
    private int lastPanelHeightPx = 0;
    private int tabBarHeightPx = 0;
    private FrameLayout tabContentRef;
    private TextView tabCloseRef;

    private ViewCallbacks viewCallbacks;
    public boolean shown = false;

    private PopupWindow keyPopup;
    private TextView keyPopupText;
    private Runnable hidePopupRunnable;

    public KeyBoardLayoutController(FrameLayout layout, Context context, PreferenceConfiguration prefConfig) {
        this.frame_layout = layout;
        this.context = context;
        this.prefConfig = prefConfig;
        this.game = (context instanceof Game) ? (Game) context : null;
        this.keyboardView = LayoutInflater.from(context).inflate(R.layout.layout_full_keyboard_panel, null);
        this.handler = new Handler(Looper.getMainLooper());
        initKeyPopup();
        initKeyboardPanel();
    }

    public void setViewCallbacks(ViewCallbacks viewCallbacks) {
        this.viewCallbacks = viewCallbacks;
    }

    // Para o teclado de texto não usamos teclas modificadoras “travadas” (Ctrl/Alt/Win coladas).
    private boolean isModifierKey(int keyCode) {
        return false;
    }

    private boolean isSpecialKey(int keyCode) {
        return SPECIAL_KEY_CODES.contains(keyCode) || MODIFIER_KEY_CODES.contains(keyCode);
    }

    private void initKeyboardPanel() {
        TextView tabKeyboard = keyboardView.findViewById(R.id.tab_keyboard);
        TextView tabMouse = keyboardView.findViewById(R.id.tab_mouse);
        TextView tabFunction = keyboardView.findViewById(R.id.tab_function);
        TextView tabClose = keyboardView.findViewById(R.id.tab_close);
        FrameLayout tabContent = keyboardView.findViewById(R.id.tab_content);
        tabContentRef = tabContent;
        tabCloseRef = tabClose;
        // Mantém o fundo azul do teclado (base), removendo apenas sobras de borda.
        keyboardView.setBackgroundColor(0xFF1A1A2E);
        tabContent.setBackgroundColor(0xFF1A1A2E);

        // Remove a faixa superior COMPLETAMENTE.
        View tabBar = keyboardView.findViewById(R.id.tab_bar);
        if (tabBar != null) {
            tabBarHeightPx = dpToPx(28);
            ViewGroup.LayoutParams lp = tabBar.getLayoutParams();
            if (lp != null) {
                lp.height = 0; // sem barra no topo
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                tabBar.setLayoutParams(lp);
            }
            tabBar.setVisibility(View.GONE);

            // Move o X para dentro do conteúdo, fixo no canto superior direito.
            if (tabBar instanceof LinearLayout) {
                ((LinearLayout) tabBar).removeView(tabClose);
            }
            FrameLayout.LayoutParams closeLp = new FrameLayout.LayoutParams(
                    dpToPx(40),
                    tabBarHeightPx,
                    Gravity.TOP | Gravity.END
            );
            closeLp.rightMargin = dpToPx(2);
            // Mantém o X no topo-direito com pequena margem.
            closeLp.topMargin = dpToPx(2);
            tabClose.setLayoutParams(closeLp);
            tabClose.setBackgroundColor(Color.TRANSPARENT);
            tabClose.setTextColor(Color.WHITE);
            tabClose.bringToFront();
            tabContent.addView(tabClose);
        }

        // Para o teclado de escrever texto: só usamos a aba de teclado principal, sem rótulo.
        tabKeyboard.setText("");
        tabKeyboard.setVisibility(View.GONE);
        // Deixa o X sem “linha azul”/destaque
        tabClose.setBackgroundColor(Color.TRANSPARENT);
        tabMouse.setVisibility(View.GONE);
        tabFunction.setVisibility(View.GONE);

        tabKeyboard.setOnClickListener(v -> rebuildTextKeyboardContent());
        tabClose.setOnClickListener(v -> hide());

        // Conteúdo é montado em refreshLayout() com lastPanelHeightPx correto (evita corte em telas pequenas).
    }

    /** Recria o teclado de texto com base na altura atual do painel (chamado após refreshLayout). */
    private void rebuildTextKeyboardContent() {
        if (tabContentRef == null || tabCloseRef == null) {
            return;
        }
        tabContentRef.removeAllViews();
        tabContentRef.addView(buildKeyboardTab());
        ensureCloseButtonOnTop(tabContentRef, tabCloseRef);
    }

    private void ensureCloseButtonOnTop(FrameLayout tabContent, TextView tabClose) {
        // Se já estiver anexado, remove para reanexar no topo da pilha de views.
        if (tabClose.getParent() instanceof ViewGroup) {
            ((ViewGroup) tabClose.getParent()).removeView(tabClose);
        }
        tabContent.addView(tabClose);
        tabClose.setClickable(true);
        tabClose.setFocusable(true);
        tabClose.bringToFront();
    }

    private void selectPickerTab(TextView selected, TextView... others) {
        selected.setBackgroundResource(R.drawable.bg_tab_selected);
        for (TextView other : others) {
            other.setBackgroundResource(R.drawable.bg_tab_unselected);
        }
    }

    // Para o teclado de texto usamos sempre o layout principal completo.
    private View buildKeyboardTab() {
        return buildSimpleKeyboardTab(ASSET_MAIN);
    }

    private View buildSimpleKeyboardTab(String assetPath) {
        HorizontalScrollView hScroll = new HorizontalScrollView(context);
        hScroll.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        hScroll.setPadding(0, 0, 0, 0);
        hScroll.setClipToPadding(false);
        hScroll.setFillViewport(true);

        LinearLayout keyboardContainer = new LinearLayout(context);
        keyboardContainer.setOrientation(LinearLayout.VERTICAL);
        // Sem padding: encosta nas bordas esquerda/direita/baixo
        keyboardContainer.setPadding(0, 0, 0, 0);
        // TOP: em telas pequenas, BOTTOM empurra o conteúdo e corta a 1.ª fila (Esc / F1–F12).
        keyboardContainer.setGravity(Gravity.TOP);
        keyboardContainer.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        hScroll.addView(keyboardContainer);

        loadKeyboardFromJson(assetPath, keyboardContainer);
        return hScroll;
    }

    private void loadKeyboardFromJson(String assetPath, LinearLayout container) {
        try {
            InputStream is = context.getAssets().open(assetPath);
            byte[] buffer = new byte[is.available()];
            int read = is.read(buffer);
            is.close();
            if (read <= 0) {
                return;
            }

            JSONObject json = new JSONObject(new String(buffer, StandardCharsets.UTF_8));
            JSONArray keyRowList = json.getJSONArray("keyRowList");
            JSONObject standardParam = json.getJSONObject("standardParam");
            float stdKeyWidth = (float) standardParam.getDouble("standardKeyWidth");
            float stdKeyHeight = (float) standardParam.getDouble("standardKeyHeight");
            float stdStartMargin = (float) standardParam.getDouble("standardStartMargin");
            float stdTopMargin = (float) standardParam.getDouble("standardTopMargin");

            DisplayMetrics dm = context.getResources().getDisplayMetrics();
            // Sem painel lateral neste teclado: usar a largura total para eliminar borda azul à direita.
            float availableWidth = dm.widthPixels;

            float maxRowWidth = 0f;
            for (int r = 0; r < keyRowList.length(); r++) {
                JSONObject row = keyRowList.getJSONObject(r);
                JSONArray items = row.getJSONArray("keyItemList");
                float rowWidth = 0f;
                for (int k = 0; k < items.length(); k++) {
                    JSONObject item = items.getJSONObject(k);
                    rowWidth += item.getDouble("width") * stdKeyWidth + item.getDouble("startMargin") * stdStartMargin;
                }
                maxRowWidth = Math.max(maxRowWidth, rowWidth);
            }

            float scale = maxRowWidth > 0 ? availableWidth / maxRowWidth : 1.0f;

            // Ajuste vertical: encolhe ligeiramente se a soma das filas ultrapassar metade da altura do ecrã,
            // para evitar ter de deslizar o teclado para ver a linha de Ctrl/Win/Alt/Space.
            float totalHeightPx = 0f;
            for (int r = 0; r < keyRowList.length(); r++) {
                JSONObject row = keyRowList.getJSONObject(r);
                float rowTopMargin = (float) row.getDouble("topMargin");
                float rowHeight = (float) row.getDouble("height");
                totalHeightPx += rowHeight * stdKeyHeight * scale;
                totalHeightPx += rowTopMargin * stdTopMargin * scale;
            }
            // Altura útil = painel real − área do botão fechar − margem (evita corte e erro 65% vs 70%).
            int topPadForClose = tabBarHeightPx + dpToPx(6);
            container.setPadding(0, topPadForClose, 0, 0);
            float panelH = lastPanelHeightPx > 0 ? lastPanelHeightPx : (dm.heightPixels * 0.65f);
            float maxKeyboardHeight = panelH - topPadForClose - dpToPx(8);
            if (maxKeyboardHeight < dpToPx(100)) {
                maxKeyboardHeight = Math.max(dpToPx(100), panelH * 0.92f - topPadForClose);
            }
            // Pequena folga para erros de arredondamento entre filas (telas muito pequenas).
            float heightBudget = maxKeyboardHeight * 0.98f;
            float heightScale = totalHeightPx > 0 && totalHeightPx > heightBudget
                    ? (heightBudget / totalHeightPx)
                    : 1.0f;

            for (int r = 0; r < keyRowList.length(); r++) {
                JSONObject row = keyRowList.getJSONObject(r);
                JSONArray items = row.getJSONArray("keyItemList");
                float rowTopMargin = (float) row.getDouble("topMargin");
                float rowHeight = (float) row.getDouble("height");

                LinearLayout rowLayout = new LinearLayout(context);
                rowLayout.setOrientation(LinearLayout.HORIZONTAL);
                rowLayout.setGravity(Gravity.START);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (int) (rowHeight * stdKeyHeight * scale * heightScale)
                );
                rowParams.topMargin = (int) (rowTopMargin * stdTopMargin * scale * heightScale);
                rowLayout.setLayoutParams(rowParams);

                int rowWidthPx = 0;
                LinearLayout.LayoutParams lastParams = null;
                for (int k = 0; k < items.length(); k++) {
                    JSONObject item = items.getJSONObject(k);
                    int keyCode = item.getInt("keyCode");
                    String displayName = item.getString("displayName");
                    float width = (float) item.getDouble("width");
                    float startMargin = (float) item.getDouble("startMargin");

                    TextView keyBtn = new TextView(context);
                    keyBtn.setText(displayName);
                    keyBtn.setTextColor(Color.WHITE);
                    float keySp = dm.widthPixels <= 360 ? 7.5f : (dm.widthPixels <= 480 ? 8f : 9f);
                    keyBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, keySp);
                    keyBtn.setGravity(Gravity.CENTER);
                    keyBtn.setBackgroundResource(R.drawable.bg_ax_keyboard_button);
                    keyBtn.setTypeface(Typeface.DEFAULT_BOLD);

                    int btnWidth = Math.max(1, Math.round(width * stdKeyWidth * scale));
                    int btnMargin = Math.max(0, Math.round(startMargin * stdStartMargin * scale));
                    LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                            btnWidth,
                            LinearLayout.LayoutParams.MATCH_PARENT
                    );
                    btnParams.leftMargin = btnMargin;
                    keyBtn.setLayoutParams(btnParams);

                    keyBtn.setOnTouchListener((v, event) -> onKeyboardButtonTouch(v, event, keyCode, displayName));
                    rowLayout.addView(keyBtn);

                    rowWidthPx += btnMargin + btnWidth;
                    lastParams = btnParams;
                }

                // Estica a última tecla da linha para encostar na borda direita (remove “borda azul”).
                if (lastParams != null) {
                    int leftover = Math.round(availableWidth) - rowWidthPx;
                    if (leftover > 0) {
                        lastParams.width = Math.max(1, lastParams.width + leftover);
                    }
                }

                container.addView(rowLayout);
            }
        } catch (Exception e) {
            LimeLog.warning("Error loading full keyboard JSON: " + e.getMessage());
        }
    }

    private boolean onKeyboardButtonTouch(View view, MotionEvent event, int keyCode, String displayName) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (!isSpecialKey(keyCode)) {
                showKeyPopup(view, displayName, keyCode);
            }
            dispatchSoftKey(KeyEvent.ACTION_DOWN, keyCode);
            view.setBackgroundResource(R.drawable.bg_ax_keyboard_button_confirm);
            performPressHaptics(view);
            return true;
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            handler.removeCallbacks(hidePopupRunnable);
            handler.postDelayed(hidePopupRunnable, POPUP_DURATION_MS);

            dispatchSoftKey(KeyEvent.ACTION_UP, keyCode);
            view.setBackgroundResource(R.drawable.bg_ax_keyboard_button);
            performReleaseHaptics(view);
            return true;
        }

        return false;
    }

    private static int panelModifierMetaMask(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_SHIFT_LEFT:
                return KeyEvent.META_SHIFT_LEFT_ON;
            case KeyEvent.KEYCODE_SHIFT_RIGHT:
                return KeyEvent.META_SHIFT_RIGHT_ON;
            case KeyEvent.KEYCODE_CTRL_LEFT:
                return KeyEvent.META_CTRL_LEFT_ON;
            case KeyEvent.KEYCODE_CTRL_RIGHT:
                return KeyEvent.META_CTRL_RIGHT_ON;
            case KeyEvent.KEYCODE_ALT_LEFT:
                return KeyEvent.META_ALT_LEFT_ON;
            case KeyEvent.KEYCODE_ALT_RIGHT:
                return KeyEvent.META_ALT_RIGHT_ON;
            case KeyEvent.KEYCODE_META_LEFT:
                return KeyEvent.META_META_LEFT_ON;
            case KeyEvent.KEYCODE_META_RIGHT:
                return KeyEvent.META_META_RIGHT_ON;
            default:
                return 0;
        }
    }

    /**
     * Envia KeyEvent com {@link KeyEvent#FLAG_SOFT_KEYBOARD} e meta só do painel, para o PC receber
     * a tecla certa (ex.: '[' e não Ctrl+'[' por estado global do cliente).
     */
    private void dispatchSoftKey(int action, int keyCode) {
        int meta;
        int modMask = panelModifierMetaMask(keyCode);
        if (modMask != 0) {
            if (action == KeyEvent.ACTION_DOWN) {
                meta = softPanelMetaState & ~modMask;
                softPanelMetaState |= modMask;
            } else {
                meta = softPanelMetaState & ~modMask;
                softPanelMetaState &= ~modMask;
            }
        } else {
            meta = softPanelMetaState;
        }
        long t = SystemClock.uptimeMillis();
        KeyEvent ev = new KeyEvent(t, t, action, keyCode, 0, meta, -1, 0,
                KeyEvent.FLAG_SOFT_KEYBOARD, InputDevice.SOURCE_KEYBOARD);
        sendKeyEvent(ev);
    }

    private void performPressHaptics(View view) {
        if (!prefConfig.enableKeyboardVibrate) {
            return;
        }
        view.performHapticFeedback(
                HapticFeedbackConstants.VIRTUAL_KEY,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING |
                        HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        );
    }

    private void performReleaseHaptics(View view) {
        if (!prefConfig.enableKeyboardVibrate) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY_RELEASE);
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        }
    }

    private void initKeyPopup() {
        keyPopupText = new TextView(context);
        keyPopupText.setBackgroundResource(R.drawable.key_popup_background);
        keyPopupText.setTextColor(Color.WHITE);
        keyPopupText.setTextSize(32);
        keyPopupText.setGravity(Gravity.CENTER);
        keyPopupText.setPadding(24, 16, 24, 16);

        keyPopup = new PopupWindow(
                keyPopupText,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        hidePopupRunnable = () -> keyPopup.dismiss();
    }

    private void showKeyPopup(View anchor, String displayName, int keyCode) {
        String popupText = displayName;
        if (popupText == null || popupText.isEmpty()) {
            KeyEvent tempEvent = new KeyEvent(KeyEvent.ACTION_DOWN, keyCode);
            int unicodeChar = tempEvent.getUnicodeChar(0);
            popupText = unicodeChar != 0 ? String.valueOf((char) unicodeChar)
                    : KeyEvent.keyCodeToString(keyCode).replace("KEYCODE_", "");
        }

        keyPopupText.setText(popupText);
        keyPopupText.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        );

        int popupWidth = keyPopupText.getMeasuredWidth();
        int[] location = new int[2];
        anchor.getLocationInWindow(location);

        int x = location[0] + (anchor.getWidth() - popupWidth) / 2;
        int y = (int) (location[1] - anchor.getHeight() * 1.5);

        if (keyPopup.isShowing()) {
            keyPopup.update(x, y, popupWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
        } else {
            keyPopup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y);
        }
    }

    public boolean isKeyboardVisible() {
        return keyboardView.getVisibility() == View.VISIBLE;
    }

    public void hide(boolean temporary) {
        softPanelMetaState = 0;
        if (prefConfig.enableKeyboardVibrate) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                keyboardView.performHapticFeedback(HapticFeedbackConstants.REJECT);
            } else {
                keyboardView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            }
        }
        keyboardView.setVisibility(View.GONE);
        if (!temporary) {
            shown = false;
        }

        if (viewCallbacks != null) {
            viewCallbacks.onKeyboardControllerVisibilityChange(false);
        }
    }

    public void hide() {
        hide(false);
    }

    public void show() {
        keyboardView.setVisibility(View.VISIBLE);
        shown = true;
        if (viewCallbacks != null) {
            viewCallbacks.onKeyboardControllerVisibilityChange(true);
        }
    }

    public void toggleVisibility() {
        if (keyboardView.getVisibility() == View.VISIBLE) {
            hide();
        } else {
            show();
        }
    }

    public void refreshLayout() {
        frame_layout.removeView(keyboardView);

        int height;
        int width;
        if (prefConfig.onscreenKeyboardAutoFitDisabled) {
            height = dpToPx(prefConfig.onscreenKeyboardHeight);
            int widthPreference = prefConfig.onscreenKeyboardWidth;
            width = widthPreference == 1000 ? ViewGroup.LayoutParams.MATCH_PARENT : dpToPx(widthPreference);
        } else {
            DisplayMetrics screen = context.getResources().getDisplayMetrics();
            width = screen.widthPixels;
            // Um pouco maior que 50% para caber a última linha sem arrastar
            height = (int) (screen.heightPixels * 0.65f);
        }

        lastPanelHeightPx = height;

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height);
        params.gravity = Gravity.BOTTOM;
        switch (prefConfig.onscreenKeyboardAlignMode) {
            case "left":
                params.gravity |= Gravity.START;
                break;
            case "right":
                params.gravity |= Gravity.END;
                break;
            case "center":
            default:
                params.gravity |= Gravity.CENTER_HORIZONTAL;
                break;
        }

        keyboardView.setAlpha(prefConfig.oscKeyboardOpacity / 100f);
        frame_layout.addView(keyboardView, params);
        rebuildTextKeyboardContent();
    }

    private int dpToPx(float dpValue) {
        float scale = this.context.getResources().getDisplayMetrics().density;
        return (int) (dpValue * scale + 0.5f);
    }

    public void sendKeyEvent(KeyEvent keyEvent) {
        if (game == null || !game.isConnected()) {
            return;
        }
        // 1-Mouse 0-Buttons 2-Stick 3-DPad 4-VScroll 5-HScroll
        if (keyEvent.getSource() == 4) {
            if (KeyEvent.ACTION_DOWN == keyEvent.getAction()) {
                int dir = keyEvent.getKeyCode();
                byte amount = (byte) (dir == 1 ? 1 : -1);
                game.mouseVScroll(amount);
            }
        } else if (keyEvent.getSource() == 5) {
            if (KeyEvent.ACTION_DOWN == keyEvent.getAction()) {
                int dir = keyEvent.getKeyCode();
                byte amount = (byte) (dir == 4 ? 1 : -1);
                game.mouseHScroll(amount);
            }
        } else if (keyEvent.getSource() == 1) {
            game.mouseButtonEvent(keyEvent.getKeyCode(), KeyEvent.ACTION_DOWN == keyEvent.getAction());
        } else {
            game.onKey(null, keyEvent.getKeyCode(), keyEvent);
        }
    }

    public interface ViewCallbacks {
        void onKeyboardControllerVisibilityChange(boolean visible);
    }
}

