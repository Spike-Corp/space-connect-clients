package com.limelight.account;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.net.Uri;

import com.limelight.utils.RequestScope;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;

public class SessionDeadlineReceiver extends BroadcastReceiver {
    private static final long WARNING_WINDOW_MS = 5 * 60_000L;

    static void schedule(Context context, SpaceConnectApiClient.MachineListItem machine, String serverNow) {
        if (machine.session == null || machine.session.endAt == null) return;
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        try {
            Date end = format.parse(machine.session.endAt);
            if (end == null) return;
            Date server = serverNow == null ? new Date() : format.parse(serverNow);
            if (server == null) return;
            scheduleRemaining(context, machine.id, machine.session.id, machine.name, end.getTime() - server.getTime());
        } catch (ParseException error) {
            com.limelight.LimeLog.warning("Session deadline timestamp invalid");
        }
    }

    static void scheduleRemaining(Context context, String machineId, String sessionId, String name, long remainingMs) {
        if (remainingMs <= 0 || machineId == null || sessionId == null
                || VmMonitorService.hasSessionNotice(context, false, sessionId)) return;
        long at = System.currentTimeMillis() + Math.max(1000, remainingMs - WARNING_WINDOW_MS);
        Intent intent = new Intent(context, SessionDeadlineReceiver.class)
                .setData(Uri.parse("spaceconnect://session-deadline/" + machineId))
                .putExtra("machineId", machineId).putExtra("sessionId", sessionId).putExtra("name", name);
        PendingIntent alarm = PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        context.getSystemService(AlarmManager.class).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm);
    }

    @Override public void onReceive(Context context, Intent intent) {
        String machineId = intent.getStringExtra("machineId");
        String sessionId = intent.getStringExtra("sessionId");
        if (machineId == null || sessionId == null) return;
        PendingResult pending = goAsync();
        RequestScope scope = new RequestScope();
        AtomicBoolean finished = new AtomicBoolean();
        Handler handler = new Handler(Looper.getMainLooper());
        Runnable finish = () -> {
            if (finished.compareAndSet(false, true)) { scope.close(); pending.finish(); }
        };
        handler.postDelayed(finish, 8000);
        AccountManager.getStatus(context, machineId, scope, new AccountManager.ResultCallback<SpaceConnectApiClient.StatusResponse>() {
            public void onSuccess(SpaceConnectApiClient.StatusResponse response) {
                if (response.session != null && sessionId.equals(response.session.id)) {
                    if (response.session.remainingMs > WARNING_WINDOW_MS) {
                        scheduleRemaining(context, machineId, sessionId, intent.getStringExtra("name"), response.session.remainingMs);
                    } else if (response.session.remainingMs > 0) {
                        VmMonitorService.sessionNotice(context, false, sessionId, intent.getStringExtra("name"));
                    }
                }
                handler.removeCallbacks(finish);
                finish.run();
            }
            public void onError(String message) {
                com.limelight.LimeLog.warning("Session deadline could not be verified");
                handler.removeCallbacks(finish);
                finish.run();
            }
        });
    }
}
