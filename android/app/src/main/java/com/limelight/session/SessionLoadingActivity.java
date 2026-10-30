package com.limelight.session;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.view.View;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.Toast;

import com.limelight.Game;
import com.limelight.R;
import com.limelight.account.AccountManager;
import com.limelight.account.LauncherActivity;
import com.limelight.account.SpaceConnectApiClient.StatusResponse;
import com.limelight.computers.ComputerManagerService;
import com.limelight.utils.RequestScope;
import com.limelight.utils.ServerHelper;
import com.limelight.utils.UiHelper;

public class SessionLoadingActivity extends Activity implements SessionOrchestrator.Listener {
    private static final String MACHINE = "session.machine";
    private static final String ACCOUNT_NAME = "session.accountName";
    private static final String FRIEND = "session.friend";
    private final Handler callbacks = new Handler(Looper.getMainLooper());
    private SessionOverlayView overlay;
    private SessionOrchestrator attempt;
    private boolean bound;
    private boolean transferred;
    private boolean closed;

    public static Intent intent(Context context, String machineId, String accountName, boolean friend) {
        return new Intent(context, SessionLoadingActivity.class)
                .putExtra(MACHINE, machineId).putExtra(ACCOUNT_NAME, accountName).putExtra(FRIEND, friend)
                .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
    }

    private final ServiceConnection service = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (closed || isFinishing() || attempt != null) return;
            callbacks.removeCallbacks(bindingTimeout);
            try {
                attempt = createAttempt((ComputerManagerService.ComputerManagerBinder) binder);
                attempt.start();
            } catch (Exception e) {
                SessionLog.failure("initialization", e);
                onFailure(SessionOrchestrator.Failure.CONNECTION);
            }
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            if (!closed && !transferred) onFailure(SessionOrchestrator.Failure.CONNECTION);
        }
    };

    private final Runnable bindingTimeout = () -> onFailure(SessionOrchestrator.Failure.CONNECTION);

    protected SessionOrchestrator createAttempt(ComputerManagerService.ComputerManagerBinder binder) {
        String machineId = getIntent().getStringExtra(MACHINE);
        boolean friend = getIntent().getBooleanExtra(FRIEND, false);
        RequestScope scope = new RequestScope();
        SessionBackend backend = new SessionBackend(this, machineId, friend, scope);
        ManagedSessionHost host = new ManagedSessionHost(this, machineId,
                getIntent().getStringExtra(ACCOUNT_NAME), friend, binder, backend.requests, scope);
        return new SessionOrchestrator(machineId, friend, backend, host, new HandlerCallbacks(callbacks), this);
    }

    private static final class HandlerCallbacks implements SessionOrchestrator.Callbacks {
        private final Handler handler;
        HandlerCallbacks(Handler handler) { this.handler = handler; }
        @Override public void post(Runnable action) { handler.post(action); }
        @Override public void clear() { handler.removeCallbacksAndMessages(null); }
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiHelper.setLocale(this);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        overlay = new SessionOverlayView(this);
        setContentView(overlay);
        overlay.showMessage(getString(R.string.session_preparing));
        overlay.setOnCancel(this::cancelAndFinish);
        // A recreated activity must never resurrect an old join/pair after cancellation.
        // Orientation/size changes are handled in-place by the manifest.
        if (savedInstanceState != null || getIntent().getStringExtra(MACHINE) == null) {
            cancelAndFinish();
            return;
        }
        callbacks.postDelayed(bindingTimeout, 10_000);
        bound = bindService(new Intent(this, ComputerManagerService.class), service, BIND_AUTO_CREATE);
        if (!bound) onFailure(SessionOrchestrator.Failure.CONNECTION);
    }

    @Override public void onProgress(SessionOrchestrator.Stage stage, StatusResponse status) {
        if (!closed && attempt != null && attempt.isActive()) {
            overlay.showMessage(SessionMessages.message(this, stage, status));
            overlay.setTips(status != null && status.bootTexts != null ? status.bootTexts.tips : null);
        }
    }

    @Override public void onPrepared(SessionOrchestrator.PreparedSession prepared) {
        if (closed || isFinishing() || attempt == null || !attempt.isActive()) return;
        String address = prepared.computer.activeAddress.toString();
        AccountManager.rememberMachineLink(address, attempt.machineId, getIntent().getStringExtra(ACCOUNT_NAME));
        if (prepared.connection.maxBitrateKbps > 0) {
            PreferenceManager.getDefaultSharedPreferences(this).edit()
                    .putInt("launcher_max_bitrate_kbps", prepared.connection.maxBitrateKbps)
                    .putInt("launcher_recommended_bitrate_kbps", prepared.connection.recommendedBitrateKbps).apply();
        }
        getSharedPreferences("space_connect_launcher", MODE_PRIVATE).edit()
                .putString("last_host", address).remove("pending_friend_machine").apply();
        Intent game = ServerHelper.createStartIntent(this, prepared.desktop, prepared.computer, prepared.uniqueId);
        game.putExtra(Game.EXTRA_SESSION_ATTEMPT, attempt.id);
        game.putExtra(Game.EXTRA_SESSION_NEWLY_PAIRED, prepared.newlyPaired);
        game.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            startActivity(game);
            transferred = true;
            attempt.detachListener();
            finish();
            overridePendingTransition(0, 0);
        } catch (RuntimeException e) {
            SessionLog.failure("handoff", e);
            onFailure(SessionOrchestrator.Failure.CONNECTION);
        }
    }

    @Override public void onFailure(SessionOrchestrator.Failure failure) {
        if (closed || transferred || isFinishing()) return;
        String detail = SessionLog.diagnostic();
        Toast.makeText(this, getString(SessionMessages.error(failure))
                + (detail.isEmpty() ? "" : "\n" + getString(R.string.vm_diagnostic, detail)), Toast.LENGTH_LONG).show();
        // CLEAR_TOP preserves the existing dashboard and all its management features.
        startActivity(new Intent(this, LauncherActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        cancelAndFinish();
    }

    private void cancelAndFinish() {
        close();
        finish();
        overridePendingTransition(0, 0);
    }

    private void close() {
        if (closed) return;
        closed = true;
        callbacks.removeCallbacksAndMessages(null);
        if (attempt != null && !transferred) attempt.cancel();
        if (overlay != null) overlay.dispose();
        if (bound) {
            unbindService(service);
            bound = false;
        }
    }

    @Override public void onBackPressed() { cancelAndFinish(); }

    @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            cancelAndFinish();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override protected void onStop() {
        super.onStop();
        if (!transferred) cancelAndFinish();
    }

    @Override protected void onDestroy() {
        close();
        super.onDestroy();
    }
}
