package com.limelight.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure logic for the user-defined custom special-key combos sent to the host from the
 * in-stream Special Keys menu (the "add keys" function and Windows+P support).
 *
 * Kept free of Android UI types so it is unit-testable: handles the main-key name to Windows
 * virtual-key-code mapping, building the ordered VK array (modifiers first - Ctrl, Alt, Shift,
 * Win - then the main key, matching how the built-in combos in Game.java are declared),
 * generating a human-readable label, and serializing the user's list for SharedPreferences.
 */
public final class SpecialKeyCombos {
    private SpecialKeyCombos() {}

    // Windows virtual-key codes (same numbering Game.java uses for the built-in combos).
    public static final short VK_LCONTROL = 0xA2;
    public static final short VK_LMENU = 0xA4; // Left Alt
    public static final short VK_LSHIFT = 0xA0;
    public static final short VK_LWIN = 0x5B; // Windows key

    private static final String RECORD_SEP = "\n";
    private static final String FIELD_SEP = "|";

    /** A user-defined combo expressed as modifier flags plus a single main key name. */
    public static final class CustomSpec {
        public final boolean ctrl;
        public final boolean alt;
        public final boolean shift;
        public final boolean win;
        public final String mainKey;

        public CustomSpec(boolean ctrl, boolean alt, boolean shift, boolean win, String mainKey) {
            this.ctrl = ctrl;
            this.alt = alt;
            this.shift = shift;
            this.win = win;
            this.mainKey = mainKey;
        }
    }

    // Ordered map of selectable main keys -> Windows virtual-key code. LinkedHashMap keeps the
    // picker order stable (letters, digits, function keys, then named keys).
    private static final Map<String, Short> MAIN_KEYS = new LinkedHashMap<>();
    static {
        for (char c = 'A'; c <= 'Z'; c++) {
            MAIN_KEYS.put(String.valueOf(c), (short) (0x41 + (c - 'A')));
        }
        for (char c = '0'; c <= '9'; c++) {
            MAIN_KEYS.put(String.valueOf(c), (short) (0x30 + (c - '0')));
        }
        for (int i = 1; i <= 12; i++) {
            MAIN_KEYS.put("F" + i, (short) (0x70 + (i - 1)));
        }
        MAIN_KEYS.put("Enter", (short) 0x0D);
        MAIN_KEYS.put("Space", (short) 0x20);
        MAIN_KEYS.put("Tab", (short) 0x09);
        MAIN_KEYS.put("Esc", (short) 0x1B);
        MAIN_KEYS.put("Backspace", (short) 0x08);
        MAIN_KEYS.put("Insert", (short) 0x2D);
        MAIN_KEYS.put("Delete", (short) 0x2E);
        MAIN_KEYS.put("Home", (short) 0x24);
        MAIN_KEYS.put("End", (short) 0x23);
        MAIN_KEYS.put("PageUp", (short) 0x21);
        MAIN_KEYS.put("PageDown", (short) 0x22);
        MAIN_KEYS.put("Left", (short) 0x25);
        MAIN_KEYS.put("Up", (short) 0x26);
        MAIN_KEYS.put("Right", (short) 0x27);
        MAIN_KEYS.put("Down", (short) 0x28);
        MAIN_KEYS.put("PrintScreen", (short) 0x2C);
    }

    /** The main keys offered in the "add key" picker, in display order. */
    public static List<String> availableMainKeys() {
        return new ArrayList<>(MAIN_KEYS.keySet());
    }

    public static boolean isValidMainKey(String name) {
        return name != null && MAIN_KEYS.containsKey(name);
    }

    /** Build the ordered VK array: Ctrl, Alt, Shift, Win, then the main key. */
    public static short[] toKeys(CustomSpec spec) {
        Short main = spec == null ? null : MAIN_KEYS.get(spec.mainKey);
        if (main == null) {
            throw new IllegalArgumentException("Unknown main key: " + (spec == null ? null : spec.mainKey));
        }
        List<Short> list = new ArrayList<>();
        if (spec.ctrl) list.add(VK_LCONTROL);
        if (spec.alt) list.add(VK_LMENU);
        if (spec.shift) list.add(VK_LSHIFT);
        if (spec.win) list.add(VK_LWIN);
        list.add(main);
        short[] out = new short[list.size()];
        for (int i = 0; i < list.size(); i++) {
            out[i] = list.get(i);
        }
        return out;
    }

    /** Human-readable label like "Ctrl+Shift+P". */
    public static String toLabel(CustomSpec spec) {
        StringBuilder sb = new StringBuilder();
        if (spec.ctrl) sb.append("Ctrl+");
        if (spec.alt) sb.append("Alt+");
        if (spec.shift) sb.append("Shift+");
        if (spec.win) sb.append("Win+");
        sb.append(spec.mainKey);
        return sb.toString();
    }

    /** Encode the list for SharedPreferences. Stable ordering, one record per line. */
    public static String serialize(List<CustomSpec> specs) {
        if (specs == null || specs.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (CustomSpec spec : specs) {
            if (!isValidMainKey(spec.mainKey)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(RECORD_SEP);
            }
            sb.append(spec.ctrl ? '1' : '0').append(FIELD_SEP)
                    .append(spec.alt ? '1' : '0').append(FIELD_SEP)
                    .append(spec.shift ? '1' : '0').append(FIELD_SEP)
                    .append(spec.win ? '1' : '0').append(FIELD_SEP)
                    .append(spec.mainKey);
        }
        return sb.toString();
    }

    /** Decode a SharedPreferences blob, silently skipping malformed or unknown records. */
    public static List<CustomSpec> deserialize(String blob) {
        List<CustomSpec> out = new ArrayList<>();
        if (blob == null || blob.trim().isEmpty()) {
            return out;
        }
        for (String line : blob.split("\\r?\\n")) {
            String record = line.trim();
            if (record.isEmpty()) {
                continue;
            }
            String[] parts = record.split("\\|", -1);
            if (parts.length != 5) {
                continue;
            }
            String mainKey = parts[4];
            if (!isValidMainKey(mainKey)) {
                continue;
            }
            out.add(new CustomSpec(
                    "1".equals(parts[0]),
                    "1".equals(parts[1]),
                    "1".equals(parts[2]),
                    "1".equals(parts[3]),
                    mainKey));
        }
        return Collections.unmodifiableList(out);
    }
}
