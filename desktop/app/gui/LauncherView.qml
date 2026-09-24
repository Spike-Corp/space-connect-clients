import QtQuick 2.9
import QtQuick.Controls 2.3
import QtQuick.Layouts 1.3
import Qt.labs.platform 1.1 as Labs
import LauncherApi 1.0
import ComputerManager 1.0

Item {
    id: launcherView
    objectName: qsTr("SpaceCloud")
    property bool addingComputer: false

    function needsMachine() {
        return LauncherApi.state === "idle" && LauncherApi.machinesLoaded && !LauncherApi.hasMachine
    }

    function primaryAction() {
        if (needsMachine()) {
            createMachineDialog.open()
            return
        }
        if (LauncherApi.state === "queued")
            LauncherApi.leaveQueue()
        else if (LauncherApi.state === "ready")
            LauncherApi.requestConnection()
        else if (LauncherApi.state === "idle")
            LauncherApi.joinQueue()
    }

    function statusTitle() {
        if (needsMachine()) return qsTr("Create your PC")
        if (LauncherApi.state === "queued") return qsTr("You are in the queue")
        if (LauncherApi.state === "starting") return qsTr("Your PC is starting")
        if (LauncherApi.state === "ready") return qsTr("Your PC is ready")
        if (LauncherApi.state === "ending") return qsTr("Ending session")
        return qsTr("Ready to play")
    }

    // Rótulo amigável da fase de criação/boot (mesmo vocabulário do site).
    function creationPhaseLabel(phase) {
        var map = {
            "queued": qsTr("Allocating cloud resources"),
            "checking_snapshot": qsTr("Checking saved snapshots"),
            "restoring_disk": qsTr("Restoring VM disk"),
            "restoring_instance": qsTr("Recreating cloud instance"),
            "creating_instance": qsTr("Creating VM from template"),
            "attaching_gpu": qsTr("Attaching GPU"),
            "starting": qsTr("Starting the operating system"),
            "securing": qsTr("Securing the environment"),
            "setting_password": qsTr("Setting your access password"),
            "configuring_network": qsTr("Configuring the network"),
            "waiting_agent": qsTr("Connecting monitoring agent"),
            "ready": qsTr("Almost ready!")
        }
        return map[phase] || qsTr("Preparing your machine")
    }

    function statusDetails() {
        if (needsMachine())
            return qsTr("You don't have a dedicated PC yet. Create one to start playing.")
        if (LauncherApi.state === "queued")
            return qsTr("Position %1 of %2").arg(LauncherApi.queuePosition).arg(LauncherApi.queueTotal)
        // Mostra a fase real do boot (criação/proteção/rede) em vez de um "starting" genérico.
        if (LauncherApi.state === "starting" && LauncherApi.creationPhase)
            return creationPhaseLabel(LauncherApi.creationPhase)
        if (LauncherApi.machineName)
            return qsTr("%1 · %2 minutes remaining").arg(LauncherApi.machineName).arg(LauncherApi.remainingMinutes)
        return qsTr("Join the shared queue. Your plan determines your priority.")
    }

    function primaryText() {
        if (needsMachine()) return qsTr("Create your VM")
        if (LauncherApi.state === "queued") return qsTr("Leave queue")
        if (LauncherApi.state === "ready") return qsTr("Connect")
        if (LauncherApi.state === "idle") return qsTr("Open machine")
        return qsTr("Please wait")
    }

    function prettifyPlan(slug) {
        if (!slug) return qsTr("Plan")
        var clean = ("" + slug).replace(/-nw$/, "")
        var words = clean.split(/[-_]/)
        var out = []
        for (var i = 0; i < words.length; i++) {
            var w = words[i]
            if (w.length > 0) out.push(w.charAt(0).toUpperCase() + w.slice(1))
        }
        return out.length ? out.join(" ") : slug
    }

    // Linha de saldo do plano da VM em foco (sessão ativa > selecionada > única):
    // "Builder Lite · faltam 87h" / "Builder Lite · horas ilimitadas" (como no site).
    function planHoursText() {
        var list = LauncherApi.machines
        if (!list || list.length === 0) return ""
        var id = LauncherApi.statusMachineId || LauncherApi.selectedMachineId
        var m = null
        for (var i = 0; i < list.length; i++) {
            if (list[i].id === id) { m = list[i]; break }
        }
        if (!m && list.length === 1) m = list[0]
        if (!m || !m.entitlementActive) return ""
        var name = (m.planName && ("" + m.planName).trim().length > 0) ? m.planName : prettifyPlan(m.planSlug)
        if (m.unlimited) return qsTr("%1 · unlimited hours").arg(name)
        var h = Math.round((m.hoursRemaining || 0) * 10) / 10
        var hStr = (Math.floor(h) === h ? h.toFixed(0) : h.toFixed(1)) + "h"
        var text = qsTr("%1 · %2 left").arg(name).arg(hStr)
        var b = Math.round((m.bonusHours || 0) * 10) / 10
        if (b > 0) text += qsTr(" + %1 bonus").arg((Math.floor(b) === b ? b.toFixed(0) : b.toFixed(1)) + "h")
        return text
    }

    Connections {
        target: LauncherApi
        function onConnectionReady(address) {
            addingComputer = true
            ComputerManager.addNewHostManually(address)
        }
        // Navegação de login/logout é centralizada no main.qml
        // (window.showLoginView/showLauncherView) — não duplicar aqui.
        function onBugReportFinished(success, message) {
            if (success) {
                bugReportDialog.close()
                bugResultDialog.isError = false
            } else {
                bugResultDialog.isError = true
            }
            bugResultDialog.customTitle = ""
            bugResultDialog.text = message
            bugResultDialog.open()
        }
        function onUsbHelperMissing() {
            usbSetupDialog.open()
        }
        function onUsbSessionStarted(machineName) {
            bugResultDialog.isError = false
            bugResultDialog.customTitle = qsTr("USB Passthrough")
            bugResultDialog.text = qsTr("SpaceUSB is open — pick the device and connect. It will show up in %1 as if plugged in directly.").arg(machineName)
            bugResultDialog.open()
        }
    }

    Connections {
        target: ComputerManager
        function onComputerAddCompleted(success, detectedPortBlocking) {
            if (!addingComputer)
                return
            addingComputer = false
            if (success)
                stackView.replace("qrc:/gui/PcView.qml")
            else
                errorDialog.open()
        }
    }

    Timer {
        interval: 5000
        repeat: true
        running: true
        triggeredOnStart: true
        onTriggered: LauncherApi.refreshStatus()
    }

    ColumnLayout {
        anchors.centerIn: parent
        width: Math.min(620, parent.width - 60)
        spacing: 16

        Label {
            text: "SPACE CONNECT"
            color: "#a482fa"
            font.pixelSize: 30
            font.bold: true
            Layout.alignment: Qt.AlignHCenter
        }

        Label {
            text: LauncherApi.email
            color: "#9793aa"
            Layout.alignment: Qt.AlignHCenter
        }

        Rectangle {
            Layout.fillWidth: true
            implicitHeight: statusColumn.implicitHeight + 48
            radius: 14
            color: "#110d17"
            border.width: 1
            border.color: "#e8e2ff"

            ColumnLayout {
                id: statusColumn
                anchors.fill: parent
                anchors.margins: 24
                spacing: 14

                Label {
                    text: statusTitle()
                    color: "#f8f5ff"
                    font.pixelSize: 24
                    font.bold: true
                    Layout.fillWidth: true
                }

                Label {
                    text: statusDetails()
                    color: "#9793aa"
                    wrapMode: Text.WordWrap
                    Layout.fillWidth: true
                }

                Label {
                    visible: LauncherApi.errorMessage.length > 0
                    text: LauncherApi.errorMessage
                    color: "#F87171"
                    wrapMode: Text.WordWrap
                    Layout.fillWidth: true
                }

                // Saldo do plano (horas restantes / ilimitado) — paridade com o
                // site e com o app Android. A binding reavalia sozinha porque lê
                // propriedades com NOTIFY (machines/statusMachineId/selectedMachineId).
                Label {
                    visible: text.length > 0
                    text: planHoursText()
                    color: "#e8c85a"
                    font.bold: true
                    wrapMode: Text.WordWrap
                    Layout.fillWidth: true
                }

                BusyIndicator {
                    running: LauncherApi.busy || addingComputer
                    visible: running
                    Layout.alignment: Qt.AlignHCenter
                }

                // Seletor de VM pra quem tem 2+ planos/máquinas. O app Android já
                // tinha; o desktop não mandava machineId em nada e o backend
                // abria "a" sessão ativa — usuário multi-plano não escolhia.
                Label {
                    visible: LauncherApi.machines.length > 1
                    text: qsTr("Which PC do you want to open?")
                    color: "#9793aa"
                    Layout.fillWidth: true
                }

                SpaceComboBox {
                    id: machineSelector
                    visible: LauncherApi.machines.length > 1
                    model: LauncherApi.machines
                    textRole: "display"
                    placeholderText: qsTr("Select a machine")
                    Layout.fillWidth: true

                    function syncSelection() {
                        var idx = -1
                        for (var i = 0; i < LauncherApi.machines.length; i++) {
                            if (LauncherApi.machines[i].id === LauncherApi.selectedMachineId) {
                                idx = i
                                break
                            }
                        }
                        currentIndex = idx
                    }

                    onActivated: function(index) {
                        if (index >= 0 && index < LauncherApi.machines.length)
                            LauncherApi.selectedMachineId = LauncherApi.machines[index].id
                    }

                    Component.onCompleted: syncSelection()

                    Connections {
                        target: LauncherApi
                        function onMachinesChanged() { machineSelector.syncSelection() }
                    }
                }

                Button {
                    text: primaryText()
                    highlighted: true
                    enabled: !LauncherApi.busy
                             && !addingComputer
                             && (needsMachine()
                                 || LauncherApi.state === "idle"
                                 || LauncherApi.state === "queued"
                                 || LauncherApi.state === "ready")
                    Layout.fillWidth: true
                    onClicked: primaryAction()
                }

                Button {
                    text: qsTr("End session")
                    visible: LauncherApi.state === "ready"
                    enabled: !LauncherApi.busy
                    Layout.fillWidth: true
                    onClicked: LauncherApi.endSession()
                }

                Button {
                    text: qsTr("Send file to your PC")
                    enabled: !LauncherApi.busy
                    Layout.fillWidth: true
                    onClicked: uploadFileDialog.open()
                }

                Button {
                    // Paridade com o Android: teste de rede/latência (mediana +
                    // jitter + veredito pra cloud gaming).
                    text: qsTr("Test network / latency")
                    enabled: !LauncherApi.busy
                    Layout.fillWidth: true
                    onClicked: navigateTo("qrc:/gui/LatencyTestView.qml", "LatencyTestView")
                }

                RowLayout {
                    Layout.fillWidth: true
                    spacing: 10

                    Button {
                        // Amigos (beta): username, pedidos, permissões e máquinas
                        // compartilhadas — estilo Parsec.
                        text: qsTr("Friends")
                        Layout.fillWidth: true
                        onClicked: navigateTo("qrc:/gui/FriendsView.qml", "FriendsView")
                    }

                    Button {
                        text: qsTr("Report a problem")
                        enabled: !LauncherApi.busy
                        Layout.fillWidth: true
                        onClicked: bugReportDialog.open()
                    }
                }

                Button {
                    // SpaceUSB: usa um dispositivo USB deste PC dentro da VM
                    // (volante, controle, dongle). Só faz sentido com a VM pronta.
                    visible: LauncherApi.state === "ready"
                    text: qsTr("USB Passthrough")
                    enabled: !LauncherApi.busy
                    Layout.fillWidth: true
                    onClicked: LauncherApi.startUsbPassthrough()
                }
            }
        }

        RowLayout {
            Layout.alignment: Qt.AlignHCenter
            Button {
                text: qsTr("Refresh")
                onClicked: LauncherApi.refreshStatus()
            }
            Button {
                text: qsTr("Sign out")
                onClicked: LauncherApi.logout()
            }
        }
    }

    Dialog {
        id: createMachineDialog
        title: qsTr("Create your VM")
        standardButtons: Dialog.Cancel
        parent: Overlay.overlay
        x: Math.round((parent.width - width) / 2)
        y: Math.round((parent.height - height) / 2)
        modal: true

        ColumnLayout {
            width: 320
            spacing: 12

            Label {
                text: qsTr("Choose a password for your PC (used for Windows login too).")
                wrapMode: Text.WordWrap
                color: "#9793aa"
                Layout.fillWidth: true
            }

            TextField {
                id: machinePasswordField
                echoMode: TextInput.Password
                placeholderText: qsTr("Password (5-64 characters)")
                Layout.fillWidth: true
            }

            Button {
                text: qsTr("Create VM")
                highlighted: true
                enabled: machinePasswordField.text.length >= 5 && machinePasswordField.text.length <= 64
                Layout.fillWidth: true
                onClicked: {
                    LauncherApi.createMachine(machinePasswordField.text)
                    machinePasswordField.text = ""
                    createMachineDialog.close()
                }
            }
        }
    }

    Dialog {
        id: errorDialog
        title: qsTr("Connection failed")
        standardButtons: Dialog.Ok
        parent: Overlay.overlay
        x: Math.round((parent.width - width) / 2)
        y: Math.round((parent.height - height) / 2)
        Label {
            text: qsTr("The Moonlight host is not ready yet. Try again in a few seconds.")
            wrapMode: Text.WordWrap
        }
    }

    Dialog {
        id: bugReportDialog
        title: qsTr("Report a bug")
        modal: true
        parent: Overlay.overlay
        x: Math.round((parent.width - width) / 2)
        y: Math.round((parent.height - height) / 2)
        standardButtons: Dialog.Ok | Dialog.Cancel
        onOpened: bugReportText.forceActiveFocus()
        onAccepted: {
            LauncherApi.reportBug(bugReportText.text, LauncherApi.email)
            bugReportText.text = ""
        }

        ColumnLayout {
            width: 340
            spacing: 10

            Label {
                text: qsTr("Tell us what happened. We include your app version and machine status automatically.")
                wrapMode: Text.WordWrap
                Layout.fillWidth: true
            }

            TextArea {
                id: bugReportText
                placeholderText: qsTr("Describe the problem (what you did, what happened)...")
                wrapMode: TextArea.Wrap
                Layout.fillWidth: true
                Layout.preferredHeight: 120
            }
        }
    }

    Dialog {
        id: bugResultDialog
        property bool isError: false
        property string customTitle: ""
        property alias text: launcherBugResultLabel.text
        title: customTitle.length > 0 ? customTitle : (isError ? qsTr("Could not send") : qsTr("Report sent"))
        modal: true
        parent: Overlay.overlay
        x: Math.round((parent.width - width) / 2)
        y: Math.round((parent.height - height) / 2)
        standardButtons: Dialog.Ok

        Label {
            id: launcherBugResultLabel
            wrapMode: Text.WordWrap
            width: 300
        }
    }

    Labs.FileDialog {
        id: uploadFileDialog
        title: qsTr("Choose a file to send")
        fileMode: Labs.FileDialog.OpenFile
        onAccepted: LauncherApi.uploadFileToVm(file.toString())
    }

    // Oferecido quando o usuário clica em "USB Passthrough" sem o helper instalado.
    Dialog {
        id: usbSetupDialog
        title: qsTr("USB Passthrough — SpaceUSB")
        modal: true
        parent: Overlay.overlay
        x: Math.round((parent.width - width) / 2)
        y: Math.round((parent.height - height) / 2)
        standardButtons: Dialog.Ok | Dialog.Cancel
        onAccepted: Qt.openUrlExternally("https://downloads.spacecloud.gg/SpaceUSB.exe")

        ColumnLayout {
            width: 360
            spacing: 10

            Label {
                text: qsTr("To use a USB device from this PC (wheel, controller, dongle) inside your cloud machine, install the free SpaceUSB helper once.")
                wrapMode: Text.WordWrap
                Layout.fillWidth: true
            }
            Label {
                text: qsTr("It installs to AppData (no admin needed day to day). After installing, click USB Passthrough again with your machine running.")
                wrapMode: Text.WordWrap
                color: "#9793aa"
                Layout.fillWidth: true
            }
            Label {
                text: qsTr("OK = download SpaceUSB now")
                color: "#4572fa"
                Layout.fillWidth: true
            }
        }
    }

    Dialog {
        id: uploadSuccessDialog
        title: qsTr("File sent")
        standardButtons: Dialog.Ok
        parent: Overlay.overlay
        x: Math.round((parent.width - width) / 2)
        y: Math.round((parent.height - height) / 2)
        property string fileName: ""
        Label {
            text: qsTr("%1 was sent to the Downloads folder on your PC.").arg(uploadSuccessDialog.fileName)
            wrapMode: Text.WordWrap
        }
    }

    Connections {
        target: LauncherApi
        function onFileUploadSucceeded(fileName) {
            uploadSuccessDialog.fileName = fileName
            uploadSuccessDialog.open()
        }
    }
}
