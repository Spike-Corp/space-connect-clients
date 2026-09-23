package com.limelight.account;

import com.limelight.PcView;
import com.limelight.R;
import com.limelight.preferences.AddComputerManually;
import com.limelight.preferences.StreamSettings;
import com.limelight.utils.HelpLauncher;
import com.limelight.utils.UiHelper;

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

public class LauncherActivity extends Activity {
    private static final int ADD_COMPUTER_REQUEST = 4101;
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
    private String pendingHost;
    private Boolean hasMachine;
    private SpaceConnectApiClient.MachineListItem[] machines;
    private String selectedMachineId;
    private SpaceConnectApiClient.StatusResponse lastStatus;
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
        // USB passthrough: o helper é Windows-only (roda no PC do cliente). No
        // Android o botão vira um how-to + link de download, visível com a VM pronta.
        findViewById(R.id.launcherUsbButton).setOnClickListener(v -> showUsbPassthroughInfo());
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
        refreshStatus(true);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(pollStatus);
        super.onPause();
    }

    private void refreshStatus(boolean showProgress) {
        if (requestRunning) return;
        requestRunning = true;
        if (showProgress) progressBar.setVisibility(View.VISIBLE);
        AccountManager.getStatus(this, selectedMachineId, new AccountManager.ResultCallback<SpaceConnectApiClient.StatusResponse>() {
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
                statusText.setText(R.string.launcher_status_error);
                detailsText.setText(message);
                schedulePoll();
            }
        });
    }

    private void render(SpaceConnectApiClient.StatusResponse status) {
        lastStatus = status;
        maybeNotifySession(status);
        renderPlanHours();
        endSessionButton.setVisibility(View.GONE);
        primaryButton.setVisibility(View.VISIBLE);
        primaryButton.setEnabled(true);

        if ("queued".equals(status.state) && status.queue != null) {
            statusText.setText(R.string.launcher_status_queued);
            detailsText.setText(getString(
                    R.string.launcher_queue_position,
                    status.queue.position,
                    status.queue.total));
            primaryButton.setText(R.string.launcher_leave_queue);
            primaryButton.setOnClickListener(v -> leaveQueue());
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
                primaryButton.setText(R.string.launcher_connect);
                primaryButton.setOnClickListener(v -> connect());
                endSessionButton.setVisibility(View.VISIBLE);
            } else if ("ending".equals(status.state)) {
                statusText.setText(R.string.launcher_status_ending);
                primaryButton.setVisibility(View.GONE);
            } else {
                statusText.setText(R.string.launcher_status_starting);
                // Mostra a fase real do boot (criação/proteção/rede) em vez de só "starting".
                String phase = status.session.machine != null ? status.session.machine.creationPhase : null;
                if (phase != null && !phase.isEmpty()) {
                    detailsText.setText(creationPhaseLabel(phase));
                }
                primaryButton.setText(R.string.launcher_wait);
                primaryButton.setEnabled(false);
            }
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
            if (prefs.getBoolean("checkbox_notify_ready", true)) {
                String text = getString(R.string.notify_machine_ready);
                if (canPostNotifications()) postSessionNotification(NOTIF_ID_READY, text);
                else Toast.makeText(this, text, Toast.LENGTH_LONG).show();
            }
            if (prefs.getBoolean("checkbox_sound_ready", true)) {
                playNotifySound();
            }
        }
        if (nowReady && status.session != null) {
            long mins = Math.max(0, status.session.remainingMs / 60000L);
            if (!endWarned && mins > 0 && mins <= 5) {
                endWarned = true;
                if (prefs.getBoolean("checkbox_notify_ending", true)) {
                    String text = getString(R.string.notify_machine_ending);
                    if (canPostNotifications()) postSessionNotification(NOTIF_ID_ENDING, text);
                    else Toast.makeText(this, text, Toast.LENGTH_LONG).show();
                }
                if (prefs.getBoolean("checkbox_sound_ending", true)) {
                    playNotifySound();
                }
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

    // Descobre se o usuário já tem alguma VM dedicada provisionada. Sem isso o
    // app só sabe oferecer "entrar na fila", que falha silenciosamente (loop)
    // pra quem nunca teve VM criada — precisa oferecer "Criar minha VM" antes.
    private void checkMachines() {
        AccountManager.getMachines(this, new AccountManager.ResultCallback<SpaceConnectApiClient.MachinesResponse>() {
            @Override
            public void onSuccess(SpaceConnectApiClient.MachinesResponse result) {
                machines = result.machines;
                hasMachine = machines != null && machines.length > 0;
                if (machines != null && machines.length == 1) {
                    selectedMachineId = machines[0].id;
                } else if (!containsMachine(selectedMachineId)) {
                    selectedMachineId = null;
                }
                renderPlanHours();
                render(lastStatus);
            }

            @Override
            public void onError(String message) {
                // Se a checagem falhar, não bloqueia o fluxo normal de fila.
            }
        });
    }

    private void promptCreateMachine() {
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
                        createMachine(passwordField.getText().toString()))
                .show();
    }

    private void createMachine(String password) {
        if (requestRunning) return;
        requestRunning = true;
        progressBar.setVisibility(View.VISIBLE);
        AccountManager.createMachine(this, password, new AccountManager.ResultCallback<SpaceConnectApiClient.CreateMachineResponse>() {
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

    // Sufixo de saldo pra uma máquina: " · 87h" / " · ilimitado" / "" (sem plano ativo).
    private String planHoursSuffix(SpaceConnectApiClient.MachineListItem machine) {
        if (machine == null || machine.entitlement == null || !machine.entitlement.active) return "";
        SpaceConnectApiClient.Entitlement ent = machine.entitlement;
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
        if (machine == null || machine.entitlement == null || !machine.entitlement.active) {
            planHoursText.setVisibility(View.GONE);
            return;
        }
        SpaceConnectApiClient.Entitlement ent = machine.entitlement;
        // Nome de exibição vem do cadastro do produto no banco; o slug maquiado
        // é só fallback pra contas antigas sem produto vinculado.
        String planName = ent.planName != null && !ent.planName.trim().isEmpty()
                ? ent.planName.trim()
                : prettifyPlanSlug(ent.planSlug);
        String text;
        if (ent.unlimited) {
            text = getString(R.string.launcher_plan_unlimited, planName);
        } else {
            text = getString(R.string.launcher_plan_hours, planName, formatHours(ent.hoursRemaining));
            if (ent.bonusHours > 0) {
                text += getString(R.string.launcher_plan_hours_bonus, formatHours(ent.bonusHours));
            }
        }
        planHoursText.setText(text);
        planHoursText.setVisibility(View.VISIBLE);
    }

    private void joinQueueForMachine(String machineId) {
        if (machineId == null || machineId.trim().isEmpty()) {
            Toast.makeText(this, R.string.launcher_select_machine_required, Toast.LENGTH_LONG).show();
            return;
        }
        runStatusAction(callback -> AccountManager.joinQueue(this, machineId, callback));
    }

    private void leaveQueue() {
        runStatusAction(callback -> AccountManager.leaveQueue(this, callback));
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
        if (requestRunning) return;
        // Conexão PRÓPRIA: limpa qualquer pendência de pareamento em VM de amigo.
        getSharedPreferences("space_connect_launcher", MODE_PRIVATE)
                .edit().remove("pending_friend_machine").apply();
        String machineId = lastStatus != null && lastStatus.session != null
                && lastStatus.session.machine != null ? lastStatus.session.machine.id : selectedMachineId;
        if (machineId == null || machineId.trim().isEmpty()) {
            Toast.makeText(this, R.string.launcher_select_machine_required, Toast.LENGTH_LONG).show();
            return;
        }
        requestRunning = true;
        progressBar.setVisibility(View.VISIBLE);
        AccountManager.getConnection(this, machineId,
                new AccountManager.ResultCallback<SpaceConnectApiClient.ConnectionResponse>() {
            @Override
            public void onSuccess(SpaceConnectApiClient.ConnectionResponse connection) {
                requestRunning = false;
                progressBar.setVisibility(View.GONE);
                String address = connection.host;
                if (address == null || address.trim().isEmpty()) {
                    address = connection.ipv6;
                }
                if (address == null || address.trim().isEmpty() || connection.port <= 0) {
                    Toast.makeText(
                            LauncherActivity.this,
                            R.string.launcher_connection_invalid,
                            Toast.LENGTH_LONG).show();
                    return;
                }
                address = address.trim();
                if (address.indexOf(':') >= 0 && !address.startsWith("[")) {
                    address = "[" + address + "]";
                }
                pendingHost = address + ":" + connection.port;
                // Cache the plan-based bitrate ceiling from the backend so StreamSettings can
                // raise/lower the bitrate slider max to match this machine's provider (proxmox
                // physical = up to 100 Mbps, cloud = 25 Mbps) without needing an app update.
                if (connection.maxBitrateKbps > 0) {
                    android.preference.PreferenceManager.getDefaultSharedPreferences(LauncherActivity.this)
                            .edit()
                            .putInt("launcher_max_bitrate_kbps", connection.maxBitrateKbps)
                            .putInt("launcher_recommended_bitrate_kbps", connection.recommendedBitrateKbps)
                            .apply();
                }
                SharedPreferences prefs = getSharedPreferences(
                        "space_connect_launcher",
                        MODE_PRIVATE);
                if (pendingHost.equals(prefs.getString("last_host", null))) {
                    startActivity(new Intent(LauncherActivity.this, PcView.class));
                    return;
                }
                Intent addComputer = new Intent(LauncherActivity.this, AddComputerManually.class);
                addComputer.putExtra(AddComputerManually.EXTRA_AUTO_HOST, pendingHost);
                startActivityForResult(addComputer, ADD_COMPUTER_REQUEST);
            }

            @Override
            public void onError(String message) {
                requestRunning = false;
                progressBar.setVisibility(View.GONE);
                Toast.makeText(LauncherActivity.this, message, Toast.LENGTH_LONG).show();
            }
                });
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
        handler.postDelayed(pollStatus, STATUS_POLL_MS);
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

        requestRunning = true;
        progressBar.setVisibility(View.VISIBLE);
        Toast.makeText(this, R.string.launcher_upload_in_progress, Toast.LENGTH_LONG).show();
        AccountManager.uploadFileToVm(this, fileName, input, size,
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
        if (requestCode == ADD_COMPUTER_REQUEST && resultCode == RESULT_OK) {
            if (pendingHost != null) {
                getSharedPreferences("space_connect_launcher", MODE_PRIVATE)
                        .edit()
                        .putString("last_host", pendingHost)
                        .apply();
            }
            startActivity(new Intent(this, PcView.class));
            return;
        }
        if (requestCode == PICK_FILE_REQUEST && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                uploadPickedFile(uri);
            }
            return;
        }
        // Voltou do pareamento/adição sem confirmar (ou a Activity morreu): destrava
        // o requestRunning e retoma o poll — sem isso o launcher congelava num estado
        // "saiu da fila" e o botão "Atualizar status" parava de responder.
        requestRunning = false;
        progressBar.setVisibility(View.GONE);
        refreshStatus(false);
    }

    private interface StatusAction {
        void run(AccountManager.ResultCallback<SpaceConnectApiClient.StatusResponse> callback);
    }
}
