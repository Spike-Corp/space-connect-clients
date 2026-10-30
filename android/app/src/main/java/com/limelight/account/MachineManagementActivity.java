package com.limelight.account;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.R;
import com.limelight.utils.HelpLauncher;
import com.limelight.utils.RequestScope;
import com.limelight.utils.UiHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

import static com.limelight.account.SpaceConnectApiClient.*;

public class MachineManagementActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout list;
    private ScrollView scroll;
    private TextView status;
    private ManagementResponse data;
    private RequestScope scope;
    private Future<?> request;
    private boolean resumed, loading, submitting;
    private final Runnable poll = this::reload;

    @Override protected void onCreate(Bundle state) {
        UiHelper.applyPreferredTheme(this);
        super.onCreate(state);
        setContentView(R.layout.activity_vm_management);
        UiHelper.applyStatusBarInset(findViewById(R.id.vmManagementRoot));
        list = findViewById(R.id.vmManagementList);
        scroll = findViewById(R.id.vmManagementScroll);
        status = findViewById(R.id.vmManagementStatus);
        findViewById(R.id.vmManagementBack).setOnClickListener(v -> finish());
    }

    @Override protected void onResume() { super.onResume(); resumed = true; reload(); }
    @Override protected void onPause() {
        resumed = false;
        handler.removeCallbacks(poll);
        if (scope != null) scope.close();
        if (request != null) request.cancel(true);
        loading = false;
        super.onPause();
    }

    private void reload() {
        if (!resumed || loading) return;
        loading = true;
        scope = new RequestScope();
        request = AccountManager.getManagement(this, scope, new AccountManager.ResultCallback<ManagementResponse>() {
            public void onSuccess(ManagementResponse result) {
                loading = false;
                data = result;
                status.setText(R.string.vm_operations_continue);
                render();
                handler.postDelayed(poll, 5000);
            }
            public void onError(String message) {
                loading = false;
                status.setText(message);
                handler.postDelayed(poll, 5000);
            }
        });
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(LinearLayout parent, String value, boolean heading) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(heading ? 20 : 14);
        view.setTypeface(heading ? UiHelper.getDisplayTypeface(this) : UiHelper.getBodyTypeface(this));
        view.setPadding(0, dp(8), 0, dp(8));
        parent.addView(view);
        return view;
    }
    private void button(LinearLayout parent, String label, String key, boolean enabled, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setTag(key);
        button.setAllCaps(false);
        button.setTypeface(UiHelper.getBodyTypeface(this));
        button.setBackgroundResource(R.drawable.bg_button_ghost);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(8);
        button.setMinHeight(dp(48));
        parent.addView(button, params);
        button.setEnabled(enabled && !submitting);
        button.setOnClickListener(v -> action.run());
    }
    private LinearLayout card() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.bg_card);
        box.setPadding(dp(20), dp(12), dp(20), dp(20));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(16);
        list.addView(box, params);
        return box;
    }
    static boolean stopped(MachineListItem machine) {
        return machine != null && machine.session == null && machine.queue == null && machine.operationId == null
                && (machine.phases == null || (machine.phases.creation == null && machine.phases.shutdown == null && machine.phases.format == null))
                && (machine.storage == null || (!"creating".equals(machine.storage.snapshotStatus) && !"restoring".equals(machine.storage.snapshotStatus)))
                && ("offline".equals(machine.state) || "deleted".equals(machine.state)
                    || "hibernated".equals(machine.state) || "archived".equals(machine.state));
    }
    private void render() {
        int y = scroll.getScrollY();
        Object focus = getCurrentFocus() == null ? null : getCurrentFocus().getTag();
        list.removeAllViews();
        if (data == null || data.machines == null || data.machines.length == 0) text(list, getString(R.string.vm_empty), false);
        if (data != null && data.machines != null) for (MachineListItem machine : data.machines) {
            LinearLayout box = card();
            text(box, machine.name, true);
            boolean working = machine.operationId != null
                    || (machine.phases != null && (machine.phases.creation != null || machine.phases.shutdown != null || machine.phases.format != null))
                    || (machine.storage != null && ("creating".equals(machine.storage.snapshotStatus) || "restoring".equals(machine.storage.snapshotStatus)));
            boolean powered = machine.session != null || "idle".equals(machine.state) || "busy".equals(machine.state);
            text(box, getString(working ? R.string.vm_working : machine.queue != null ? R.string.vm_queued : powered ? R.string.vm_on : R.string.vm_off), false);
            text(box, getString(R.string.vm_windows_disk, machine.specs != null && machine.specs.diskGb != null ? machine.specs.diskGb.toString() : "—"), false);
            boolean available = !working;
            button(box, getString(R.string.vm_power_on), "on:" + machine.id, available && !machine.locked && !powered && machine.queue == null,
                    () -> submit(machine.id, new MachineAction("power_on")));
            button(box, getString(R.string.vm_restart), "restart:" + machine.id, available && machine.session != null,
                    () -> confirm(getString(R.string.vm_restart_warning, machine.name), () -> submit(machine.id, new MachineAction("restart"))));
            button(box, getString(R.string.vm_power_off), "off:" + machine.id, available && (powered || machine.queue != null),
                    () -> confirm(getString(R.string.vm_power_warning, machine.name), () -> submit(machine.id, new MachineAction("power_off"))));
            button(box, getString(R.string.vm_password), "password:" + machine.id, available, () -> password(machine));
            int formats = machine.formats == null ? 0 : machine.formats.remaining;
            text(box, getString(R.string.vm_format_remaining, formats), false);
            button(box, getString(R.string.vm_format), "format:" + machine.id, available && machine.queue == null && !machine.locked && formats > 0,
                    () -> confirm(getString(R.string.vm_format_warning, machine.name), () -> {
                        MachineAction action = new MachineAction("format_os");
                        action.eraseWindows = true;
                        submit(machine.id, action);
                    }));
            if (formats == 0) button(box, getString(R.string.vm_add_format), "credit:" + machine.id, true,
                    () -> HelpLauncher.launchUrl(this, "https://spacecloud.gg/painel/loja?formatFor=" + machine.id));
            button(box, getString(R.string.vm_add_storage), "buy:" + machine.id, !machine.locked,
                    () -> HelpLauncher.launchUrl(this, "https://spacecloud.gg/painel/loja?storageFor=" + machine.id));
            boolean hasVolume = false;
            if (data.volumes != null) for (DataVolume volume : data.volumes) if (machine.id.equals(volume.machineId)) {
                hasVolume = true;
                text(box, getString(R.string.vm_data_disk, volume.sizeGb), true);
                text(box, volume.initializationError != null ? volume.initializationError
                        : volume.initialized ? getString(R.string.vm_disk_ready, volume.driveLetter) : getString(R.string.vm_disk_pending), false);
                button(box, getString(R.string.vm_transfer), "transfer:" + volume.id,
                        "attached".equals(volume.state) && volume.operationId == null && stopped(machine), () -> transfer(volume));
            }
            if (!hasVolume) text(box, getString(R.string.vm_no_additional), false);
        }
        text(list, getString(R.string.vm_operations), true);
        if (data != null && data.operations != null) {
            int shown = 0;
            for (VmOperation operation : data.operations) {
                if (shown++ >= 12) break;
                LinearLayout box = card();
                text(box, operation.message, false);
                if ("failed".equals(operation.state) && !"expire_machine".equals(operation.kind)) {
                    button(box, getString(R.string.vm_retry), "retry:" + operation.id, true, () -> {
                        VmMonitorService.observe(this, operation.machineId, "power_on".equals(operation.kind));
                        submitting = true;
                        render();
                        AccountManager.retryOperation(this, operation.id, callback(null));
                    });
                }
            }
        }
        text(list, getString(R.string.vm_legacy_storage), false);
        text(list, getString(R.string.vm_tip_switch), false);
        if (focus != null) {
            View replacement = list.findViewWithTag(focus);
            if (replacement != null && replacement.isEnabled()) replacement.requestFocus();
        }
        scroll.post(() -> scroll.scrollTo(0, y));
    }
    private void confirm(String message, Runnable action) {
        new AlertDialog.Builder(this).setTitle(R.string.vm_management).setMessage(message)
                .setNegativeButton(R.string.game_menu_cancel, null)
                .setPositiveButton(R.string.vm_confirm, (dialog, which) -> action.run()).show();
    }
    private void submit(String machineId, MachineAction action) {
        if (submitting) return;
        VmMonitorService.observe(this, machineId, "power_on".equals(action.action));
        submitting = true;
        render();
        AccountManager.machineAction(this, machineId, action, callback(() -> submit(machineId, action)));
    }
    private AccountManager.ResultCallback<OperationResponse> callback(Runnable retry) {
        return new AccountManager.ResultCallback<OperationResponse>() {
            public void onSuccess(OperationResponse response) {
                submitting = false;
                if (!resumed) return;
                reload();
            }
            public void onError(String message) {
                submitting = false;
                if (!resumed) return;
                render();
                AlertDialog.Builder dialog = new AlertDialog.Builder(MachineManagementActivity.this)
                        .setMessage(message + "\n\n" + getString(R.string.vm_request_uncertain))
                        .setNegativeButton(R.string.vm_back, null);
                if (retry != null) dialog.setPositiveButton(R.string.vm_retry, (d, w) -> retry.run());
                dialog.show();
            }
        };
    }
    private void transfer(DataVolume volume) {
        List<MachineListItem> targets = new ArrayList<>();
        for (MachineListItem machine : data.machines) if (!volume.machineId.equals(machine.id)
                && volume.provider.equals(machine.provider) && !machine.locked && stopped(machine)) targets.add(machine);
        if (targets.isEmpty()) { Toast.makeText(this, R.string.vm_transfer_unavailable, Toast.LENGTH_LONG).show(); return; }
        String[] names = new String[targets.size()];
        for (int i = 0; i < targets.size(); i++) names[i] = targets.get(i).name;
        new AlertDialog.Builder(this).setTitle(R.string.vm_transfer_target).setItems(names, (dialog, index) -> {
            MachineListItem target = targets.get(index);
            confirm(getString(R.string.vm_transfer_warning, volume.sizeGb, target.name), () -> {
                MachineAction action = new MachineAction("transfer_volume");
                action.volumeId = volume.id;
                action.targetMachineId = target.id;
                action.eraseAdditionalData = true;
                submit(volume.machineId, action);
            });
        }).setNegativeButton(R.string.game_menu_cancel, null).show();
    }
    private void password(MachineListItem machine) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), dp(12));
        EditText first = new EditText(this), second = new EditText(this);
        first.setHint(R.string.vm_password);
        second.setHint(R.string.vm_password_confirm);
        int type = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
        first.setInputType(type); second.setInputType(type);
        box.addView(first); box.addView(second);
        CheckBox show = new CheckBox(this);
        show.setText(R.string.vm_show_password);
        show.setOnCheckedChangeListener((button, checked) -> {
            first.setInputType(checked ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : type);
            second.setInputType(checked ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : type);
        });
        box.addView(show);
        text(box, getString(R.string.vm_password_rules), false);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(machine.name).setView(box)
                .setNegativeButton(R.string.game_menu_cancel, null).setPositiveButton(R.string.vm_save, null).create();
        dialog.setOnDismissListener(ignored -> { first.setText(""); second.setText(""); });
        dialog.setOnShowListener(ignored -> {
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String value = first.getText().toString();
                if (!value.matches("[\\x21-\\x7E]{6,32}") || !value.equals(second.getText().toString())) {
                    second.setError(getString(R.string.vm_password_rules)); return;
                }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                AccountManager.setMachinePassword(this, machine.id, value, new AccountManager.ResultCallback<SetMachinePasswordResponse>() {
                    public void onSuccess(SetMachinePasswordResponse result) {
                        first.setText(""); second.setText(""); dialog.dismiss();
                        if (resumed) Toast.makeText(MachineManagementActivity.this,
                                "on_boot".equals(result.applied) ? R.string.vm_password_boot : R.string.vm_password_saved, Toast.LENGTH_LONG).show();
                    }
                    public void onError(String message) {
                        if (isDestroyed()) return;
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                        second.setError(message);
                    }
                });
            });
        });
        dialog.show();
    }
}
