package com.limelight.account;

import com.limelight.PcView;
import com.limelight.R;
import com.limelight.preferences.AddComputerManually;
import com.limelight.utils.UiHelper;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

public class LauncherActivity extends Activity {
    private static final int ADD_COMPUTER_REQUEST = 4101;
    private static final long STATUS_POLL_MS = 5000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView statusText;
    private TextView detailsText;
    private Button primaryButton;
    private Button endSessionButton;
    private ProgressBar progressBar;
    private boolean requestRunning;
    private String pendingHost;

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

        statusText = findViewById(R.id.launcherStatus);
        detailsText = findViewById(R.id.launcherDetails);
        primaryButton = findViewById(R.id.launcherPrimaryButton);
        endSessionButton = findViewById(R.id.launcherEndSessionButton);
        progressBar = findViewById(R.id.launcherProgress);

        TextView accountText = findViewById(R.id.launcherAccount);
        accountText.setText(AccountManager.getLoggedInEmail(this));

        findViewById(R.id.launcherRefreshButton).setOnClickListener(v -> refreshStatus(true));
        findViewById(R.id.launcherLogoutButton).setOnClickListener(v -> {
            AccountManager.logout(LauncherActivity.this);
            startActivity(new Intent(LauncherActivity.this, LoginActivity.class));
            finish();
        });
        endSessionButton.setOnClickListener(v -> endSession());
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
        AccountManager.getStatus(this, new AccountManager.ResultCallback<SpaceConnectApiClient.StatusResponse>() {
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
                primaryButton.setText(R.string.launcher_wait);
                primaryButton.setEnabled(false);
            }
            return;
        }

        statusText.setText(R.string.launcher_status_idle);
        detailsText.setText(R.string.launcher_idle_details);
        primaryButton.setText(R.string.launcher_join_queue);
        primaryButton.setOnClickListener(v -> joinQueue());
    }

    private void joinQueue() {
        runStatusAction(callback -> AccountManager.joinQueue(this, callback));
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
        requestRunning = true;
        progressBar.setVisibility(View.VISIBLE);
        AccountManager.getConnection(this, new AccountManager.ResultCallback<SpaceConnectApiClient.ConnectionResponse>() {
            @Override
            public void onSuccess(SpaceConnectApiClient.ConnectionResponse connection) {
                requestRunning = false;
                progressBar.setVisibility(View.GONE);
                pendingHost = connection.host + ":" + connection.port;
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
        requestRunning = true;
        progressBar.setVisibility(View.VISIBLE);
        AccountManager.endSession(this, new AccountManager.ResultCallback<SpaceConnectApiClient.EndSessionResponse>() {
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
        }
    }

    private interface StatusAction {
        void run(AccountManager.ResultCallback<SpaceConnectApiClient.StatusResponse> callback);
    }
}
