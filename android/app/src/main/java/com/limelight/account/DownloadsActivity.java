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
import com.limelight.utils.HelpLauncher;
import com.limelight.utils.UiHelper;

// Aba "Downloads" — biblioteca de arquivos/links gerenciada pelo admin
// (Admin → Downloads do app). Arquivo: a VM baixa direto da URL pra pasta
// Downloads DELA (C:\Users\scg\Downloads) — NÃO pro aparelho (o bug era baixar
// pro celular; um driver de 750MB descia pro celular em vez da máquina em
// nuvem). Link abre no navegador.
public class DownloadsActivity extends Activity {

    private LinearLayout listBox;
    private TextView emptyText;
    private ProgressBar progress;
    private boolean busy;
    private View gpuLicenseCard;
    private Button gpuLicenseButton;
    private boolean applyingLicense;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        UiHelper.applyPreferredTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_downloads);
        UiHelper.applyStatusBarInset(findViewById(R.id.downloadsRoot));

        listBox = findViewById(R.id.downloadsList);
        emptyText = findViewById(R.id.downloadsEmpty);
        progress = findViewById(R.id.downloadsProgress);
        gpuLicenseCard = findViewById(R.id.gpuLicenseCard);
        gpuLicenseButton = findViewById(R.id.gpuLicenseButton);

        findViewById(R.id.downloadsBackButton).setOnClickListener(v -> finish());
        findViewById(R.id.downloadsRefreshButton).setOnClickListener(v -> reload());

        gpuLicenseButton.setOnClickListener(v -> applyGpuLicense());

        reload();
        loadGpuLicenseStatus();
    }

    // Card da licença NVIDIA vGPU — só aparece quando o admin configurou o
    // token no servidor (getGpuLicenseStatus). O cliente aplica com 1 toque.
    private void loadGpuLicenseStatus() {
        AccountManager.getGpuLicenseStatus(this, new AccountManager.ResultCallback<SpaceConnectApiClient.GpuLicenseStatusResponse>() {
            @Override
            public void onSuccess(SpaceConnectApiClient.GpuLicenseStatusResponse r) {
                boolean available = r != null && Boolean.TRUE.equals(r.available);
                gpuLicenseCard.setVisibility(available ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onError(String message) {
                gpuLicenseCard.setVisibility(View.GONE);
            }
        });
    }

    private void applyGpuLicense() {
        if (applyingLicense) return;
        applyingLicense = true;
        gpuLicenseButton.setEnabled(false);
        gpuLicenseButton.setText(R.string.gpu_license_applying);
        AccountManager.applyGpuLicense(this, new AccountManager.ResultCallback<SpaceConnectApiClient.GpuLicenseApplyResponse>() {
            @Override
            public void onSuccess(SpaceConnectApiClient.GpuLicenseApplyResponse r) {
                applyingLicense = false;
                gpuLicenseButton.setEnabled(true);
                gpuLicenseButton.setText(R.string.gpu_license_apply);
                boolean ok = r != null && Boolean.TRUE.equals(r.ok);
                String msg = (r != null && r.message != null) ? r.message
                        : (ok ? "Licença aplicada na sua máquina!" : "Não consegui aplicar a licença agora.");
                Toast.makeText(DownloadsActivity.this, msg, Toast.LENGTH_LONG).show();
            }

            @Override
            public void onError(String message) {
                applyingLicense = false;
                gpuLicenseButton.setEnabled(true);
                gpuLicenseButton.setText(R.string.gpu_license_apply);
                Toast.makeText(DownloadsActivity.this,
                        message != null ? message : "Não consegui aplicar a licença agora.",
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private void setBusy(boolean b) {
        busy = b;
        progress.setVisibility(b ? View.VISIBLE : View.GONE);
    }

    private void reload() {
        if (busy) return;
        setBusy(true);
        AccountManager.getDownloads(this, new AccountManager.ResultCallback<SpaceConnectApiClient.DownloadsResponse>() {
            @Override
            public void onSuccess(SpaceConnectApiClient.DownloadsResponse r) {
                setBusy(false);
                render(r != null ? r.downloads : null);
            }

            @Override
            public void onError(String message) {
                setBusy(false);
                Toast.makeText(DownloadsActivity.this, message != null ? message : getString(R.string.dl_load_fail), Toast.LENGTH_LONG).show();
                render(null);
            }
        });
    }

    private static String fmtSize(long bytes) {
        if (bytes <= 0) return "";
        if (bytes >= 1073741824L) return String.format(java.util.Locale.US, "%.1f GB", bytes / 1073741824.0);
        return Math.round(bytes / 1048576.0) + " MB";
    }

    private void render(SpaceConnectApiClient.DownloadItem[] items) {
        listBox.removeAllViews();
        int count = items != null ? items.length : 0;
        emptyText.setVisibility(count == 0 ? View.VISIBLE : View.GONE);
        if (items == null) return;

        LayoutInflater inflater = LayoutInflater.from(this);
        for (SpaceConnectApiClient.DownloadItem item : items) {
            View card = inflater.inflate(R.layout.item_download, listBox, false);

            TextView name = card.findViewById(R.id.dlName);
            TextView desc = card.findViewById(R.id.dlDescription);
            TextView meta = card.findViewById(R.id.dlMeta);
            Button action = card.findViewById(R.id.dlActionButton);

            name.setText((item.icon != null ? item.icon + "  " : "") + (item.name != null ? item.name : ""));
            if (item.description != null && !item.description.trim().isEmpty()) {
                desc.setText(item.description);
                desc.setVisibility(View.VISIBLE);
            } else {
                desc.setVisibility(View.GONE);
            }
            String size = fmtSize(item.sizeBytes);
            meta.setText(size);
            meta.setVisibility(size.isEmpty() ? View.GONE : View.VISIBLE);

            boolean isLink = "link".equals(item.kind);
            action.setText(isLink ? R.string.dl_open_link : R.string.dl_download_to_vm);
            action.setOnClickListener(v -> {
                if (item.url == null || item.url.trim().isEmpty()) return;
                if (isLink) {
                    HelpLauncher.launchUrl(this, item.url);
                } else {
                    startVmDownload(item, action);
                }
            });

            listBox.addView(card);
        }
    }

    // A VM baixa o arquivo direto da URL pra pasta Downloads DELA (não pro
    // aparelho). O botão vira barra de progresso via polling no status.
    private final java.util.Map<String, Boolean> downloadingToVm = new java.util.HashMap<>();
    private final android.os.Handler vmDlHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    private void startVmDownload(SpaceConnectApiClient.DownloadItem item, Button action) {
        final String id = item.id;
        if (id == null || downloadingToVm.containsKey(id)) return;
        downloadingToVm.put(id, true);
        action.setEnabled(false);
        action.setText(R.string.dl_to_vm_starting);
        AccountManager.startVmDownload(this, id, new AccountManager.ResultCallback<SpaceConnectApiClient.VmDownloadStartResponse>() {
            @Override
            public void onSuccess(SpaceConnectApiClient.VmDownloadStartResponse r) {
                Toast.makeText(DownloadsActivity.this,
                        (r != null && r.message != null) ? r.message : getString(R.string.dl_to_vm_started),
                        Toast.LENGTH_LONG).show();
                pollVmDownload(item, action);
            }

            @Override
            public void onError(String message) {
                downloadingToVm.remove(id);
                action.setEnabled(true);
                action.setText(R.string.dl_download_to_vm);
                Toast.makeText(DownloadsActivity.this, message != null ? message : getString(R.string.dl_to_vm_fail), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void pollVmDownload(final SpaceConnectApiClient.DownloadItem item, final Button action) {
        final String id = item.id;
        vmDlHandler.postDelayed(() -> {
            if (id == null || !downloadingToVm.containsKey(id)) return;
            AccountManager.getVmDownloadStatus(this, id, new AccountManager.ResultCallback<SpaceConnectApiClient.VmDownloadStatusResponse>() {
                @Override
                public void onSuccess(SpaceConnectApiClient.VmDownloadStatusResponse r) {
                    if (r == null || r.state == null) { pollVmDownload(item, action); return; }
                    switch (r.state) {
                        case "running":
                            int p = r.progress != null ? r.progress : -1;
                            action.setText(p >= 0
                                    ? getString(R.string.dl_to_vm_progress, p)
                                    : getString(R.string.dl_to_vm_starting));
                            pollVmDownload(item, action);
                            break;
                        case "done":
                            downloadingToVm.remove(id);
                            action.setEnabled(true);
                            action.setText(R.string.dl_download_to_vm);
                            Toast.makeText(DownloadsActivity.this, R.string.dl_to_vm_done, Toast.LENGTH_LONG).show();
                            break;
                        case "failed":
                            downloadingToVm.remove(id);
                            action.setEnabled(true);
                            action.setText(R.string.dl_download_to_vm);
                            Toast.makeText(DownloadsActivity.this,
                                    r.message != null ? r.message : getString(R.string.dl_to_vm_fail),
                                    Toast.LENGTH_LONG).show();
                            break;
                        default: // none
                            downloadingToVm.remove(id);
                            action.setEnabled(true);
                            action.setText(R.string.dl_download_to_vm);
                            break;
                    }
                }

                @Override
                public void onError(String message) {
                    // erro de rede no polling não mata — tenta de novo
                    pollVmDownload(item, action);
                }
            });
        }, 3000);
    }

    @Override
    protected void onDestroy() {
        vmDlHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
