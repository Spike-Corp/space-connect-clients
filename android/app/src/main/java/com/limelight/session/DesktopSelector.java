package com.limelight.session;

import com.limelight.nvstream.http.NvApp;

public final class DesktopSelector {
    private DesktopSelector() {}

    public static NvApp select(Iterable<NvApp> apps) throws SessionOrchestrator.SessionException {
        return select(apps, false);
    }

    // Escolhe o app a lançar no fluxo "managed desktop".
    // preferVirtualDisplay (modo Desempenho / Sudo Maker): tenta primeiro o app
    // "Virtual Display" (resoluções desbloqueadas via SudoVDA); se a VM não tiver,
    // cai no "Desktop" normal — seguro em qualquer máquina. Modo Padrão abre Desktop.
    public static NvApp select(Iterable<NvApp> apps, boolean preferVirtualDisplay)
            throws SessionOrchestrator.SessionException {
        if (apps != null) {
            if (preferVirtualDisplay) {
                for (NvApp app : apps) {
                    if (isLaunchable(app) && isVirtualDisplayName(app.getAppName().trim())) {
                        return app;
                    }
                }
            }
            for (NvApp app : apps) {
                if (isLaunchable(app) && isDesktopName(app.getAppName().trim())) {
                    return app;
                }
            }
        }
        throw new SessionOrchestrator.SessionException(SessionOrchestrator.Failure.DESKTOP_MISSING);
    }

    private static boolean isLaunchable(NvApp app) {
        return app != null && app.isInitialized() && app.getAppId() != 0 && app.getAppName() != null;
    }

    private static boolean isDesktopName(String name) {
        return "Desktop".equalsIgnoreCase(name);
    }

    // Nome real do app no Apollo/Moonlight nas VMs "Sudo Maker" (SudoVDA):
    // "Virtual Display" (+ variações/traduções plausíveis). Comparação exata, então
    // não conflita com o "Desktop".
    private static boolean isVirtualDisplayName(String name) {
        return "Virtual Display".equalsIgnoreCase(name)
                || "Virtual Desktop".equalsIgnoreCase(name)
                || "Display Virtual".equalsIgnoreCase(name)
                || "Área de trabalho virtual".equalsIgnoreCase(name);
    }
}
