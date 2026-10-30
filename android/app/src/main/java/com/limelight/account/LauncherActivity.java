package com.limelight.account;

import com.limelight.R;
import com.limelight.preferences.StreamSettings;
import com.limelight.utils.HelpLauncher;
import com.limelight.utils.UiHelper;
import com.limelight.utils.RequestScope;
import com.limelight.session.SessionLoadingActivity;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class LauncherActivity extends Activity {
    private static final int PICK_FILE_REQUEST = 4102;
    private static final long STATUS_POLL_MS = 5000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView statusText;
    private TextView detailsText;
    private TextView planHoursText;
    private Button primaryButton;
    private Button endSessionButton;
    private ProgressBar progressBar;
    private boolean requestRunning;
    private boolean resumed;
    private boolean pollingRequest;
    private boolean launchingSession;
    private int statusEpoch;
    private RequestScope statusScope;
    private java.util.concurrent.Future<?> statusFuture;
    private Boolean hasMachine;
    private SpaceConnectApiClient.MachineListItem[] machines;
    // Planos ativos ainda sem VM — alimenta o "Criar nova VM" mesmo quando o
    // usuário já tem máquinas (paridade com o desktop).
    private SpaceConnectApiClient.CreatablePlan[] creatable;
    private String selectedMachineId;
    private SpaceConnectApiClient.StatusResponse lastStatus;
    private boolean planLocked;
    private SpaceConnectApiClient.Entitlement[] upcomingEntitlements;
    private SpaceConnectApiClient.Entitlement upcomingEntitlement;
    // Controle das notificações de sessão (transição de estado + aviso único).
    private String lastNotifiedState;
    private boolean endWarned;

    private final Runnable pollStatus = new Runnable() {
        @Override
        public void run() {
            refreshStatus(false);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        UiHelper.applyPreferredTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launcher);
        // Botões do header ficavam sob a status bar em alguns aparelhos (paisagem/
        // cutout) = inclicáveis. Empurra o toolbar pra baixo do inset do sistema.
        UiHelper.applyStatusBarInset(findViewById(R.id.launcherToolbar));
        setupNotifications();

        statusText = findViewById(R.id.launcherStatus);
        detailsText = findViewById(R.id.launcherDetails);
        planHoursText = findViewById(R.id.launcherPlanHours);
        primaryButton = findViewById(R.id.launcherPrimaryButton);
        endSessionButton = findViewById(R.id.launcherEndSessionButton);
        progressBar = findViewById(R.id.launcherProgress);

        String email = AccountManager.getLoggedInEmail(this);
        TextView userNameText = findViewById(R.id.launcherUserName);
        String accountName = AccountManager.getLoggedInName(this);
        userNameText.setText(accountName != null && !accountName.trim().isEmpty()
                ? getString(R.string.launcher_greeting_named, accountName.trim())
                : getString(R.string.launcher_greeting_named, formatDisplayName(email)));
        TextView accountText = findViewById(R.id.launcherAccount);
        accountText.setText(email);
        userNameText.setTypeface(UiHelper.getDisplayTypeface(this), android.graphics.Typeface.BOLD);
        accountText.setTypeface(UiHelper.getBodyTypeface(this));
        ((TextView) findViewById(R.id.launcherToolbarTitle)).setTypeface(UiHelper.getBodyTypeface(this));
        statusText.setTypeface(UiHelper.getBodyTypeface(this), android.graphics.Typeface.BOLD);
        detailsText.setTypeface(UiHelper.getBodyTypeface(this));

        findViewById(R.id.launcherRefreshButton).setOnClickListener(v -> refreshStatus(true));
        findViewById(R.id.launcherLogoutButton).setOnClickListener(v -> {
            AccountManager.logout(LauncherActivity.this);
            startActivity(new Intent(LauncherActivity.this, LoginActivity.class));
            finish();
        });
        endSessionButton.setOnClickListener(v -> endSession());

        // Atalhos da tela de fila: controle (config de gamepad) e settings
        // abrem o StreamSettings (onde ficam as duas secoes), e o upload
        // envia arquivo do celular pra pasta Downloads da VM.
        findViewById(R.id.launcherControllerButton).setOnClickListener(v ->
                startActivity(new Intent(LauncherActivity.this, StreamSettings.class)));
        findViewById(R.id.launcherSettingsButton).setOnClickListener(v ->
                startActivity(new Intent(LauncherActivity.this, StreamSettings.class)));
        findViewById(R.id.launcherUploadButton).setOnClickListener(v -> pickFileForUpload());
        findViewById(R.id.launcherNetworkButton).setOnClickListener(v ->
                startActivity(new Intent(LauncherActivity.this, LatencyTestActivity.class)));
        findViewById(R.id.launcherNetworkHeaderButton).setOnClickListener(v ->
                startActivity(new Intent(LauncherActivity.this, LatencyTestActivity.class)));
        findViewById(R.id.launcherSettingsHeaderButton).setOnClickListener(v ->
                startActivity(new Intent(LauncherActivity.this, StreamSettings.class)));
        findViewById(R.id.launcherHelpButton).setOnClickListener(v ->
                HelpLauncher.launchUrl(this, "https://spacecloud.gg/ajuda"));
        // Relato de bug dentro do app → página "Bugs app" do painel admin.
        findViewById(R.id.launcherBugButton).setOnClickListener(v ->
                startActivity(new Intent(LauncherActivity.this, BugReportActivity.class)));
        // Amigos (beta): username, pedidos, permissões e VMs compartilhadas.
        findViewById(R.id.launcherFriendsButton).setOnClickListener(v ->
                startActivity(new Intent(LauncherActivity.this, FriendsActivity.class)));
        // Emuladores: catálogo oficial (RetroArch e cia), instala/abre na VM.
        findViewById(R.id.launcherEmulatorsButton).setOnClickListener(v ->
                startActivity(new Intent(LauncherActivity.this, EmulatorsActivity.class).putExtra("machineId", selectedMachineId)));
        // Downloads: biblioteca de arquivos/links gerenciada pelo admin.
        findViewById(R.id.launcherDownloadsButton).setOnClickListener(v ->
                startActivity(new Intent(LauncherActivity.this, DownloadsActivity.class).putExtra("machineId", selectedMachineId)));
        findViewById(R.id.launcherTransferDiskButton).setOnClickListener(v ->
                startActivity(new Intent(this, MachineManagementActivity.class)));
        // USB passthrough: o helper é Windows-only (roda no PC do cliente). No
        // Android o botão vira um how-to + link de download, visível com a VM pronta.
        findViewById(R.id.launcherUsbButton).setOnClickListener(v -> showUsbPassthroughInfo());
        // "Minhas VMs": gestão de máquinas disponível em QUALQUER estado (idle,
        // na fila, em sessão) — seletor/troca de VM + "Criar nova VM", como no
        // desktop. Antes a gestão sumia ao ligar uma VM (só sobrava Conectar/Encerrar).
        findViewById(R.id.launcherMachinesButton).setOnClickListener(v -> showMachinesDialog());
    }

    private void showUsbPassthroughInfo() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.usb_passthrough)
                .setMessage(R.string.usb_passthrough_info)
                .setNegativeButton(R.string.game_menu_cancel, null)
                .setPositiveButton(R.string.usb_download, (d, w) ->
                        HelpLauncher.launchUrl(this, "https://downloads.spacecloud.gg/SpaceUSB.exe"))
                .show();
    }

    private static String formatDisplayName(String email) {
        if (email == null || email.trim().isEmpty()) {
            return "Jogador";
        }
        String localPart = email.trim();
        int atIndex = localPart.indexOf('@');
        if (atIndex > 0) {
            localPart = localPart.substring(0, atIndex);
        }
        localPart = localPart.replace('.', ' ').replace('_', ' ').replace('-', ' ').trim();
        if (localPart.isEmpty()) {
            return "Jogador";
        }
        String[] words = localPart.split("\\s+");
        StringBuilder displayName = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (displayName.length() > 0) displayName.append(' ');
            displayName.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) displayName.append(word.substring(1));
        }
        return displayName.toString();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        launchingSession = false;
        refreshStatus(true);
    }

    @Override
    protected void onPause() {
        resumed = false;
        statusEpoch++;
        handler.removeCallbacks(pollStatus);
        if (statusScope != null) statusScope.close();
        if (statusFuture != null) statusFuture.cancel(true);
        if (pollingRequest) requestRunning = false;
        pollingRequest = false;
        super.onPause();
    }

    private void refreshStatus(boolean showProgress) {
        if (!resumed || requestRunning) return;
        requestRunning = true;
        pollingRequest = true;
        final int epoch = ++statusEpoch;
        statusScope = new RequestScope();
        if (showProgress) progressBar.setVisibility(View.VISIBLE);
        statusFuture = requestStatus(selectedMachineId, statusScope, new AccountManager.ResultCallback<SpaceConnectApiClient.StatusResponse>() {
            @Override
            public void onSuccess(SpaceConnectApiClient.StatusResponse result) {
                if (!resumed || epoch != statusEpoch) return;
                requestRunning = false;
                pollingRequest = false;
                progressBar.setVisibility(View.GONE);
                render(result);
                schedulePoll();
            }

            @Override
            public void onError(String message) {
                if (!resumed || epoch != statusEpoch) return;
                requestRunning = false;
                pollingRequest = false;
                progressBar.setVisibility(View.GONE);
                statusText.setText(R.string.launcher_status_error);
                detailsText.setText(message);
                schedulePoll();
            }
        });
    }

    protected java.util.concurrent.Future<?> requestStatus(String machineId, RequestScope scope,
            AccountManager.ResultCallback<SpaceConnectApiClient.StatusResponse> callback) {
        return AccountManager.getStatus(this, machineId, scope, callback);
    }

    private void render(SpaceConnectApiClient.StatusResponse status) {
        lastStatus = status;
        maybeNotifySession(status);
        renderPlanHours();
        endSessionButton.setVisibility(View.GONE);
        primaryButton.setVisibility(View.VISIBLE);
        primaryButton.setEnabled(true);

        if ("queued".equals(status.state) && status.queue != null) {
            SpaceConnectApiClient.BootTexts texts = status.bootTexts;
            String position = getString(R.string.launcher_queue_position, status.queue.position, status.queue.total);
            statusText.setText(texts == null ? getString(R.string.launcher_status_queued)
                    : texts.queueTitle(status.queue.position, status.queue.total, position));
            String reason = texts != null && texts.reasons != null ? texts.reasons.get(status.queue.blockedReason) : null;
            detailsText.setText(bootDetails(status, texts == null ? position
                    : SpaceConnectApiClient.BootTexts.text(reason,
                        SpaceConnectApiClient.BootTexts.text(texts.queueSearching, position))));
            primaryButton.setText(R.string.launcher_join_queue);
            primaryButton.setOnClickListener(v -> connect());
            return;
        }

        // USB passthrough só faz sentido com a VM pronta (helper é no PC, não no celular).
        findViewById(R.id.launcherUsbButton).setVisibility(
                "ready".equals(status.state) ? View.VISIBLE : View.GONE);

        if (status.session != null) {
            String machineName = status.session.machine != null
                    ? status.session.machine.name
                    : getString(R.string.launcher_machine);
            long remainingMinutes = Math.max(0, status.session.remainingMs / 60000L);
            detailsText.setText(getString(
                    R.string.launcher_session_details,
                    machineName,
                    remainingMinutes));

            if ("ready".equals(status.state)) {
                statusText.setText(R.string.launcher_status_ready);
                primaryButton.setText(R.string.launcher_join_queue);
                primaryButton.setOnClickListener(v -> connect());
                endSessionButton.setVisibility(View.VISIBLE);
            } else if ("ending".equals(status.state)) {
                statusText.setText(R.string.launcher_status_ending);
                primaryButton.setVisibility(View.GONE);
            } else {
                statusText.setText(SpaceConnectApiClient.BootTexts.text(
                        status.bootTexts == null ? null : status.bootTexts.bootingTitle,
                        getString(R.string.launcher_status_starting)));
                // Mostra a fase real do boot (criação/proteção/rede) em vez de só "starting".
                String phase = status.session.machine != null ? status.session.machine.creationPhase : null;
                detailsText.setText(bootDetails(status, creationPhaseLabel(phase)));
                primaryButton.setText(R.string.launcher_join_queue);
                primaryButton.setOnClickListener(v -> connect());
            }
            return;
        }

        if (isActivationPending()) {
            SpaceConnectApiClient.Entitlement pending = pendingActivationEntitlement();
            String planName = entitlementPlanName(pending);
            String activationDate = formatActivationDate(pending != null ? pending.availableAt : null);
            statusText.setText(R.string.launcher_status_scheduled);
            detailsText.setText(getString(
                    R.string.launcher_scheduled_details,
                    planName,
                    activationDate));
            primaryButton.setText(getString(R.string.launcher_activation_button, activationDate));
            primaryButton.setEnabled(false);
            return;
        }

        statusText.setText(R.string.launcher_status_idle);
        if (Boolean.FALSE.equals(hasMachine)) {
            detailsText.setText(R.string.launcher_no_machine_details);
            primaryButton.setText(R.string.launcher_create_machine);
            primaryButton.setOnClickListener(v -> promptCreateMachine());
        } else {
            detailsText.setText(R.string.launcher_idle_details);
            primaryButton.setText(R.string.launcher_join_queue);
            primaryButton.setOnClickListener(v -> joinQueue());
        }
        if (hasMachine == null) {
            checkMachines();
        }
    }

    private static final String NOTIF_CHANNEL_ID = "session_events";
    private static final int NOTIF_PERMISSION_REQ = 4201;
    private static final int NOTIF_ID_READY = 1001;
    private static final int NOTIF_ID_ENDING = 1002;

    private void setupNotifications() {
        // Canal (API 26+) + pedido de permissão em runtime (API 33+) — sem a
        // permissão o sistema engole a notificação silenciosamente.
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(NOTIF_CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(
                        NOTIF_CHANNEL_ID,
                        getString(R.string.notif_channel_name),
                        NotificationManager.IMPORTANCE_HIGH);
                channel.setDescription(getString(R.string.notif_channel_desc));
                // Som do canal desligado: o som é o toggle próprio (ringtone),
                // senão tocava em dobro.
                channel.setSound(null, null);
                nm.createNotificationChannel(channel);
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            boolean asked = prefs.getBoolean("notif_permission_asked", false);
            if (!asked && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                prefs.edit().putBoolean("notif_permission_asked", true).apply();
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, NOTIF_PERMISSION_REQ);
            }
        }
    }

    private boolean canPostNotifications() {
        if (android.os.Build.VERSION.SDK_INT < 33) return true;
        return checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void postSessionNotification(int id, String text) {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;
            Notification.Builder b = android.os.Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, NOTIF_CHANNEL_ID)
                    : new Notification.Builder(this);
            // Silenciosa: o som fica por conta do toggle próprio (ringtone), sem dobrar.
            Notification n = b.setContentTitle(getString(R.string.launcher_toolbar_title))
                    .setContentText(text)
                    .setSmallIcon(R.drawable.ic_bell)
                    .setAutoCancel(true)
                    .build();
            nm.notify(id, n);
        } catch (Exception ignored) {}
    }

    // Notificações de sessão (toggles em Configurações → Notificações da sessão):
    // aviso + som quando a máquina fica pronta e 5 min antes de desligar.
    private void maybeNotifySession(SpaceConnectApiClient.StatusResponse status) {
        if (status == null || status.state == null) return;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean nowReady = "ready".equals(status.state);
        boolean firstSample = lastNotifiedState == null;

        if (nowReady && !firstSample && !"ready".equals(lastNotifiedState)) {
            if (status.session != null) VmMonitorService.sessionNotice(this, true, status.session.id,
                    status.session.machine == null ? "" : status.session.machine.name);
        }
        if (nowReady && status.session != null) {
            long mins = Math.max(0, status.session.remainingMs / 60000L);
            if (!endWarned && mins > 0 && mins <= 5) {
                endWarned = true;
                VmMonitorService.sessionNotice(this, false, status.session.id,
                        status.session.machine == null ? "" : status.session.machine.name);
            }
        }
        if (!nowReady) endWarned = false;
        lastNotifiedState = status.state;
    }

    private void playNotifySound() {
        try {
            Ringtone ringtone = RingtoneManager.getRingtone(this,
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION));
            if (ringtone != null) ringtone.play();
        } catch (Exception ignored) {
            // Sem áudio/permissão: a notificação visual (Toast) já cobre.
        }
    }

    // Rótulo amigável da fase de criação/boot (mesmo vocabulário do site/desktop).
    private String creationPhaseLabel(String phase) {
        SpaceConnectApiClient.BootTexts texts = lastStatus == null ? null : lastStatus.bootTexts;
        if (texts != null && texts.phases != null) {
            String custom = texts.phases.get(phase);
            if (custom != null && !custom.trim().isEmpty()) return custom;
        }
        if (phase == null || phase.isEmpty()) return SpaceConnectApiClient.BootTexts.text(
                texts == null ? null : texts.defaultTitle, getString(R.string.phase_generic));
        int res;
        switch (phase) {
            case "queued": res = R.string.phase_queued; break;
            case "checking_snapshot": res = R.string.phase_checking_snapshot; break;
            case "restoring_disk": res = R.string.phase_restoring_disk; break;
            case "restoring_instance": res = R.string.phase_restoring_instance; break;
            case "creating_instance": res = R.string.phase_creating_instance; break;
            case "attaching_gpu": res = R.string.phase_attaching_gpu; break;
            case "starting": res = R.string.phase_starting; break;
            case "securing": res = R.string.phase_securing; break;
            case "setting_password": res = R.string.phase_setting_password; break;
            case "configuring_network": res = R.string.phase_configuring_network; break;
            case "waiting_agent": res = R.string.phase_waiting_agent; break;
            case "ready": res = R.string.phase_ready; break;
            default: return getString(R.string.phase_generic);
        }
        return getString(res);
    }

    private String bootDetails(SpaceConnectApiClient.StatusResponse status, String phase) {
        SpaceConnectApiClient.BootTexts texts = status.bootTexts;
        if (texts == null) return phase;
        StringBuilder body = new StringBuilder();
        appendBootLine(body, texts.eyebrow);
        appendBootLine(body, texts.subtitle);
        appendBootLine(body, status.bootElapsedSeconds >= 60
                ? SpaceConnectApiClient.BootTexts.text(texts.takingLonger, "{reason}").replace("{reason}", phase)
                : phase);
        if (texts.steps != null) {
            for (int i = 0; i < texts.steps.length; i++) {
                if (texts.steps[i] != null && !texts.steps[i].trim().isEmpty()) {
                    appendBootLine(body, (i + 1) + ". " + texts.steps[i]);
                }
            }
        }
        if (texts.tips != null && texts.tips.length > 0) {
            int index = (int) ((System.currentTimeMillis() / 6000) % texts.tips.length);
            appendBootLine(body, texts.tips[index]);
        }
        return body.toString();
    }

    private static void appendBootLine(StringBuilder body, String text) {
        if (text == null || text.trim().isEmpty()) return;
        if (body.length() > 0) body.append("\n\n");
        body.append(text);
    }

    // Descobre se o usuário já tem alguma VM dedicada provisionada. Sem isso o
    // app só sabe oferecer "entrar na fila", que falha silenciosamente (loop)
    // pra quem nunca teve VM criada — precisa oferecer "Criar minha VM" antes.
    private void checkMachines() {
        AccountManager.getMachines(this, new AccountManager.ResultCallback<SpaceConnectApiClient.MachinesResponse>() {
            @Override
            public void onSuccess(SpaceConnectApiClient.MachinesResponse result) {
                planLocked = result.locked;
                upcomingEntitlements = result.upcomingEntitlements;
                upcomingEntitlement = firstUpcomingEntitlement();
                machines = result.machines;
                creatable = result.creatable;
                hasMachine = machines != null && machines.length > 0;
                if (machines != null && machines.length == 1) {
                    selectedMachineId = machines[0].id;
                } else if (!containsMachine(selectedMachineId)) {
                    selectedMachineId = null;
                }
                renderPlanHours();
                render(lastStatus);
                updateTransferDiskButton();
                updateMachinesButton();
            }

            @Override
            public void onError(String message) {
                // Se a checagem falhar, não bloqueia o fluxo normal de fila.
            }
        });
    }

    // Entrada única da criação de VM. Com vários planos criáveis, deixa o
    // usuário escolher de qual plano criar (demo, pacote, etc.); com um só, usa
    // ele direto; sem info (lista ainda não carregada) cai no modo antigo em que
    // o backend escolhe o plano.
    private void startCreateMachineFlow() {
        if (creatable != null && creatable.length > 1) {
            String[] labels = new String[creatable.length];
            for (int i = 0; i < creatable.length; i++) {
                labels[i] = creatablePlanLabel(creatable[i]);
            }
            new AlertDialog.Builder(this)
                    .setTitle(R.string.launcher_select_plan_title)
                    .setItems(labels, (dialog, which) ->
                            promptCreateMachine(creatable[which].balanceId))
                    .setNegativeButton(R.string.game_menu_cancel, null)
                    .show();
            return;
        }
        String balanceId = (creatable != null && creatable.length == 1) ? creatable[0].balanceId : null;
        promptCreateMachine(balanceId);
    }

    private void promptCreateMachine() {
        startCreateMachineFlow();
    }

    private void promptCreateMachine(String balanceId) {
        final EditText passwordField = new EditText(this);
        passwordField.setHint(R.string.launcher_create_machine_password_hint);
        passwordField.setInputType(
                android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);

        new AlertDialog.Builder(this)
                .setTitle(R.string.launcher_create_machine_title)
                .setMessage(R.string.launcher_create_machine_message)
                .setView(passwordField)
                .setNegativeButton(R.string.game_menu_cancel, null)
                .setPositiveButton(R.string.launcher_confirm, (dialog, which) ->
                        createMachine(passwordField.getText().toString(), balanceId))
                .show();
    }

    // Rótulo de um plano criável: "xTerminator NW · 10h" / "… · ilimitado".
    private String creatablePlanLabel(SpaceConnectApiClient.CreatablePlan plan) {
        if (plan == null) return getString(R.string.launcher_plan_fallback);
        String name = plan.planName != null && !plan.planName.trim().isEmpty()
                ? plan.planName.trim()
                : prettifyPlanSlug(plan.planSlug);
        if (plan.unlimited) {
            return name + " · " + getString(R.string.launcher_plan_unlimited_short);
        }
        return name + " · " + formatHours(plan.hoursRemaining);
    }

    private void createMachine(String password) {
        createMachine(password, null);
    }

    private void createMachine(String password, String balanceId) {
        if (requestRunning) return;
        requestRunning = true;
        progressBar.setVisibility(View.VISIBLE);
        AccountManager.createMachine(this, password, balanceId, new AccountManager.ResultCallback<SpaceConnectApiClient.CreateMachineResponse>() {
            @Override
            public void onSuccess(SpaceConnectApiClient.CreateMachineResponse result) {
                requestRunning = false;
                progressBar.setVisibility(View.GONE);
                hasMachine = null;
                Toast.makeText(LauncherActivity.this, R.string.launcher_create_machine_success, Toast.LENGTH_LONG).show();
                refreshStatus(true);
            }

            @Override
            public void onError(String message) {
                requestRunning = false;
                progressBar.setVisibility(View.GONE);
                Toast.makeText(LauncherActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    // Botão "Minhas VMs": visível quando há máquinas OU planos criáveis. É o
    // ponto de gestão que funciona em qualquer estado (inclusive em sessão).
    private void updateMachinesButton() {
        boolean show = (machines != null && machines.length > 0)
                || (creatable != null && creatable.length > 0);
        findViewById(R.id.launcherMachinesButton).setVisibility(show ? View.VISIBLE : View.GONE);
    }

    // Painel de gestão de VMs: lista as máquinas (marcando a em sessão/selecionada)
    // e oferece "Criar nova VM". Disponível em QUALQUER estado — era o que
    // faltava no app: ao ligar uma VM a gestão das outras sumia.
    private void showMachinesDialog() {
        boolean hasMachines = machines != null && machines.length > 0;
        boolean hasCreatable = creatable != null && creatable.length > 0;
        if (!hasMachines && !hasCreatable) {
            startCreateMachineFlow();
            return;
        }

        final String sessionMachineId = lastStatus != null && lastStatus.session != null
                && lastStatus.session.machine != null ? lastStatus.session.machine.id : null;

        final java.util.List<String> labels = new java.util.ArrayList<>();
        final java.util.List<SpaceConnectApiClient.MachineListItem> rows = new java.util.ArrayList<>();
        if (hasMachines) {
            for (SpaceConnectApiClient.MachineListItem m : machines) {
                if (m == null) continue;
                String name = (m.name == null || m.name.trim().isEmpty()) ? m.id : m.name;
                String label = name + " (" + m.provider + ")" + planHoursSuffix(m);
                if (m.id != null && m.id.equals(sessionMachineId)) {
                    label = "▶ " + label + "\n" + getString(R.string.launcher_machine_in_session);
                } else if (m.id != null && m.id.equals(selectedMachineId)) {
                    label = label + "\n" + getString(R.string.launcher_machine_selected);
                }
                labels.add(label);
                rows.add(m);
            }
        }
        final int createIndex = labels.size();
        if (hasCreatable) {
            labels.add(getString(R.string.launcher_create_new_machine));
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.launcher_machines_title)
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    if (hasCreatable && which == createIndex) {
                        startCreateMachineFlow();
                    } else if (which >= 0 && which < rows.size()) {
                        onMachineChosen(rows.get(which), sessionMachineId);
                    }
                })
                .setNegativeButton(R.string.game_menu_cancel, null)
                .show();
    }

    private void onMachineChosen(SpaceConnectApiClient.MachineListItem machine, String sessionMachineId) {
        if (machine == null || machine.id == null) return;
        statusEpoch++;
        if (statusScope != null) statusScope.close();
        if (statusFuture != null) statusFuture.cancel(true);
        if (pollingRequest) requestRunning = false;
        pollingRequest = false;
        lastStatus = null;
        lastNotifiedState = null;
        endWarned = false;
        selectedMachineId = machine.id;
        primaryButton.setEnabled(false);
        endSessionButton.setVisibility(View.GONE);
        statusText.setText(R.string.vm_loading);
        detailsText.setText(machine.name);
        renderPlanHours();
        refreshStatus(true);
    }

    private String machineLabelForId(String machineId) {
        SpaceConnectApiClient.MachineListItem m = machineForId(machineId);
        if (m == null) return getString(R.string.launcher_machine);
        return (m.name == null || m.name.trim().isEmpty()) ? m.id : m.name;
    }

    private void updateTransferDiskButton() {
        findViewById(R.id.launcherTransferDiskButton).setVisibility(
                machines != null && machines.length > 0 ? View.VISIBLE : View.GONE);
    }

    private void joinQueue() {
        if (machines == null || machines.length == 0) {
            checkMachines();
            Toast.makeText(this, R.string.launcher_no_machine_details, Toast.LENGTH_LONG).show();
            return;
        }
        if (machines.length == 1) {
            selectedMachineId = machines[0].id;
            joinQueueForMachine(selectedMachineId);
            return;
        }
        if (containsMachine(selectedMachineId)) {
            joinQueueForMachine(selectedMachineId);
            return;
        }

        String[] labels = new String[machines.length];
        for (int i = 0; i < machines.length; i++) {
            String name = machines[i].name == null || machines[i].name.trim().isEmpty()
                    ? machines[i].id
                    : machines[i].name;
            labels[i] = name + " (" + machines[i].provider + ")" + planHoursSuffix(machines[i]);
        }
        int checked = 0;
        for (int i = 0; i < machines.length; i++) {
            if (machines[i].id != null && machines[i].id.equals(selectedMachineId)) {
                checked = i;
                break;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.launcher_select_machine_title)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    selectedMachineId = machines[which].id;
                    dialog.dismiss();
                    joinQueueForMachine(selectedMachineId);
                })
                .setNegativeButton(R.string.game_menu_cancel, null)
                .show();
    }

    private boolean containsMachine(String machineId) {
        if (machineId == null || machines == null) return false;
        for (SpaceConnectApiClient.MachineListItem machine : machines) {
            if (machine != null && machineId.equals(machine.id)) return true;
        }
        return false;
    }

    // Apelido custom (accountName) da VM indicada, se já tivermos a lista
    // carregada — null quando o dono nunca renomeou (usa o hostname do Apollo).
    private String accountNameFor(String machineId) {
        if (machineId == null || machines == null) return null;
        for (SpaceConnectApiClient.MachineListItem machine : machines) {
            if (machine != null && machineId.equals(machine.id)) return machine.accountName;
        }
        return null;
    }

    // Máquina "em foco" pra exibir saldo: a da sessão ativa, senão a selecionada,
    // senão a única da conta.
    private SpaceConnectApiClient.MachineListItem effectiveMachine() {
        if (machines == null || machines.length == 0) return null;
        String sessionMachineId = lastStatus != null && lastStatus.session != null
                && lastStatus.session.machine != null ? lastStatus.session.machine.id : null;
        if (sessionMachineId != null) {
            for (SpaceConnectApiClient.MachineListItem m : machines) {
                if (m != null && sessionMachineId.equals(m.id)) return m;
            }
        }
        if (selectedMachineId != null) {
            for (SpaceConnectApiClient.MachineListItem m : machines) {
                if (m != null && selectedMachineId.equals(m.id)) return m;
            }
        }
        return machines.length == 1 ? machines[0] : null;
    }

    // "builder-lite" → "Builder Lite" (o backend só manda o slug do plano).
    private static String prettifyPlanSlug(String slug) {
        if (slug == null || slug.trim().isEmpty()) return "Plano";
        String clean = slug.trim().replaceAll("-nw$", "");
        StringBuilder out = new StringBuilder();
        for (String word : clean.split("[-_]")) {
            if (word.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) out.append(word.substring(1));
        }
        return out.length() > 0 ? out.toString() : slug;
    }

    private static String formatHours(double hours) {
        if (hours <= 0) return "0h";
        if (Math.floor(hours) == hours) return String.format(java.util.Locale.US, "%.0fh", hours);
        return String.format(java.util.Locale.US, "%.1fh", hours);
    }

    // Sufixo de saldo pra uma máquina: " · 87h" / " · ilimitado" /
    // " · ativação 28/10" (pré-venda).
    private String planHoursSuffix(SpaceConnectApiClient.MachineListItem machine) {
        if (machine == null || machine.entitlement == null) return "";
        SpaceConnectApiClient.Entitlement ent = machine.entitlement;
        if (!ent.active) {
            return isFutureEntitlement(ent)
                    ? " · " + getString(
                            R.string.launcher_plan_activation_short,
                            formatActivationDate(ent.availableAt))
                    : "";
        }
        if (ent.unlimited) return " · " + getString(R.string.launcher_plan_unlimited_short);
        double total = ent.hoursRemaining;
        String suffix = " · " + formatHours(total);
        if (ent.bonusHours > 0) {
            suffix += getString(R.string.launcher_plan_hours_bonus, formatHours(ent.bonusHours));
        }
        return suffix;
    }

    // Linha de saldo do plano no card de status (igual ao site: mostra quantas
    // horas faltam no plano, ou se é ilimitado).
    private void renderPlanHours() {
        if (planHoursText == null) return;
        SpaceConnectApiClient.MachineListItem machine = effectiveMachine();
        String text = "";
        if (machine != null && machine.entitlement != null && machine.entitlement.active) {
            SpaceConnectApiClient.Entitlement ent = machine.entitlement;
            String planName = entitlementPlanName(ent);
            if (ent.unlimited) {
                text = getString(R.string.launcher_plan_unlimited, planName);
            } else {
                text = getString(R.string.launcher_plan_hours, planName, formatHours(ent.hoursRemaining));
                if (ent.bonusHours > 0) {
                    text += getString(R.string.launcher_plan_hours_bonus, formatHours(ent.bonusHours));
                }
            }
        }

        if (upcomingEntitlement != null) {
            String upcoming = getString(
                    R.string.launcher_upcoming_plan,
                    entitlementPlanName(upcomingEntitlement),
                    formatActivationDate(upcomingEntitlement.availableAt));
            text = text.isEmpty() ? upcoming : text + "\n" + upcoming;
        }

        if (text.isEmpty()) {
            planHoursText.setVisibility(View.GONE);
            return;
        }
        planHoursText.setText(text);
        planHoursText.setVisibility(View.VISIBLE);
    }

    private SpaceConnectApiClient.Entitlement firstUpcomingEntitlement() {
        if (upcomingEntitlements == null) return null;
        for (SpaceConnectApiClient.Entitlement entitlement : upcomingEntitlements) {
            if (entitlement != null
                    && !entitlement.active
                    && entitlement.availableAt != null
                    && !entitlement.availableAt.trim().isEmpty()) {
                return entitlement;
            }
        }
        return null;
    }

    private SpaceConnectApiClient.MachineListItem machineForId(String machineId) {
        if (machineId == null || machines == null) return null;
        for (SpaceConnectApiClient.MachineListItem machine : machines) {
            if (machine != null && machineId.equals(machine.id)) return machine;
        }
        return null;
    }

    private SpaceConnectApiClient.Entitlement pendingActivationEntitlement() {
        SpaceConnectApiClient.MachineListItem selected = machineForId(selectedMachineId);
        if (selected != null && isFutureEntitlement(selected.entitlement)) {
            return selected.entitlement;
        }
        return upcomingEntitlement;
    }

    private boolean isActivationPending() {
        SpaceConnectApiClient.MachineListItem selected = machineForId(selectedMachineId);
        return (selected != null && isFutureEntitlement(selected.entitlement))
                || (planLocked && upcomingEntitlement != null);
    }

    private boolean isFutureEntitlement(SpaceConnectApiClient.Entitlement entitlement) {
        if (entitlement == null || entitlement.active || upcomingEntitlements == null) return false;
        for (SpaceConnectApiClient.Entitlement upcoming : upcomingEntitlements) {
            if (upcoming == null) continue;
            if (entitlement.id != null && entitlement.id.equals(upcoming.id)) return true;
            if (entitlement.id == null
                    && upcoming.id == null
                    && sameValue(entitlement.planSlug, upcoming.planSlug)
                    && sameValue(entitlement.availableAt, upcoming.availableAt)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameValue(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private String entitlementPlanName(SpaceConnectApiClient.Entitlement entitlement) {
        if (entitlement == null) return getString(R.string.launcher_plan_fallback);
        return entitlement.planName != null && !entitlement.planName.trim().isEmpty()
                ? entitlement.planName.trim()
                : prettifyPlanSlug(entitlement.planSlug);
    }

    private static Date parseIsoDate(String iso) {
        if (iso == null || iso.trim().isEmpty()) return null;
        String[] patterns = {
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ss'Z'"
        };
        for (String pattern : patterns) {
            try {
                SimpleDateFormat parser = new SimpleDateFormat(pattern, Locale.US);
                parser.setTimeZone(TimeZone.getTimeZone("UTC"));
                return parser.parse(iso);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static String formatActivationDate(String iso) {
        Date date = parseIsoDate(iso);
        if (date == null) return "--/--";
        SimpleDateFormat formatter = new SimpleDateFormat("dd/MM", Locale.getDefault());
        formatter.setTimeZone(TimeZone.getTimeZone("UTC"));
        return formatter.format(date);
    }

    private void joinQueueForMachine(String machineId) {
        if (machineId == null || machineId.trim().isEmpty()) {
            Toast.makeText(this, R.string.launcher_select_machine_required, Toast.LENGTH_LONG).show();
            return;
        }
        SpaceConnectApiClient.MachineListItem machine = machineForId(machineId);
        if (machine != null && isFutureEntitlement(machine.entitlement)) {
            Toast.makeText(
                    this,
                    getString(
                            R.string.launcher_scheduled_details,
                            entitlementPlanName(machine.entitlement),
                            formatActivationDate(machine.entitlement.availableAt)),
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (requestRunning) return;
        requestRunning = true;
        VmMonitorService.observe(this, machineId, true);
        SpaceConnectApiClient.MachineAction action = new SpaceConnectApiClient.MachineAction("power_on");
        AccountManager.machineAction(this, machineId, action, new AccountManager.ResultCallback<SpaceConnectApiClient.OperationResponse>() {
            @Override public void onSuccess(SpaceConnectApiClient.OperationResponse result) {
                requestRunning = false;
                if (resumed) refreshStatus(true);
            }
            @Override public void onError(String message) {
                requestRunning = false;
                if (resumed) {
                    Toast.makeText(LauncherActivity.this, message, Toast.LENGTH_LONG).show();
                    refreshStatus(false);
                }
            }
        });
    }

    private void leaveQueue() {
        if (selectedMachineId == null) {
            Toast.makeText(this, R.string.launcher_select_machine_required, Toast.LENGTH_LONG).show();
            return;
        }
        runStatusAction(callback -> AccountManager.leaveQueue(this, selectedMachineId, callback));
    }

    private void runStatusAction(StatusAction action) {
        if (requestRunning) return;
        requestRunning = true;
        progressBar.setVisibility(View.VISIBLE);
        action.run(new AccountManager.ResultCallback<SpaceConnectApiClient.StatusResponse>() {
            @Override
            public void onSuccess(SpaceConnectApiClient.StatusResponse result) {
                requestRunning = false;
                progressBar.setVisibility(View.GONE);
                render(result);
                schedulePoll();
            }

            @Override
            public void onError(String message) {
                requestRunning = false;
                progressBar.setVisibility(View.GONE);
                Toast.makeText(LauncherActivity.this, message, Toast.LENGTH_LONG).show();
                schedulePoll();
            }
        });
    }

    private void connect() {
        String machineId = lastStatus != null && lastStatus.session != null
                && lastStatus.session.machine != null ? lastStatus.session.machine.id : selectedMachineId;
        startSession(machineId);
    }

    private void startSession(String machineId) {
        if (launchingSession || !resumed) return;
        if (machineId == null || machineId.trim().isEmpty()) {
            Toast.makeText(this, R.string.launcher_select_machine_required, Toast.LENGTH_LONG).show();
            return;
        }
        launchingSession = true;
        selectedMachineId = machineId;
        VmMonitorService.observe(this, machineId, false);
        startActivity(SessionLoadingActivity.intent(this, machineId, accountNameFor(machineId), false));
        overridePendingTransition(0, 0);
    }

    private void endSession() {
        if (requestRunning) return;
        String machineId = lastStatus != null && lastStatus.session != null
                && lastStatus.session.machine != null ? lastStatus.session.machine.id : selectedMachineId;
        if (machineId == null || machineId.trim().isEmpty()) {
            Toast.makeText(this, R.string.launcher_select_machine_required, Toast.LENGTH_LONG).show();
            return;
        }
        requestRunning = true;
        progressBar.setVisibility(View.VISIBLE);
        AccountManager.endSession(this, machineId,
                new AccountManager.ResultCallback<SpaceConnectApiClient.EndSessionResponse>() {
            @Override
            public void onSuccess(SpaceConnectApiClient.EndSessionResponse result) {
                requestRunning = false;
                progressBar.setVisibility(View.GONE);
                refreshStatus(false);
            }

            @Override
            public void onError(String message) {
                requestRunning = false;
                progressBar.setVisibility(View.GONE);
                Toast.makeText(LauncherActivity.this, message, Toast.LENGTH_LONG).show();
            }
                });
    }

    private void schedulePoll() {
        handler.removeCallbacks(pollStatus);
        if (resumed && !isFinishing() && !isDestroyed()) {
            handler.postDelayed(pollStatus, STATUS_POLL_MS);
        }
    }

    private void pickFileForUpload() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(
                    Intent.createChooser(intent, getString(R.string.launcher_upload_pick_title)),
                    PICK_FILE_REQUEST);
        } catch (Exception e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void uploadPickedFile(Uri uri) {
        if (requestRunning) return;
        String fileName = "arquivo";
        long size = -1;
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIdx >= 0) fileName = cursor.getString(nameIdx);
                int sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx);
            }
        }

        InputStream input;
        try {
            input = getContentResolver().openInputStream(uri);
        } catch (Exception e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        if (input == null) {
            Toast.makeText(this, R.string.launcher_status_error, Toast.LENGTH_LONG).show();
            return;
        }

        String machineId = lastStatus != null && lastStatus.session != null
                && lastStatus.session.machine != null ? lastStatus.session.machine.id : selectedMachineId;
        if (machineId == null || machineId.trim().isEmpty()) {
            try {
                input.close();
            } catch (Exception ignored) {
            }
            Toast.makeText(this, R.string.launcher_no_machine_details, Toast.LENGTH_LONG).show();
            return;
        }

        requestRunning = true;
        progressBar.setVisibility(View.VISIBLE);
        Toast.makeText(this, R.string.launcher_upload_in_progress, Toast.LENGTH_LONG).show();
        AccountManager.uploadFileToVm(this, machineId, fileName, input, size,
                new AccountManager.ResultCallback<SpaceConnectApiClient.UploadResponse>() {
                    @Override
                    public void onSuccess(SpaceConnectApiClient.UploadResponse result) {
                        requestRunning = false;
                        progressBar.setVisibility(View.GONE);
                        String msg = result != null && result.message != null && !result.message.isEmpty()
                                ? result.message
                                : getString(R.string.launcher_upload_success_fallback);
                        Toast.makeText(LauncherActivity.this, msg, Toast.LENGTH_LONG).show();
                    }

                    @Override
                    public void onError(String message) {
                        requestRunning = false;
                        progressBar.setVisibility(View.GONE);
                        Toast.makeText(LauncherActivity.this, message, Toast.LENGTH_LONG).show();
                    }
                });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_FILE_REQUEST && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                uploadPickedFile(uri);
            }
            return;
        }
        requestRunning = false;
        progressBar.setVisibility(View.GONE);
        refreshStatus(false);
    }

    private interface StatusAction {
        void run(AccountManager.ResultCallback<SpaceConnectApiClient.StatusResponse> callback);
    }
}
