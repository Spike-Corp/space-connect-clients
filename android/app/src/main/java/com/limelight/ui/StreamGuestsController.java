package com.limelight.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.account.AccountManager;
import com.limelight.account.SpaceConnectApiClient;
import com.limelight.account.StreamClientIdentity;
import com.limelight.utils.RequestScope;
import com.limelight.utils.UiHelper;
import java.util.LinkedHashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

public final class StreamGuestsController {
    private enum Work { IDENTITY, LIST, DISCONNECT }
    private interface Operation<T> { T run(AccountManager.StreamRequests requests) throws Exception; }

    private final Activity activity;
    private final AccountManager.StreamRequests session;
    private final StreamClientIdentity identity;
    private final ExecutorService worker;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final boolean owner;
    private AlertDialog panel, confirmation;
    private LinearLayout content;
    private RequestScope scope;
    private Future<?> request;
    private Work work;
    private int generation;
    private boolean started, disposed;
    private String bindingId, error, notice;
    private SpaceConnectApiClient.StreamGuestsResponse data;
    private Consumer<Boolean> visibilityListener;

    public StreamGuestsController(Activity activity, AccountManager.StreamRequests requests, StreamClientIdentity identity) {
        this(activity, requests, identity, Executors.newSingleThreadExecutor());
    }

    StreamGuestsController(Activity activity, AccountManager.StreamRequests requests,
            StreamClientIdentity identity, ExecutorService worker) {
        this.activity = activity;
        this.session = requests;
        this.identity = identity;
        this.worker = worker;
        owner = requests.isOwnerSession();
    }

    public void start() {
        if (started || disposed) return;
        started = true;
        identify();
    }

    public boolean isOwnerSession() { return owner; }

    public void setVisibilityListener(Consumer<Boolean> listener) {
        visibilityListener = listener;
    }

    private void identify() {
        submit(Work.IDENTITY, requests -> requests.registerStream(identity), result -> {
            bindingId = result.bindingId;
            if (owner && !Boolean.TRUE.equals(result.canManageGuests)) {
                error = activity.getString(R.string.stream_guests_owner_only);
            } else if (isShowing()) {
                refresh();
            }
        });
    }

    public void showPanel() {
        if (disposed || !owner || activity.isFinishing() || activity.isDestroyed() || isShowing()) return;
        if (panel != null) releasePanel(panel);
        content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(8), dp(20), dp(8));
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);
        panel = new AlertDialog.Builder(activity)
                .setTitle(R.string.stream_guests_title)
                .setView(scroll)
                .setNegativeButton(R.string.stream_guests_close, null)
                .setNeutralButton(R.string.stream_guests_refresh, null)
                .create();
        AlertDialog shown = panel;
        shown.setOnDismissListener(dialog -> releasePanel(shown));
        if (visibilityListener != null) visibilityListener.accept(true);
        shown.show();
        shown.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
            if (bindingId == null) identify();
            else refresh();
        });
        for (int button : new int[] {AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            shown.getButton(button).setTypeface(UiHelper.getBodyTypeface(activity));
        }
        render();
        if (!started) start();
        else if (work == null) {
            if (bindingId == null) identify();
            else refresh();
        }
    }

    private void refresh() {
        if (bindingId == null || !owner) return;
        String binding = bindingId;
        submit(Work.LIST, requests -> requests.streamGuests(binding), result -> data = result);
    }

    private <T> void submit(Work kind, Operation<T> operation, Consumer<T> complete) {
        if (disposed || work != null || !checkAccount()) return;
        work = kind;
        error = null;
        int attempt = ++generation;
        scope = new RequestScope();
        AccountManager.StreamRequests requests = session.withScope(scope);
        render();
        request = worker.submit(() -> {
            try (AccountManager.StreamRequests ownedRequests = requests) {
                T result = operation.run(ownedRequests);
                main.post(() -> finish(attempt, () -> complete.accept(result)));
            } catch (Exception failure) {
                main.post(() -> finish(attempt, () -> {
                    data = null;
                    notice = null;
                    error = errorMessage(failure);
                    LimeLog.warning("Stream guests " + kind + ": " + failure.getClass().getSimpleName());
                    if (!isShowing()) Toast.makeText(activity, error, Toast.LENGTH_LONG).show();
                }));
            }
        });
    }

    private void finish(int attempt, Runnable action) {
        if (disposed || generation != attempt) return;
        work = null;
        scope = null;
        request = null;
        if (!checkAccount()) return;
        action.run();
        render();
    }

    private boolean checkAccount() {
        if (session.isCurrentAccount()) return true;
        bindingId = null;
        data = null;
        notice = null;
        error = activity.getString(R.string.stream_account_changed);
        closeConfirmation();
        render();
        return false;
    }

    private void confirm(SpaceConnectApiClient.StreamGuest guest) {
        if (disposed || work != null || !isShowing() || !checkAccount()) return;
        closeConfirmation();
        confirmation = new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.stream_guests_confirm_title, guest.displayName))
                .setMessage(R.string.stream_guests_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.stream_guests_disconnect, (dialog, which) -> disconnect(guest.userId))
                .create();
        confirmation.show();
    }

    private void disconnect(String guestUserId) {
        if (!isShowing() || bindingId == null) return;
        String binding = bindingId;
        data = null;
        notice = null;
        submit(Work.DISCONNECT, requests -> requests.disconnectStreamGuest(binding, guestUserId), result -> {
            notice = activity.getString(result.requested > 0
                    ? R.string.stream_guests_requested : R.string.stream_guests_already_gone);
            if (isShowing()) refresh();
            else Toast.makeText(activity, notice, Toast.LENGTH_LONG).show();
        });
    }

    private void render() {
        if (!isShowing() || content == null) return;
        content.removeAllViews();
        panel.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(work == null && session.isCurrentAccount());
        if (work != null) {
            ProgressBar progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleSmall);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(32), dp(32));
            params.gravity = Gravity.CENTER_HORIZONTAL;
            content.addView(progress, params);
            text(activity.getString(R.string.stream_guests_loading));
        } else if (error != null) {
            text(error);
        } else if (data != null) {
            if (notice != null) text(notice);
            if (data.guests.length == 0) text(activity.getString(R.string.stream_guests_empty));
            for (SpaceConnectApiClient.StreamGuest guest : data.guests) {
                LinkedHashSet<String> platforms = new LinkedHashSet<>();
                for (SpaceConnectApiClient.StreamGuestDevice device : guest.devices) {
                    switch (device.platform) {
                        case "windows": platforms.add("Windows"); break;
                        case "android": platforms.add("Android"); break;
                        case "linux": platforms.add("Linux"); break;
                        case "macos": platforms.add("macOS"); break;
                        default: platforms.add(activity.getString(R.string.stream_guests_device)); break;
                    }
                }
                String count = activity.getResources().getQuantityString(
                        R.plurals.stream_guest_devices, guest.devices.length, guest.devices.length);
                Button button = new Button(activity);
                button.setAllCaps(false);
                button.setText(activity.getString(R.string.stream_guest_summary,
                        guest.displayName, count, TextUtils.join(", ", platforms)));
                button.setTypeface(UiHelper.getBodyTypeface(activity));
                button.setMinHeight(dp(48));
                button.setBackgroundResource(R.drawable.bg_button_ghost);
                button.setContentDescription(activity.getString(R.string.stream_guests_confirm_title, guest.displayName)
                        + " " + count);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
                params.topMargin = dp(8);
                content.addView(button, params);
                button.setOnClickListener(view -> confirm(guest));
            }
            if (data.unidentifiedCount > 0) {
                text(activity.getResources().getQuantityString(
                        R.plurals.stream_guests_unidentified, data.unidentifiedCount, data.unidentifiedCount));
            }
        }
    }

    private void text(String value) {
        TextView text = new TextView(activity);
        text.setText(value);
        text.setTextSize(14);
        text.setTypeface(UiHelper.getBodyTypeface(activity));
        text.setPadding(0, dp(10), 0, dp(10));
        content.addView(text, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private String errorMessage(Exception failure) {
        int message = R.string.stream_guests_error;
        SpaceConnectApiClient.ApiException api = failure instanceof SpaceConnectApiClient.ApiException
                ? (SpaceConnectApiClient.ApiException) failure : null;
        if (api != null) {
            switch (api.code) {
                case "ACCOUNT_CHANGED": message = R.string.stream_account_changed; break;
                case "STREAM_GUESTS_UNSUPPORTED":
                case "STREAM_HOST_AUTH_UNAVAILABLE": message = R.string.stream_guests_unsupported; break;
                case "STREAM_OWNER_REQUIRED":
                case "STREAM_OWNER_PROTECTED":
                case "STREAM_ACCESS_DENIED": message = R.string.stream_guests_owner_only; break;
                case "STREAM_HOST_CHANGED":
                case "STREAM_BINDING_INVALID": message = R.string.stream_guests_reconnect; break;
                case "STREAM_GUEST_NOT_CONNECTED": message = R.string.stream_guests_already_gone; break;
                case "ACCESS_REVOKED": message = R.string.stream_guests_sign_in; break;
                default: break;
            }
        }
        String result = activity.getString(message);
        if (api != null && api.requested != null && api.alreadyDisconnected != null
                && api.failed != null && api.notAttempted != null) {
            result += "\n\n" + activity.getString(R.string.stream_guests_partial,
                    api.requested, api.alreadyDisconnected, api.failed, api.notAttempted);
        }
        return result;
    }

    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }

    private void closeConfirmation() {
        if (confirmation != null) {
            confirmation.dismiss();
            confirmation = null;
        }
    }

    public void closePanel() {
        closeConfirmation();
        if (panel != null) {
            AlertDialog shown = panel;
            releasePanel(shown);
            shown.dismiss();
        }
    }

    private void releasePanel(AlertDialog shown) {
        if (panel != shown) return;
        panel = null;
        content = null;
        data = null;
        closeConfirmation();
        if (work == Work.LIST) cancelRequest();
        if (visibilityListener != null) visibilityListener.accept(false);
    }

    public boolean isShowing() { return panel != null && panel.isShowing(); }

    private void cancelRequest() {
        generation++;
        if (scope != null) scope.close();
        if (request != null) request.cancel(true);
        scope = null;
        request = null;
        work = null;
    }

    public void dispose() {
        if (disposed) return;
        disposed = true;
        closePanel();
        cancelRequest();
        session.close();
        main.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        bindingId = null;
        data = null;
        visibilityListener = null;
    }
}
