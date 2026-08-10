package com.limelight;

import android.app.AlertDialog;
import android.widget.ArrayAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * Simple in-stream menu offering quick access to actions that would otherwise require leaving
 * the stream or a physical keyboard shortcut. Ported (in simplified form) from Artemis
 * (github.com/MobinYengejehi/Artemis, a moonlight-android fork) - their version also integrates
 * clipboard sync, server commands, and external-display routing that depend on their "Apollo"
 * Sunshine fork, which isn't applicable to our GameStream/Sunshine backend, so those are omitted.
 * Special key sending and keyboard toggling reuse Game's own existing methods rather than
 * duplicating that logic here.
 */
public class GameMenu {

    private static class MenuOption {
        private final String label;
        private final Runnable runnable;

        MenuOption(String label, Runnable runnable) {
            this.label = label;
            this.runnable = runnable;
        }
    }

    private final Game game;
    private AlertDialog currentDialog;

    public GameMenu(Game game) {
        this.game = game;
    }

    private String getString(int id) {
        return game.getResources().getString(id);
    }

    private void showMenuDialog(String title, List<MenuOption> options) {
        AlertDialog.Builder builder = new AlertDialog.Builder(game);
        builder.setTitle(title);

        final ArrayAdapter<String> actions = new ArrayAdapter<>(game, android.R.layout.simple_list_item_1);
        for (MenuOption option : options) {
            actions.add(option.label);
        }

        builder.setAdapter(actions, (dialog, which) -> {
            MenuOption option = options.get(which);
            if (option.runnable != null) {
                option.runnable.run();
            }
        });

        if (currentDialog != null) {
            currentDialog.dismiss();
        }
        currentDialog = builder.show();
    }

    public void showMenu() {
        List<MenuOption> options = new ArrayList<>();

        options.add(new MenuOption(getString(R.string.game_menu_send_special_keys), game::showSpecialKeysMenu));
        options.add(new MenuOption(getString(R.string.game_menu_select_mouse_mode), game::selectMouseMode));
        options.add(new MenuOption(getString(R.string.game_menu_toggle_keyboard), game::toggleKeyboard));
        options.add(new MenuOption(getString(R.string.game_menu_toggle_full_keyboard), game::toggleFullKeyboard));
        options.add(new MenuOption(getString(R.string.game_menu_rotate_screen), game::rotateScreen));
        options.add(new MenuOption(getString(game.isZoomModeEnabled() ?
                R.string.game_menu_disable_zoom_mode : R.string.game_menu_enable_zoom_mode), game::toggleZoomMode));
        options.add(new MenuOption(getString(R.string.game_menu_disconnect), game::disconnect));
        options.add(new MenuOption(getString(R.string.game_menu_quit_session), game::quit));
        options.add(new MenuOption(getString(R.string.game_menu_cancel), null));

        showMenuDialog(getString(R.string.game_menu_title), options);
    }

    public void hideMenu() {
        if (currentDialog != null) {
            currentDialog.dismiss();
            currentDialog = null;
        }
    }

    public boolean isMenuOpen() {
        return currentDialog != null && currentDialog.isShowing();
    }
}
