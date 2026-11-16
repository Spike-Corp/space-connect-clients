package com.limelight.utils;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.limelight.utils.SpecialKeyCombos.CustomSpec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * Unit tests for the user-defined special-key combo codec used by the in-stream Special Keys
 * menu ("add keys" function + Windows+P). Pure logic, no Android UI, so it runs as a plain
 * JUnit test. Covers the main-key -> Windows virtual-key-code mapping, ordered VK array
 * construction (modifiers first, then the main key, matching the built-in combos), label
 * generation, and round-trip serialization for SharedPreferences.
 */
public class SpecialKeyCombosTest {

    @Test
    public void buildsOrderedVkArrayModifiersFirstThenMainKey() {
        CustomSpec spec = new CustomSpec(true, false, true, false, "P");
        assertArrayEquals(
                new short[]{SpecialKeyCombos.VK_LCONTROL, SpecialKeyCombos.VK_LSHIFT, (short) 0x50},
                SpecialKeyCombos.toKeys(spec));
        assertEquals("Ctrl+Shift+P", SpecialKeyCombos.toLabel(spec));
    }

    @Test
    public void winPlusPBuildsMetaThenP() {
        CustomSpec spec = new CustomSpec(false, false, false, true, "P");
        assertArrayEquals(
                new short[]{SpecialKeyCombos.VK_LWIN, (short) 0x50},
                SpecialKeyCombos.toKeys(spec));
        assertEquals("Win+P", SpecialKeyCombos.toLabel(spec));
    }

    @Test
    public void singleMainKeyWithoutModifiers() {
        CustomSpec spec = new CustomSpec(false, false, false, false, "F5");
        assertArrayEquals(new short[]{(short) 0x74}, SpecialKeyCombos.toKeys(spec));
        assertEquals("F5", SpecialKeyCombos.toLabel(spec));
    }

    @Test
    public void allModifiersOrderIsCtrlAltShiftWin() {
        CustomSpec spec = new CustomSpec(true, true, true, true, "Delete");
        assertArrayEquals(
                new short[]{SpecialKeyCombos.VK_LCONTROL, SpecialKeyCombos.VK_LMENU,
                        SpecialKeyCombos.VK_LSHIFT, SpecialKeyCombos.VK_LWIN, (short) 0x2E},
                SpecialKeyCombos.toKeys(spec));
        assertEquals("Ctrl+Alt+Shift+Win+Delete", SpecialKeyCombos.toLabel(spec));
    }

    @Test
    public void mainKeyCodesMatchWindowsVirtualKeys() {
        assertEquals((short) 0x41, SpecialKeyCombos.toKeys(new CustomSpec(false, false, false, false, "A"))[0]);
        assertEquals((short) 0x50, SpecialKeyCombos.toKeys(new CustomSpec(false, false, false, false, "P"))[0]);
        assertEquals((short) 0x5A, SpecialKeyCombos.toKeys(new CustomSpec(false, false, false, false, "Z"))[0]);
        assertEquals((short) 0x30, SpecialKeyCombos.toKeys(new CustomSpec(false, false, false, false, "0"))[0]);
        assertEquals((short) 0x70, SpecialKeyCombos.toKeys(new CustomSpec(false, false, false, false, "F1"))[0]);
        assertEquals((short) 0x7B, SpecialKeyCombos.toKeys(new CustomSpec(false, false, false, false, "F12"))[0]);
        assertEquals((short) 0x0D, SpecialKeyCombos.toKeys(new CustomSpec(false, false, false, false, "Enter"))[0]);
    }

    @Test
    public void unknownMainKeyThrows() {
        try {
            SpecialKeyCombos.toKeys(new CustomSpec(true, false, false, false, "ZZ"));
            fail("expected IllegalArgumentException for unknown main key");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void isValidMainKeyRecognizesKnownKeys() {
        assertTrue(SpecialKeyCombos.isValidMainKey("P"));
        assertTrue(SpecialKeyCombos.isValidMainKey("F5"));
        assertTrue(SpecialKeyCombos.isValidMainKey("Enter"));
        assertFalse(SpecialKeyCombos.isValidMainKey("ZZ"));
        assertFalse(SpecialKeyCombos.isValidMainKey(""));
        assertFalse(SpecialKeyCombos.isValidMainKey(null));
    }

    @Test
    public void availableMainKeysCoversLettersDigitsFunctionAndNamedKeys() {
        List<String> keys = SpecialKeyCombos.availableMainKeys();
        assertTrue(keys.contains("A"));
        assertTrue(keys.contains("P"));
        assertTrue(keys.contains("9"));
        assertTrue(keys.contains("F12"));
        assertTrue(keys.contains("Enter"));
        assertTrue(keys.contains("Left"));
    }

    @Test
    public void serializeDeserializeRoundTrip() {
        List<CustomSpec> specs = new ArrayList<>(Arrays.asList(
                new CustomSpec(false, false, false, true, "P"),
                new CustomSpec(true, true, false, false, "F4"),
                new CustomSpec(true, false, true, false, "Enter")));
        String blob = SpecialKeyCombos.serialize(specs);
        List<CustomSpec> parsed = SpecialKeyCombos.deserialize(blob);
        assertEquals(3, parsed.size());
        assertEquals("Win+P", SpecialKeyCombos.toLabel(parsed.get(0)));
        assertEquals("Ctrl+Alt+F4", SpecialKeyCombos.toLabel(parsed.get(1)));
        assertEquals("Ctrl+Shift+Enter", SpecialKeyCombos.toLabel(parsed.get(2)));
        // Re-serializing the parsed list yields the same blob (stable encoding).
        assertEquals(blob, SpecialKeyCombos.serialize(parsed));
    }

    @Test
    public void deserializeSkipsMalformedAndUnknownEntries() {
        // Valid Win+P, then a garbage record, then an unknown main key, then valid Ctrl+C.
        String blob = "0|0|0|1|P\ngarbage\n1|0|0|0|ZZ\n1|0|0|0|C";
        List<CustomSpec> parsed = SpecialKeyCombos.deserialize(blob);
        assertEquals(2, parsed.size());
        assertEquals("Win+P", SpecialKeyCombos.toLabel(parsed.get(0)));
        assertEquals("Ctrl+C", SpecialKeyCombos.toLabel(parsed.get(1)));
    }

    @Test
    public void deserializeEmptyOrNullYieldsEmptyList() {
        assertTrue(SpecialKeyCombos.deserialize(null).isEmpty());
        assertTrue(SpecialKeyCombos.deserialize("").isEmpty());
        assertTrue(SpecialKeyCombos.deserialize("   ").isEmpty());
    }
}
