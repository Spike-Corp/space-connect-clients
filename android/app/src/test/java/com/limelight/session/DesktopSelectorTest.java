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

    // Modo Desempenho (Sudo Maker): lança o app "Virtual Display" (SudoVDA,
    // resoluções desbloqueadas) em vez do "Desktop" normal.
    @Test public void prefersVirtualDisplayInPerformanceMode() throws Exception {
        NvApp desktop = new NvApp("Desktop", 10, false);
        NvApp virtualDisplay = new NvApp("Virtual Display", 20, false);
        NvApp chosen = DesktopSelector.select(Arrays.asList(desktop, virtualDisplay), true);
        assertSame(virtualDisplay, chosen);
        assertEquals(20, chosen.getAppId());
    }

    // Modo Padrão: continua abrindo o Desktop mesmo quando existe Virtual Display.
    @Test public void standardModeKeepsDesktopEvenWithVirtualDisplay() throws Exception {
        NvApp desktop = new NvApp("Desktop", 10, false);
        NvApp virtualDisplay = new NvApp("Virtual Display", 20, false);
        NvApp chosen = DesktopSelector.select(Arrays.asList(desktop, virtualDisplay), false);
        assertSame(desktop, chosen);
        assertEquals(10, chosen.getAppId());
    }

    // Overload antigo (sem arg) preserva comportamento: sempre Desktop.
    @Test public void defaultOverloadStaysDesktop() throws Exception {
        NvApp desktop = new NvApp("Desktop", 10, false);
        NvApp virtualDisplay = new NvApp("Virtual Display", 20, false);
        assertSame(desktop, DesktopSelector.select(Arrays.asList(desktop, virtualDisplay)));
    }

    // Desempenho pedido, mas a VM não tem Virtual Display: cai no Desktop (seguro).
    @Test public void fallsBackToDesktopWhenVirtualDisplayMissing() throws Exception {
        NvApp game = new NvApp("Steam", 99, false);
        NvApp desktop = new NvApp("Desktop", 4321, false);
        NvApp chosen = DesktopSelector.select(Arrays.asList(game, desktop), true);
        assertSame(desktop, chosen);
        assertEquals(4321, chosen.getAppId());
    }

    // Virtual Display não inicializado (id 0) é ignorado; ainda cai no Desktop.
    @Test public void ignoresUninitializedVirtualDisplay() throws Exception {
        NvApp badVirtual = new NvApp("Virtual Display", 0, false);
        NvApp desktop = new NvApp("Desktop", 4321, false);
        NvApp chosen = DesktopSelector.select(Arrays.asList(badVirtual, desktop), true);
        assertSame(desktop, chosen);
        assertEquals(4321, chosen.getAppId());
    }

    // Desktop e Virtual Display são "managed desktop apps": trocar de um pro outro
    // (ex.: Desktop -> Virtual Display no Sudo Maker) deve dar quit+relaunch, não
    // o erro "outro app aberto".
    @Test public void isManagedDesktopAppRecognizesDesktopVariants() {
        NvApp desktop = new NvApp("Desktop", 10, false);
        NvApp virtualDisplay = new NvApp("Virtual Display", 20, false);
        NvApp game = new NvApp("Steam", 99, false);
        java.util.List<NvApp> apps = Arrays.asList(desktop, virtualDisplay, game);
        assertTrue(DesktopSelector.isManagedDesktopApp(apps, 10));
        assertTrue(DesktopSelector.isManagedDesktopApp(apps, 20));
        // Um jogo real NUNCA é managed desktop -> mantém o erro (não mata o jogo).
        assertFalse(DesktopSelector.isManagedDesktopApp(apps, 99));
        // Id que não está na lista -> conservador: trata como não-managed.
        assertFalse(DesktopSelector.isManagedDesktopApp(apps, 12345));
        // Id 0 (nada rodando) -> não é managed.
        assertFalse(DesktopSelector.isManagedDesktopApp(apps, 0));
        // Lista nula é segura.
        assertFalse(DesktopSelector.isManagedDesktopApp(null, 10));
    }
}
