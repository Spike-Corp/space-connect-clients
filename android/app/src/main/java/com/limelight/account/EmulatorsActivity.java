package com.limelight.account;

import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.R;
import com.limelight.utils.UiHelper;

import java.util.HashMap;
import java.util.Map;

/**
 * Emuladores — catálogo oficial da SpaceCloud (RetroArch e cia). Instala/abre
 * na VM com 1 toque. O backend faz tudo via guest agent do Proxmox (mesmo
 * mecanismo do upload de arquivo), então funciona em qualquer VM sem versão
 * nova do agent Windows. ROMs são responsabilidade do cliente (upload próprio).
 */
public class EmulatorsActivity extends Activity {

    private LinearLayout listBox;
    private TextView emptyText;
    private ProgressBar progress;
    private boolean busy;
    // Install em andamento (por emulador) + progresso 0-100. O install NÃO
    // trava a tela toda: o usuário pode sair da aba e até entrar na VM
    // enquanto baixa — só o card do emulador mostra a barra.
    private String installingEmuId;
    private int installingProgress = -1;
    // Último catálogo renderizado — pra re-renderizar o progresso sem refetch.
    private SpaceConnectApiClient.EmulatorEntry[] lastCatalog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        UiHelper.applyPreferredTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_emulators);
        UiHelper.applyStatusBarInset(findViewById(R.id.emulatorsRoot));

        listBox = findViewById(R.id.emulatorsList);
        emptyText = findViewById(R.id.emulatorsEmpty);
        progress = findViewById(R.id.emulatorsProgress);

        findViewById(R.id.emulatorsBackButton).setOnClickListener(v -> finish());
        findViewById(R.id.emulatorsRefreshButton).setOnClickListener(v -> reload());

        reload();
    }

    private void setBusy(boolean b) {
        busy = b;
        progress.setVisibility(b ? View.VISIBLE : View.GONE);
    }

    private void reload() {
        if (busy) return;
        setBusy(true);
        AccountManager.getEmulators(this, new AccountManager.ResultCallback<SpaceConnectApiClient.EmulatorsResponse>() {
            @Override
            public void onSuccess(SpaceConnectApiClient.EmulatorsResponse catalog) {
                loadStatus(catalog != null ? catalog.emulators : null);
            }

            @Override
            public void onError(String message) {
                setBusy(false);
                Toast.makeText(EmulatorsActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void loadStatus(final SpaceConnectApiClient.EmulatorEntry[] catalog) {
        // Status (instalado?) — se falhar (VM desligada), mostra o catálogo
        // com tudo "não instalado" mesmo assim.
        AccountManager.getEmulatorStatus(this, new AccountManager.ResultCallback<SpaceConnectApiClient.EmulatorStatusResponse>() {
            @Override
            public void onSuccess(SpaceConnectApiClient.EmulatorStatusResponse st) {
                Map<String, Boolean> installed = new HashMap<>();
                if (st != null && st.status != null) {
                    for (SpaceConnectApiClient.EmulatorStatusEntry e : st.status) {
                        installed.put(e.id, e.installed);
                    }
                }
                if (catalog != null) {
                    for (SpaceConnectApiClient.EmulatorEntry e : catalog) {
                        e.installed = Boolean.TRUE.equals(installed.get(e.id));
                    }
                }
                setBusy(false);
                lastCatalog = catalog;
                render(catalog);
            }

            @Override
            public void onError(String message) {
                setBusy(false);
                lastCatalog = catalog;
                render(catalog);
            }
        });
    }

    private void render(SpaceConnectApiClient.EmulatorEntry[] items) {
        listBox.removeAllViews();
        int count = items != null ? items.length : 0;
        emptyText.setVisibility(count == 0 ? View.VISIBLE : View.GONE);

        LayoutInflater inflater = LayoutInflater.from(this);
        if (items == null) return;
        for (SpaceConnectApiClient.EmulatorEntry emu : items) {
            View card = inflater.inflate(R.layout.item_emulator, listBox, false);

            TextView name = card.findViewById(R.id.emuName);
            TextView tagline = card.findViewById(R.id.emuTagline);
            TextView systems = card.findViewById(R.id.emuSystems);
            TextView badge = card.findViewById(R.id.emuInstalledBadge);
            Button action = card.findViewById(R.id.emuActionButton);

            name.setText((emu.icon != null ? emu.icon + "  " : "") + emu.name
                    + (emu.version != null && !emu.version.isEmpty() ? "  v" + emu.version : ""));
            tagline.setText(emu.tagline != null ? emu.tagline : "");
            if (emu.systems != null && emu.systems.length > 0) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < emu.systems.length; i++) {
                    if (i > 0) sb.append(" · ");
                    sb.append(emu.systems[i]);
                }
                systems.setText(sb.toString());
                systems.setVisibility(View.VISIBLE);
            } else {
                systems.setVisibility(View.GONE);
            }
            badge.setVisibility(emu.installed ? View.VISIBLE : View.GONE);

            // Barra de progresso do download — visível só no card instalando
            ProgressBar emuProgress = card.findViewById(R.id.emuProgress);
            TextView emuProgressText = card.findViewById(R.id.emuProgressText);
            boolean isInstalling = emu.id != null && emu.id.equals(installingEmuId);
            emuProgress.setVisibility(isInstalling ? View.VISIBLE : View.GONE);
            emuProgressText.setVisibility(isInstalling ? View.VISIBLE : View.GONE);
            if (isInstalling) {
                if (installingProgress >= 0) {
                    emuProgress.setIndeterminate(false);
                    emuProgress.setProgress(installingProgress);
                    emuProgressText.setText(installingProgress >= 85
                            ? getString(R.string.emu_install_extracting)
                            : getString(R.string.emu_install_downloading, installingProgress));
                } else {
                    emuProgress.setIndeterminate(true);
                    emuProgressText.setText(R.string.emu_install_starting);
                }
            }

            action.setText(emu.installed ? R.string.emu_open : R.string.emu_install);
            action.setEnabled(installingEmuId == null);
            action.setOnClickListener(v -> {
                if (busy || installingEmuId != null) return;
                if (emu.installed) launch(emu); else install(emu);
            });

            listBox.addView(card);
        }
    }

    private void install(SpaceConnectApiClient.EmulatorEntry emu) {
        installingEmuId = emu.id;
        installingProgress = 0;
        reloadCardsOnly();
        Toast.makeText(this, getString(R.string.emu_installing, emu.name), Toast.LENGTH_SHORT).show();
        AccountManager.installEmulator(this, emu.id, new AccountManager.ResultCallback<SpaceConnectApiClient.EmulatorActionResponse>() {
            @Override
            public void onSuccess(SpaceConnectApiClient.EmulatorActionResponse r) {
                // Install é assíncrono no backend (download grande — Cloudflare
                // mataria a request em ~100s). Faz polling até done/failed.
                pollInstall(emu, 0);
            }

            @Override
            public void onError(String message) {
                installingEmuId = null;
                installingProgress = -1;
                reloadCardsOnly();
                Toast.makeText(EmulatorsActivity.this, message != null ? message : getString(R.string.emu_install_fail), Toast.LENGTH_LONG).show();
            }
        });
    }

    // Re-renderiza os cards SEM o spinner de tela cheia (o install roda em
    // paralelo, com a barra de progresso no próprio card).
    private void reloadCardsOnly() {
        if (lastCatalog != null) render(lastCatalog);
    }

    private void pollInstall(final SpaceConnectApiClient.EmulatorEntry emu, final int attempt) {
        if (attempt > 240) { // 240 x 2s = ~8min
            installingEmuId = null;
            installingProgress = -1;
            reloadCardsOnly();
            Toast.makeText(this, R.string.emu_install_timeout, Toast.LENGTH_LONG).show();
            return;
        }
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            AccountManager.getEmulatorInstallStatus(this, emu.id, new AccountManager.ResultCallback<SpaceConnectApiClient.EmulatorInstallStatusResponse>() {
                @Override
                public void onSuccess(SpaceConnectApiClient.EmulatorInstallStatusResponse st) {
                    String state = st != null && st.state != null ? st.state : "none";
                    if ("done".equals(state)) {
                        installingEmuId = null;
                        installingProgress = -1;
                        Toast.makeText(EmulatorsActivity.this, R.string.emu_installed_ok, Toast.LENGTH_LONG).show();
                        reload();
                        return;
                    }
                    if ("failed".equals(state)) {
                        installingEmuId = null;
                        installingProgress = -1;
                        reloadCardsOnly();
                        Toast.makeText(EmulatorsActivity.this,
                                st != null && st.message != null ? st.message : getString(R.string.emu_install_fail),
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    // "none" por muito tempo = o job sumiu do backend (ex.: API
                    // reiniciou no meio). Antes ficava em loop pra sempre.
                    if ("none".equals(state) && attempt > 15) {
                        installingEmuId = null;
                        installingProgress = -1;
                        reloadCardsOnly();
                        Toast.makeText(EmulatorsActivity.this, R.string.emu_install_lost, Toast.LENGTH_LONG).show();
                        return;
                    }
                    // Progresso real do download (0-85%) vindo do backend
                    if (st != null && st.progress != null && st.progress >= 0
                            && st.progress != installingProgress) {
                        installingProgress = st.progress;
                        reloadCardsOnly();
                    }
                    pollInstall(emu, attempt + 1);
                }

                @Override
                public void onError(String message) {
                    // erro de rede no polling não mata a instalação — tenta de novo
                    pollInstall(emu, attempt + 1);
                }
            });
        }, 2000);
    }

    private void launch(SpaceConnectApiClient.EmulatorEntry emu) {
        setBusy(true);
        AccountManager.launchEmulator(this, emu.id, new AccountManager.ResultCallback<SpaceConnectApiClient.EmulatorActionResponse>() {
            @Override
            public void onSuccess(SpaceConnectApiClient.EmulatorActionResponse r) {
                setBusy(false);
                Toast.makeText(EmulatorsActivity.this,
                        r != null && r.message != null ? r.message : getString(R.string.emu_opened_ok),
                        Toast.LENGTH_LONG).show();
            }

            @Override
            public void onError(String message) {
                setBusy(false);
                Toast.makeText(EmulatorsActivity.this, message != null ? message : getString(R.string.emu_open_fail), Toast.LENGTH_LONG).show();
            }
        });
    }
}
