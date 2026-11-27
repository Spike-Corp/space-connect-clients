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

    // Ação decidida pelo plan() pro fluxo de entrada numa VM gerenciada.
    public enum Action {
        // Nada rodando (ou já no app desejado): só lançar/retomar o app desejado.
        LAUNCH_OR_RESUME,
        // Co-op: entrar na tela gerenciada que o HOST já usa (nunca dá quit).
        JOIN,
        // Dono trocando de desktop gerenciado (Desktop <-> Virtual Display): quit + relaunch.
        QUIT_RELAUNCH,
        // Jogo real / app desconhecido rodando: "outro app aberto" (HOST_BUSY).
        BUSY
    }

    public static final class Plan {
        public final NvApp app;      // app a transmitir (null quando BUSY)
        public final Action action;
        Plan(NvApp app, Action action) {
            this.app = app;
            this.action = action;
        }
    }

    // Decide o que fazer ao abrir uma VM gerenciada, cobrindo dono E amigo (co-op).
    //
    // Regra central do co-op: um AMIGO entrando numa VM onde o host já está num
    // desktop gerenciado (Desktop OU Virtual Display / Sudo Maker) SEMPRE JUNTA
    // nessa tela — nunca dá quit, nunca derruba o host, nunca bloqueia. É o que
    // conserta o bug "outro app aberto" quando o host usa o Sudo Maker: antes o
    // amigo pedia "Desktop", batia com o Virtual Display rodando e tentava matá-lo.
    // Apollo/Sunshine aceita vários clientes no MESMO app ativo (tela compartilhada),
    // mas não telas virtuais separadas por cliente — por isso o amigo tem que entrar
    // no app que já roda, não abrir outro.
    //
    // Fora do co-op o comportamento do dono é preservado exatamente:
    //  - nada rodando / mesmo app -> LAUNCH_OR_RESUME
    //  - trocando de desktop gerenciado -> QUIT_RELAUNCH
    //  - jogo real / id desconhecido -> BUSY (mantém "outro app aberto")
    public static Plan plan(Iterable<NvApp> apps, boolean preferVirtualDisplay,
                            int runningGameId, boolean friend)
            throws SessionOrchestrator.SessionException {
        // Amigo (co-op): junta no desktop gerenciado que o host já usa. Checado ANTES
        // do select() pra ser robusto mesmo se a VM não expõe o app "Desktop" (host
        // só no Virtual Display) — nesse caso select() lançaria DESKTOP_MISSING à toa.
        if (friend && isManagedDesktopApp(apps, runningGameId)) {
            return new Plan(appById(apps, runningGameId), Action.JOIN);
        }
        NvApp desired = select(apps, preferVirtualDisplay);
        if (runningGameId == 0 || runningGameId == desired.getAppId()) {
            return new Plan(desired, Action.LAUNCH_OR_RESUME);
        }
        if (isManagedDesktopApp(apps, runningGameId)) {
            return new Plan(desired, Action.QUIT_RELAUNCH);
        }
        return new Plan(desired, Action.BUSY);
    }

    static NvApp appById(Iterable<NvApp> apps, int appId) {
        if (apps != null) {
            for (NvApp app : apps) {
                if (app != null && app.getAppId() == appId) {
                    return app;
                }
            }
        }
        return null;
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
