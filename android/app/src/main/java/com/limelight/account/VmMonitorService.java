package com.limelight.account;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

import com.limelight.R;
import com.limelight.utils.RequestScope;

import java.util.HashSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;

import static com.limelight.account.SpaceConnectApiClient.*;

/** Bounded, user-started monitoring. The durable work belongs to the backend. */
public class VmMonitorService extends Service {
    private static final String CHANNEL = "vm_operations";
    private static final int FOREGROUND_ID = 46000;
    private static final long MAX_MONITOR_MS = 30 * 60_000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, BootWatch> waitingForBoot = new LinkedHashMap<>();
    private RequestScope scope;
    private Future<?> request;
    private long started, idleSince;
    private boolean loading, destroyed;
    private boolean observing;
    private long accountGeneration;
    private String account, startedIso;
    private final Runnable poll = this::refresh;

    private static final class BootWatch {
        final String machineId;
        RequestScope scope;
        Future<?> request;
        BootWatch(String machineId) { this.machineId = machineId; }
        void cancel() {
            if (scope != null) scope.close();
            if (request != null) request.cancel(true);
        }
    }

    public static boolean observe(Context context, String machineId, boolean boot) {
        return observe(context, machineId, boot, AccountManager.currentAccountGeneration());
    }

    public static boolean observe(Context context, String machineId, boolean boot, long generation) {
        if (generation != AccountManager.currentAccountGeneration()) {
            Toast.makeText(context, R.string.stream_account_changed, Toast.LENGTH_LONG).show();
            return false;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = Build.VERSION.SDK_INT >= 26 ? manager.getNotificationChannel(CHANNEL) : null;
        if (boot && (!manager.areNotificationsEnabled()
                || (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED)
                || (Build.VERSION.SDK_INT >= 26 && channel != null
                        && channel.getImportance() == NotificationManager.IMPORTANCE_NONE)
                || !android.preference.PreferenceManager.getDefaultSharedPreferences(context)
                        .getBoolean("checkbox_notify_ready", true))) {
            Toast.makeText(context, R.string.session_background_notifications, Toast.LENGTH_LONG).show();
            return false;
        }
        Intent intent = new Intent(context, VmMonitorService.class).putExtra("machineId", machineId)
                .putExtra("boot", boot).putExtra("accountGeneration", generation);
        try {
            android.content.ComponentName service = Build.VERSION.SDK_INT >= 26
                    ? context.startForegroundService(intent) : context.startService(intent);
            if (service != null) return true;
        } catch (IllegalStateException | SecurityException error) {
            com.limelight.LimeLog.warning("VM monitor start failed: " + error.getClass().getSimpleName());
        }
        Toast.makeText(context, R.string.vm_monitor_unavailable, Toast.LENGTH_LONG).show();
        return false;
    }

    static Notification.Builder notification(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(new NotificationChannel(CHANNEL,
                    context.getString(R.string.vm_monitor_title), NotificationManager.IMPORTANCE_LOW));
        }
        Intent open = new Intent(context, MachineManagementActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(context, 46000, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return (Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(context, CHANNEL) : new Notification.Builder(context))
                .setSmallIcon(R.drawable.ic_bell).setContentTitle(context.getString(R.string.vm_monitor_title))
                .setContentIntent(content).setAutoCancel(true);
    }

    static void alert(Context context, String key, String body) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        context.getSystemService(NotificationManager.class).notify(key, 1, notification(context)
                .setContentText(body).setStyle(new Notification.BigTextStyle().bigText(body)).build());
    }

    static boolean hasSessionNotice(Context context, boolean ready, String sessionId) {
        String email = AccountManager.getLoggedInEmail(context);
        return email != null && !email.isEmpty()
                && context.getSharedPreferences("vm_session_notices_" + email.hashCode(), MODE_PRIVATE)
                    .contains((ready ? "ready:" : "ending:") + sessionId);
    }

    static void sessionNotice(Context context, boolean ready, String sessionId, String name) {
        if (sessionId == null) return;
        if (!context.getSystemService(NotificationManager.class).areNotificationsEnabled()) return;
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        String email = AccountManager.getLoggedInEmail(context);
        if (email == null || email.isEmpty()) return;
        SharedPreferences notices = context.getSharedPreferences("vm_session_notices_" + email.hashCode(), MODE_PRIVATE);
        String key = (ready ? "ready:" : "ending:") + sessionId;
        if (notices.contains(key)) return;
        SharedPreferences.Editor editor = notices.edit();
        if (notices.getAll().size() > 200) {
            java.util.List<java.util.Map.Entry<String, ?>> entries = new java.util.ArrayList<>(notices.getAll().entrySet());
            entries.sort((a, b) -> Long.compare(a.getValue() instanceof Long ? (Long) a.getValue() : 0L,
                    b.getValue() instanceof Long ? (Long) b.getValue() : 0L));
            for (int i = 0; i < entries.size() - 160; i++) editor.remove(entries.get(i).getKey());
        }
        editor.putLong(key, System.currentTimeMillis()).apply();
        SharedPreferences settings = android.preference.PreferenceManager.getDefaultSharedPreferences(context);
        if (settings.getBoolean(ready ? "checkbox_notify_ready" : "checkbox_notify_ending", true))
            alert(context, key, context.getString(ready ? R.string.vm_machine_on : R.string.vm_session_ending, name));
        if (settings.getBoolean(ready ? "checkbox_sound_ready" : "checkbox_sound_ending", true)) {
            try {
                android.media.Ringtone sound = android.media.RingtoneManager.getRingtone(context,
                        android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION));
                if (sound != null) sound.play();
            } catch (SecurityException error) {
                com.limelight.LimeLog.warning("Session notification sound permission denied");
            }
        }
    }

    private Notification ongoingNotification(String message) {
        PendingIntent stop = PendingIntent.getService(this, 46001, new Intent(this, VmMonitorService.class).setAction("STOP"),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return notification(this).setOngoing(true).setOnlyAlertOnce(true).setAutoCancel(false)
                .setContentText(message)
                .addAction(new Notification.Action.Builder(null, getString(R.string.vm_monitor_stop), stop).build()).build();
    }

    @Override public void onCreate() {
        super.onCreate();
        resetAccount();
        startForeground(FOREGROUND_ID, ongoingNotification(getString(R.string.vm_monitor_body)));
    }

    private void resetAccount() {
        handler.removeCallbacks(poll);
        if (scope != null) scope.close();
        if (request != null) request.cancel(true);
        for (BootWatch watch : waitingForBoot.values()) watch.cancel();
        waitingForBoot.clear();
        scope = null;
        request = null;
        loading = false;
        idleSince = 0;
        started = SystemClock.elapsedRealtime();
        accountGeneration = AccountManager.currentAccountGeneration();
        account = AccountManager.getLoggedInEmail(this);
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US);
        format.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        startedIso = format.format(new java.util.Date(System.currentTimeMillis() - 1000));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        long current = AccountManager.currentAccountGeneration();
        if (intent == null || intent.getLongExtra("accountGeneration", current) != current) {
            if (!observing) stopSelf();
            return START_NOT_STICKY;
        }
        if (accountGeneration != current) resetAccount();
        observing = true;
        if (intent != null && intent.getBooleanExtra("boot", false)) {
            String id = intent.getStringExtra("machineId");
            if (id != null && id.matches("^[a-fA-F0-9]{24}$")) {
                waitingForBoot.putIfAbsent(id, new BootWatch(id));
            } else {
                com.limelight.LimeLog.warning("VM monitor received an invalid machine ID");
                Toast.makeText(this, R.string.vm_monitor_unavailable, Toast.LENGTH_LONG).show();
                if (waitingForBoot.isEmpty()) stopSelf();
                return START_NOT_STICKY;
            }
        }
        idleSince = 0;
        handler.removeCallbacks(poll);
        refresh();
        return START_NOT_STICKY;
    }

    private void refresh() {
        if (destroyed || loading) return;
        if (!isCurrentAccount()) { stopSelf(); return; }
        if (SystemClock.elapsedRealtime() - started >= MAX_MONITOR_MS) {
            alert(this, "monitor_timeout", getString(R.string.vm_monitor_timeout));
            stopSelf(); return;
        }
        loading = true;
        scope = new RequestScope();
        RequestScope managementScope = scope;
        request = AccountManager.getManagement(this, scope, accountGeneration, new AccountManager.ResultCallback<ManagementResponse>() {
            public void onSuccess(ManagementResponse response) {
                if (response == null) { onError(getString(R.string.vm_refresh_error)); return; }
                if (!finishManagementRead(managementScope)) return;
                boolean pending = false;
                SharedPreferences preferences = getSharedPreferences("vm_operation_notices_" + (account == null ? 0 : account.hashCode()), MODE_PRIVATE);
                Set<String> seen = new HashSet<>(preferences.getStringSet("seen", new HashSet<>()));
                if (response.operations != null) for (VmOperation operation : response.operations) {
                    if ("running".equals(operation.state) || "queued".equals(operation.state)) pending = true;
                    else {
                        String key = operation.id + ":" + operation.state;
                        if (!seen.contains(key)) {
                            // Do not announce historical operations on the first observation.
                            if (preferences.getBoolean("initialized", false) || (operation.createdAt != null && operation.createdAt.compareTo(startedIso) >= 0))
                                alert(VmMonitorService.this, operation.id, operation.message);
                            seen.add(key);
                        }
                    }
                }
                if (seen.size() > 200) {
                    seen.clear();
                    if (response.operations != null) for (VmOperation operation : response.operations)
                        if (!"running".equals(operation.state) && !"queued".equals(operation.state))
                            seen.add(operation.id + ":" + operation.state);
                }
                preferences.edit().putStringSet("seen", seen).putBoolean("initialized", true).apply();
                if (response.machines != null) for (MachineListItem machine : response.machines) {
                    if (machine.session != null) {
                        SessionDeadlineReceiver.schedule(VmMonitorService.this, machine, response.serverNow);
                    }
                }
                refreshBoots();
                if (!pending && waitingForBoot.isEmpty()) {
                    if (idleSince == 0) idleSince = SystemClock.elapsedRealtime();
                    if (SystemClock.elapsedRealtime() - idleSince >= 30_000) { stopSelf(); return; }
                } else idleSince = 0;
                getSystemService(NotificationManager.class).notify(FOREGROUND_ID, ongoingNotification(getString(R.string.vm_monitor_body)));
                handler.postDelayed(poll, 15_000);
            }
            public void onError(String message) {
                if (!finishManagementRead(managementScope)) return;
                showRefreshError();
                handler.postDelayed(poll, 15_000);
            }
        });
    }

    private boolean isCurrentAccount() {
        return accountGeneration == AccountManager.currentAccountGeneration()
                && java.util.Objects.equals(account, AccountManager.getLoggedInEmail(this));
    }

    private boolean finishManagementRead(RequestScope completed) {
        if (destroyed || scope != completed) return false;
        completed.close();
        scope = null;
        request = null;
        loading = false;
        if (!isCurrentAccount()) { stopSelf(); return false; }
        return true;
    }

    private void showRefreshError() {
        getSystemService(NotificationManager.class).notify(FOREGROUND_ID,
                ongoingNotification(getString(R.string.vm_refresh_error)));
    }

    private void refreshBoots() {
        for (BootWatch watch : new ArrayList<>(waitingForBoot.values())) {
            if (watch.scope != null) continue;
            RequestScope statusScope = new RequestScope();
            watch.scope = statusScope;
            watch.request = AccountManager.getStatus(this, watch.machineId, statusScope, accountGeneration,
                    new AccountManager.ResultCallback<StatusResponse>() {
                        public void onSuccess(StatusResponse response) {
                            if (!finishBootRead(watch, statusScope)) return;
                            if (response == null || (response.session != null && response.session.machine != null
                                    && !watch.machineId.equals(response.session.machine.id))
                                    || (response.queue != null && !watch.machineId.equals(response.queue.machineId))) {
                                com.limelight.LimeLog.warning("VM monitor received an invalid or mismatched status");
                                showRefreshError();
                                return;
                            }
                            if (!"ready".equals(response.state)) return;
                            if (response.session == null || response.session.id == null || response.session.machine == null) {
                                com.limelight.LimeLog.warning("VM monitor received ready without a session");
                                showRefreshError();
                                return;
                            }
                            waitingForBoot.remove(watch.machineId);
                            String name = response.session.machine.name != null
                                    ? response.session.machine.name : watch.machineId;
                            sessionNotice(VmMonitorService.this, true, response.session.id, name);
                        }
                        public void onError(String message) {
                            if (finishBootRead(watch, statusScope)) showRefreshError();
                        }
                    });
        }
    }

    private boolean finishBootRead(BootWatch watch, RequestScope completed) {
        if (destroyed || waitingForBoot.get(watch.machineId) != watch || watch.scope != completed) return false;
        completed.close();
        watch.scope = null;
        watch.request = null;
        if (!isCurrentAccount()) { stopSelf(); return false; }
        return true;
    }

    @Override public void onTimeout(int startId, int fgsType) {
        alert(this, "monitor_timeout", getString(R.string.vm_monitor_timeout));
        stopSelf();
    }
    @Override public void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        if (scope != null) scope.close();
        if (request != null) request.cancel(true);
        for (BootWatch watch : waitingForBoot.values()) watch.cancel();
        waitingForBoot.clear();
        stopForeground(true);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
