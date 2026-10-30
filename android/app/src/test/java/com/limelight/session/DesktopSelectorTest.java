package com.limelight.session;

import com.limelight.nvstream.http.NvApp;
import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class DesktopSelectorTest {
    @Test public void selectsActualDesktopIdNotFirstGameOrZero() throws Exception {
        NvApp game = new NvApp("Steam", 1, true);
        NvApp placeholder = new NvApp("Desktop", 0, false);
        NvApp desktop = new NvApp(" Desktop ", 193732, false);
        assertSame(desktop, DesktopSelector.select(Arrays.asList(game, placeholder, desktop)));
    }

    @Test public void gameOnlyListHasNoFallback() throws Exception {
        try {
            DesktopSelector.select(Arrays.asList(new NvApp("directLaunch", 2, false), new NvApp("Desktop game", 3, false)));
            fail("must not launch an arbitrary app");
        } catch (SessionOrchestrator.SessionException e) {
            assertEquals(SessionOrchestrator.Failure.DESKTOP_MISSING, e.failure);
        }
    }
}
