import QtQuick 2.15
import QtQuick.Controls 2.3
import QtQuick.Controls.Material 2.3
import QtTest 1.2
import LauncherApi 1.0
import "../../gui" as Connect

TestCase {
    id: testCase
    name: "MachineManagement"
    when: windowShown

    FontLoader { source: "../../res/fonts/inter_regular.ttf" }
    FontLoader { source: "../../res/fonts/bai_jamjuree_semibold.ttf" }
    Component {
        id: factory
        ApplicationWindow {
            width: 1040
            height: 900
            visible: true
            color: theme.background
            font.family: "Inter"
            Material.theme: Material.Dark
            Material.accent: theme.purple
            Connect.BrandTheme { id: theme }
            property alias managementView: view
            Connect.MachineManagementView { id: view; anchors.fill: parent }
        }
    }
    function machine(id, provider, active) {
        return {id: id, name: "SpaceCloud " + id, provider: provider, state: "offline",
            locked: false, phases: {}, storage: {}, specs: {diskGb: 256},
            formats: {remaining: 3}, entitlement: {active: active !== false}}
    }
    function init() {
        LauncherApi.actions = []
        LauncherApi.management = {
            machines: [machine("alpha", "gcp"), machine("beta", "gcp"), machine("gamma", "proxmox"), machine("expired", "gcp", false)],
            volumes: [{id: "extra", machineId: "alpha", provider: "gcp", sizeGb: 50, state: "attached"}],
            operations: []
        }
    }
    function openView() {
        var window = createTemporaryObject(factory, null)
        verify(window)
        verify(waitForRendering(window.managementView))
        compare(window.managementView.machines.length, 4)
        return window.managementView
    }
    function test_power_actions_keep_each_machine_target() {
        var view = openView()
        mouseClick(findChild(view, "beta:power_on"))
        mouseClick(findChild(view, "alpha:power_on"))
        compare(LauncherApi.actions.length, 2)
        compare(LauncherApi.actions[0].machineId, "beta")
        compare(LauncherApi.actions[1].machineId, "alpha")
        compare(LauncherApi.actions[0].kind, "power_on")
        compare(LauncherApi.actions[1].kind, "power_on")
    }
    function test_format_requires_confirmation_and_cancel_clears_request() {
        var view = openView()
        mouseClick(findChild(view, "alpha:format_os"))
        var confirmation = findChild(view, "machineConfirmation")
        tryCompare(confirmation, "visible", true)
        compare(LauncherApi.actions.length, 0)
        mouseClick(confirmation.standardButton(Dialog.Cancel))
        tryCompare(confirmation, "visible", false)
        compare(view.pending, null)
        compare(LauncherApi.actions.length, 0)
    }
    function test_transfer_keeps_provider_and_erases_only_additional_disk() {
        var view = openView()
        mouseClick(findChild(view, "extra:transfer"))
        var confirmation = findChild(view, "volumeTransfer")
        tryCompare(confirmation, "visible", true)
        compare(confirmation.targets.length, 1)
        compare(confirmation.targets[0].id, "beta")
        compare(LauncherApi.actions.length, 0)
        mouseClick(confirmation.standardButton(Dialog.Ok))
        compare(LauncherApi.actions.length, 1)
        compare(LauncherApi.actions[0].machineId, "alpha")
        compare(LauncherApi.actions[0].kind, "transfer_volume")
        compare(LauncherApi.actions[0].options.volumeId, "extra")
        compare(LauncherApi.actions[0].options.targetMachineId, "beta")
        compare(LauncherApi.actions[0].options.eraseAdditionalData, true)
        verify(LauncherApi.actions[0].options.eraseWindows === undefined)
    }
    function test_old_password_result_cannot_close_another_machine_dialog() {
        var view = openView()
        mouseClick(findChild(view, "beta:password"))
        var dialog = findChild(view, "machinePassword")
        tryCompare(dialog, "visible", true)
        dialog.submitting = true
        LauncherApi.machinePasswordFinished(true, "Old result", "pending_agent", "alpha")
        compare(dialog.visible, true)
        compare(dialog.submitting, true)
        LauncherApi.machinePasswordFinished(true, "Current result", "pending_agent", "beta")
        tryCompare(dialog, "visible", false)
    }
    function test_retry_reuses_request_and_machine() {
        var view = openView()
        mouseClick(findChild(view, "alpha:power_on"))
        var original = LauncherApi.actions[0]
        LauncherApi.managementActionFinished(false, "Request not confirmed")
        var dialog = findChild(view, "machineRetry")
        tryCompare(dialog, "opened", true)
        mouseClick(dialog.standardButton(Dialog.Retry))
        compare(LauncherApi.actions.length, 2)
        compare(LauncherApi.actions[1].machineId, original.machineId)
        compare(LauncherApi.actions[1].options.requestId, original.options.requestId)
    }
}
