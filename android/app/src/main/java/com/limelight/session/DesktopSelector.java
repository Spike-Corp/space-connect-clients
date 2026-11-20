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

    // True quando o app que está rodando no host é um "desktop gerenciado" —
    // Desktop normal OU Virtual Display. Nesse caso, trocar de um pro outro
    // (ex.: Desktop -> Virtual Display ao entrar no Sudo Maker) é seguro via
    // quit+relaunch. Um jogo real, um id desconhecido ou id 0 retornam false,
    // pra NUNCA encerrar silenciosamente algo que não seja um desktop que nós
    // mesmos gerenciamos (mantém o erro "outro app aberto" só pra esses casos).
    public static boolean isManagedDesktopApp(Iterable<NvApp> apps, int appId) {
        if (appId == 0 || apps == null) {
            return false;
        }
        for (NvApp app : apps) {
            if (app != null && app.getAppId() == appId && app.getAppName() != null) {
                String name = app.getAppName().trim();
                return isDesktopName(name) || isVirtualDisplayName(name);
            }
        }
        return false;
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
