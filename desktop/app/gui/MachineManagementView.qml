import QtQuick 2.15
import QtQuick.Controls 2.3
import QtQuick.Layouts 1.3
import LauncherApi 1.0

Item {
    id: root
    objectName: qsTr("Machines and storage")
    BrandTheme { id: brand }
    property var machines: LauncherApi.management.machines || []
    property var volumes: LauncherApi.management.volumes || []
    property var operations: LauncherApi.management.operations || []
    property var pending: null
    property string notice: ""
    property string focusKey: ""

    function working(machine) {
        if (machine.operationId) return true
        var phases = machine.phases || {}
        var storage = machine.storage || {}
        if (phases.creation || phases.shutdown || phases.format
                || storage.snapshotStatus === "creating" || storage.snapshotStatus === "restoring") return true
        return operations.some(function(op) {
            return (op.state === "queued" || op.state === "running") && op.machineIds && op.machineIds.indexOf(machine.id) >= 0
        })
    }
    function powered(machine) { return !!machine.session || machine.state === "idle" || machine.state === "busy" }
    function stopped(machine) {
        var phases = machine.phases || {}
        var storage = machine.storage || {}
        return !powered(machine) && !machine.queue && !working(machine) && !phases.creation && !phases.shutdown && !phases.format
                && storage.snapshotStatus !== "creating" && storage.snapshotStatus !== "restoring"
                && ["offline", "deleted", "hibernated", "archived"].indexOf(machine.state) >= 0
    }
    function action(machine, kind) {
        pending = {machineId: machine.id, kind: kind, options: {requestId: LauncherApi.newOperationRequestId()}}
        if (kind === "power_on") { submit(); return }
        confirmation.message = kind === "format_os"
                ? qsTr("Reinstall Windows on %1? ALL files on its Windows disk will be erased, including older upgrades integrated into it. Separate additional disks and other machines are preserved.").arg(machine.name)
                : kind === "restart"
                    ? qsTr("Force-restart %1? Unsaved work may be lost. Its session time and billing continue; other machines are not affected.").arg(machine.name)
                    : qsTr("Shut down only %1? Save your work. Other machines remain powered on.").arg(machine.name)
        if (kind === "format_os") pending.options.eraseWindows = true
        confirmation.open()
    }
    function submit() {
        if (pending) LauncherApi.machineAction(pending.machineId, pending.kind, pending.options)
    }
    function transfer(volume) {
        transferDialog.volume = volume
        transferDialog.targets = machines.filter(function(m) {
            return m.id !== volume.machineId && m.provider === volume.provider && !m.locked
                    && m.entitlement && m.entitlement.active && stopped(m)
        })
        if (!transferDialog.targets.length) {
            notice = qsTr("Power off both machines and finish their operations. The destination needs an active plan on the same provider.")
            return
        }
        targetSelector.currentIndex = 0
        transferDialog.open()
    }

    Component.onCompleted: LauncherApi.refreshManagement()
    Connections {
        target: LauncherApi
        function onManagementActionFinished(ok, message) {
            root.notice = message
            if (ok) root.pending = null
            else if (root.pending) retryRequest.open()
        }
        function onMachinePasswordFinished(ok, message, applied, machineId) {
            if (!passwordDialog.visible || !passwordDialog.submitting || !passwordDialog.machine
                    || passwordDialog.machine.id !== machineId) return
            passwordDialog.submitting = false
            if (ok) { passwordDialog.close(); root.notice = message }
            else passwordError.text = message
        }
    }
    ScrollView {
        anchors.fill: parent
        anchors.margins: Math.min(28, parent.width * 0.04)
        clip: true
        contentWidth: availableWidth
        ColumnLayout {
            width: parent.width
            spacing: 16
            Label { text: qsTr("Your cloud, under your control"); font.family: brand.headingFont; font.pixelSize: 28; color: brand.foreground; wrapMode: Text.WordWrap; Layout.fillWidth: true }
            Label { text: qsTr("Start, restart or manage your machines without entering Windows. Each powered-on VM uses its own plan."); color: brand.muted; wrapMode: Text.WordWrap; Layout.fillWidth: true }
            Label { visible: !!LauncherApi.managementError; text: LauncherApi.managementError; textFormat: Text.PlainText; color: brand.danger; wrapMode: Text.WordWrap; Layout.fillWidth: true; Accessible.role: Accessible.AlertMessage }
            Label { visible: !!root.notice; text: root.notice; textFormat: Text.PlainText; color: brand.purple; wrapMode: Text.WordWrap; Layout.fillWidth: true; Accessible.role: Accessible.AlertMessage }
            Label { visible: root.machines.length === 0; text: qsTr("No machines to manage."); color: brand.muted }
            Repeater {
                model: root.machines
                Frame {
                    id: machineCard
                    objectName: "machine:" + machine.id
                    property var machine: modelData
                    Layout.fillWidth: true
                    padding: 20
                    background: Rectangle { radius: 16; color: brand.surface; border.color: brand.border }
                    ColumnLayout {
                        anchors.fill: parent
                        spacing: 12
                        Label { text: machineCard.machine.name; textFormat: Text.PlainText; font.family: brand.headingFont; font.pixelSize: 23; color: brand.foreground; wrapMode: Text.WrapAnywhere; Layout.fillWidth: true }
                        Label {
                            text: root.working(machineCard.machine) || (machineCard.machine.phases && (machineCard.machine.phases.creation || machineCard.machine.phases.shutdown))
                                  ? qsTr("Operation in progress") : machineCard.machine.queue ? qsTr("Queued for this machine")
                                  : root.powered(machineCard.machine) ? qsTr("Powered on") : qsTr("Powered off")
                            color: brand.purple
                        }
                        Label { text: qsTr("Windows disk: %1 GB · %2 reinstall credit(s)").arg(machineCard.machine.specs ? machineCard.machine.specs.diskGb || "—" : "—").arg(machineCard.machine.formats ? machineCard.machine.formats.remaining : 0); color: brand.muted; wrapMode: Text.WordWrap; Layout.fillWidth: true }
                        Flow {
                            Layout.fillWidth: true
                            spacing: 8
                            Repeater {
                                model: [
                                    {kind: "power_on", label: qsTr("Power on")},
                                    {kind: "restart", label: qsTr("Restart")},
                                    {kind: "power_off", label: qsTr("Shut down")},
                                    {kind: "format_os", label: qsTr("Reinstall Windows")}
                                ]
                                Button {
                                    text: modelData.label
                                    property string actionKind: modelData.kind
                                    highlighted: actionKind === "power_on"
                                    property string actionKey: machineCard.machine.id + ":" + actionKind
                                    objectName: actionKey
                                    enabled: !LauncherApi.managementBusy && !root.working(machineCard.machine)
                                             && (actionKind !== "power_on" || (!root.powered(machineCard.machine) && !machineCard.machine.queue && !machineCard.machine.locked))
                                             && (actionKind !== "restart" || !!machineCard.machine.session)
                                             && (actionKind !== "power_off" || root.powered(machineCard.machine) || !!machineCard.machine.queue)
                                             && (actionKind !== "format_os" || (!machineCard.machine.queue && !machineCard.machine.locked && machineCard.machine.formats && machineCard.machine.formats.remaining > 0))
                                    onClicked: root.action(machineCard.machine, actionKind)
                                    onActiveFocusChanged: if (activeFocus) root.focusKey = actionKey
                                    Component.onCompleted: {
                                        if (root.focusKey === actionKey && root.StackView.status === StackView.Active
                                                && !confirmation.visible && !passwordDialog.visible && !transferDialog.visible && !retryRequest.visible)
                                            forceActiveFocus()
                                    }
                                }
                            }
                            Button {
                                text: qsTr("Windows password")
                                objectName: machineCard.machine.id + ":password"
                                enabled: !root.working(machineCard.machine)
                                onClicked: { passwordDialog.machine = machineCard.machine; passwordDialog.open() }
                            }
                            Button {
                                text: qsTr("Buy additional storage")
                                enabled: !machineCard.machine.locked
                                onClicked: Qt.openUrlExternally("https://spacecloud.gg/painel/loja?storageFor=" + machineCard.machine.id)
                            }
                            Button {
                                text: qsTr("Buy a reinstall credit")
                                visible: machineCard.machine.formats && machineCard.machine.formats.remaining === 0
                                onClicked: Qt.openUrlExternally("https://spacecloud.gg/painel/loja?formatFor=" + machineCard.machine.id)
                            }
                        }
                        Repeater {
                            model: root.volumes.filter(function(v) { return v.machineId === machineCard.machine.id })
                            ColumnLayout {
                                Layout.fillWidth: true
                                Label { text: qsTr("Separate additional disk · %1 GB").arg(modelData.sizeGb); color: brand.foreground; font.bold: true }
                                Label {
                                    text: modelData.initializationError || (modelData.state === "error" ? qsTr("Needs recovery. Check the operation below.")
                                          : modelData.initialized ? qsTr("Available in Windows as %1:").arg(modelData.driveLetter || "?")
                                          : qsTr("Disk preparation will be checked when Windows starts."))
                                    textFormat: Text.PlainText; wrapMode: Text.WordWrap; color: brand.muted; Layout.fillWidth: true
                                }
                                Button {
                                    text: qsTr("Transfer this additional disk")
                                    objectName: modelData.id + ":transfer"
                                    enabled: modelData.state === "attached" && !modelData.operationId && root.stopped(machineCard.machine) && !LauncherApi.managementBusy
                                    onClicked: root.transfer(modelData)
                                }
                            }
                        }
                    }
                }
            }
            Label { text: qsTr("Recent operations"); font.family: brand.headingFont; font.pixelSize: 22; color: brand.foreground }
            Label { text: qsTr("Accepted operations continue on the server if you close the app. Older upgrades integrated into Windows cannot be transferred separately. Data follows the retention period of its linked plan."); color: brand.muted; wrapMode: Text.WordWrap; Layout.fillWidth: true }
            Repeater {
                model: root.operations.slice(0, 15)
                Frame {
                    Layout.fillWidth: true
                    padding: 16
                    background: Rectangle { color: brand.surface; radius: 12; border.color: brand.border }
                    ColumnLayout {
                        anchors.fill: parent
                        Label { text: modelData.message; textFormat: Text.PlainText; color: modelData.state === "failed" ? brand.danger : brand.foreground; wrapMode: Text.WordWrap; Layout.fillWidth: true }
                        Label { visible: modelData.dataErased && modelData.state === "failed"; text: qsTr("The additional disk was already erased. Recovery resumes delivery of the empty disk; it cannot restore those files."); color: brand.danger; wrapMode: Text.WordWrap; Layout.fillWidth: true }
                        Button { visible: modelData.state === "failed" && modelData.kind !== "expire_machine"; text: qsTr("Retry safely"); enabled: !LauncherApi.managementBusy; onClicked: { root.pending = null; LauncherApi.retryOperation(modelData.id) } }
                    }
                }
            }
        }
    }
    Dialog {
        id: confirmation
        objectName: "machineConfirmation"
        property string message: ""
        title: qsTr("Confirm machine operation")
        parent: Overlay.overlay
        width: Math.min(560, parent.width - 32)
        x: (parent.width - width) / 2
        y: (parent.height - height) / 2
        modal: true
        closePolicy: Popup.CloseOnEscape
        standardButtons: Dialog.Ok | Dialog.Cancel
        contentItem: Label { text: confirmation.message; textFormat: Text.PlainText; wrapMode: Text.WordWrap; color: brand.foreground }
        onAccepted: root.submit()
        onRejected: root.pending = null
    }
    Dialog {
        id: retryRequest
        objectName: "machineRetry"
        title: qsTr("Request not confirmed")
        parent: Overlay.overlay
        width: Math.min(560, parent.width - 32)
        x: (parent.width - width) / 2
        y: (parent.height - height) / 2
        modal: true
        standardButtons: Dialog.Retry | Dialog.Cancel
        contentItem: Label { text: root.notice + "\n\n" + qsTr("Retry uses the same identifier. Check the operations before creating a new request."); textFormat: Text.PlainText; wrapMode: Text.WordWrap }
        onAccepted: root.submit()
        onRejected: root.pending = null
    }
    Dialog {
        id: transferDialog
        objectName: "volumeTransfer"
        property var volume: null
        property var targets: []
        title: qsTr("Transfer additional capacity")
        parent: Overlay.overlay
        width: Math.min(560, parent.width - 32)
        x: (parent.width - width) / 2
        y: (parent.height - height) / 2
        modal: true
        standardButtons: Dialog.Ok | Dialog.Cancel
        contentItem: ColumnLayout {
            Label { text: qsTr("Choose a powered-off destination on the same provider."); wrapMode: Text.WordWrap; Layout.fillWidth: true }
            SpaceComboBox { id: targetSelector; model: transferDialog.targets; textRole: "name"; Layout.fillWidth: true }
            Label {
                text: qsTr("ALL files only on this %1 GB additional disk will be erased. The destination receives an EMPTY disk, without Windows. Both VMs and their Windows disks remain intact. No new purchase is charged.").arg(transferDialog.volume ? transferDialog.volume.sizeGb : 0)
                color: brand.danger; wrapMode: Text.WordWrap; Layout.fillWidth: true
            }
        }
        onAccepted: {
            var destination = targets[targetSelector.currentIndex]
            if (!destination || !volume) return
            root.pending = {machineId: volume.machineId, kind: "transfer_volume", options: {
                requestId: LauncherApi.newOperationRequestId(), volumeId: volume.id,
                targetMachineId: destination.id, eraseAdditionalData: true
            }}
            root.submit()
        }
    }
    Dialog {
        id: passwordDialog
        objectName: "machinePassword"
        property var machine: null
        property bool submitting: false
        title: qsTr("Windows password")
        parent: Overlay.overlay
        width: Math.min(480, parent.width - 32)
        x: (parent.width - width) / 2
        y: (parent.height - height) / 2
        modal: true
        standardButtons: Dialog.Cancel
        onClosed: { password.text = ""; passwordAgain.text = ""; passwordError.text = ""; submitting = false }
        contentItem: ColumnLayout {
            TextField { id: password; placeholderText: qsTr("New password"); echoMode: showPassword.checked ? TextInput.Normal : TextInput.Password; maximumLength: 32; Layout.fillWidth: true }
            TextField { id: passwordAgain; placeholderText: qsTr("Confirm password"); echoMode: showPassword.checked ? TextInput.Normal : TextInput.Password; maximumLength: 32; Layout.fillWidth: true }
            CheckBox { id: showPassword; text: qsTr("Show password") }
            Label { text: qsTr("Use 6–32 letters, numbers or symbols without spaces."); color: brand.muted; wrapMode: Text.WordWrap; Layout.fillWidth: true }
            Label { id: passwordError; color: brand.danger; textFormat: Text.PlainText; wrapMode: Text.WordWrap; Layout.fillWidth: true }
            Button {
                text: qsTr("Save password")
                enabled: !passwordDialog.submitting && /^[\x21-\x7E]{6,32}$/.test(password.text) && password.text === passwordAgain.text
                onClicked: { passwordDialog.submitting = true; LauncherApi.setMachinePassword(passwordDialog.machine.id, password.text) }
            }
        }
    }
}
