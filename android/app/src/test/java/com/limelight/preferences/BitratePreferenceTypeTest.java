package com.limelight.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

/**
 * Regression test for the "app crashes on OK/save after changing the bitrate" bug.
 *
 * The bitrate is an int-backed SeekBarPreference (key "seekbar_bitrate_kbps"). An older code
 * path (StreamSettings.resetBitrateToDefault, run on every resolution/FPS change) persisted it
 * with putString. When the user then saved the bitrate slider, the framework's
 * Preference.persistInt() read the current value back with getPersistedInt() first, which threw
 * ClassCastException because the stored value was a String -> the app crashed.
 *
 * These tests use a fake SharedPreferences that reproduces Android's type-strict getInt/getString
 * (getInt throws ClassCastException on a String value, exactly like the real framework).
 */
public class BitratePreferenceTypeTest {

    private static final String KEY = PreferenceConfiguration.BITRATE_PREF_STRING;

    // Mimics android.preference.Preference.persistInt(): it reads the current value back as an
    // int (getPersistedInt) before writing. This is the exact line that crashed.
    private static void simulateFrameworkPersistInt(SharedPreferences prefs, int value) {
        prefs.getInt(KEY, ~value); // throws ClassCastException if the stored value is a String
        prefs.edit().putInt(KEY, value).apply();
    }

    @Test
    public void stringValueReproducesTheCrashOnSave() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        prefs.edit().putString(KEY, "20000").apply(); // legacy buggy write

        try {
            simulateFrameworkPersistInt(prefs, 15000); // user clicks OK
            fail("Expected ClassCastException reproducing the crash");
        } catch (ClassCastException expected) {
            // This is the crash we are fixing.
        }
    }

    @Test
    public void normalizeHealsStringValueSoSaveNoLongerCrashes() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        prefs.edit().putString(KEY, "20000").apply();

        assertTrue(PreferenceConfiguration.normalizeBitratePreferenceType(prefs));
        assertEquals(20000, prefs.getInt(KEY, -1)); // now an int, no exception

        simulateFrameworkPersistInt(prefs, 15000); // saving no longer crashes
        assertEquals(15000, prefs.getInt(KEY, -1));
    }

    @Test
    public void normalizeIsNoOpForIntValue() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        prefs.edit().putInt(KEY, 12000).apply();

        assertFalse(PreferenceConfiguration.normalizeBitratePreferenceType(prefs));
        assertEquals(12000, prefs.getInt(KEY, -1));
    }

    @Test
    public void normalizeIsNoOpForAbsentKey() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        assertFalse(PreferenceConfiguration.normalizeBitratePreferenceType(prefs));
    }

    @Test
    public void normalizeClampsHealedValueIntoValidRange() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        prefs.edit().putString(KEY, "999999").apply(); // absurdly high leftover

        assertTrue(PreferenceConfiguration.normalizeBitratePreferenceType(prefs));
        assertEquals(PreferenceConfiguration.MAX_BITRATE_KBPS_HIGH_TIER, prefs.getInt(KEY, -1));
    }

    @Test
    public void normalizeRemovesUnparseableStringSoDefaultApplies() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        prefs.edit().putString(KEY, "not-a-number").apply();

        assertTrue(PreferenceConfiguration.normalizeBitratePreferenceType(prefs));
        assertFalse(prefs.contains(KEY)); // removed -> falls back to default, never crashes
        simulateFrameworkPersistInt(prefs, 15000);
        assertEquals(15000, prefs.getInt(KEY, -1));
    }

    /**
     * Minimal in-memory SharedPreferences that reproduces Android's type strictness: getInt on a
     * value that isn't an Integer throws ClassCastException (the real framework does {@code
     * (Integer) value}). Only the methods used by the code under test are implemented.
     */
    private static final class FakeSharedPreferences implements SharedPreferences {
        private final Map<String, Object> store = new HashMap<>();

        @Override
        public int getInt(String key, int defValue) {
            if (!store.containsKey(key)) return defValue;
            return (Integer) store.get(key); // throws ClassCastException on a String, like Android
        }

        @Override
        public String getString(String key, String defValue) {
            if (!store.containsKey(key)) return defValue;
            return (String) store.get(key);
        }

        @Override
        public boolean contains(String key) {
            return store.containsKey(key);
        }

        @Override
        public Editor edit() {
            return new FakeEditor(store);
        }

        @Override
        public Map<String, ?> getAll() {
            return new HashMap<>(store);
        }

        @Override
        public Set<String> getStringSet(String key, Set<String> defValues) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long getLong(String key, long defValue) {
            throw new UnsupportedOperationException();
        }

        @Override
        public float getFloat(String key, float defValue) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakeEditor implements SharedPreferences.Editor {
        private final Map<String, Object> store;

        FakeEditor(Map<String, Object> store) {
            this.store = store;
        }

        @Override
        public SharedPreferences.Editor putInt(String key, int value) {
            store.put(key, value);
            return this;
        }

        @Override
        public SharedPreferences.Editor putString(String key, String value) {
            store.put(key, value);
            return this;
        }

        @Override
        public SharedPreferences.Editor remove(String key) {
            store.remove(key);
            return this;
        }

        @Override
        public boolean commit() {
            return true;
        }

        @Override
        public void apply() {
        }

        @Override
        public SharedPreferences.Editor putStringSet(String key, Set<String> values) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SharedPreferences.Editor putLong(String key, long value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SharedPreferences.Editor putFloat(String key, float value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SharedPreferences.Editor putBoolean(String key, boolean value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SharedPreferences.Editor clear() {
            store.clear();
            return this;
        }
    }
}
