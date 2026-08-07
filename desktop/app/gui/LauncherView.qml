import QtQuick 2.9
import QtQuick.Controls 2.2
import QtQuick.Layouts 1.3
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

    function statusDetails() {
        if (needsMachine())
            return qsTr("You don't have a dedicated PC yet. Create one to start playing.")
        if (LauncherApi.state === "queued")
            return qsTr("Position %1 of %2").arg(LauncherApi.queuePosition).arg(LauncherApi.queueTotal)
        if (LauncherApi.machineName)
            return qsTr("%1 · %2 minutes remaining").arg(LauncherApi.machineName).arg(LauncherApi.remainingMinutes)
        return qsTr("Join the shared queue. Your plan determines your priority.")
    }

    function primaryText() {
        if (needsMachine()) return qsTr("Create your VM")
        if (LauncherApi.state === "queued") return qsTr("Leave queue")
        if (LauncherApi.state === "ready") return qsTr("Connect with Moonlight")
        if (LauncherApi.state === "idle") return qsTr("Join queue")
        return qsTr("Please wait")
    }

    Connections {
        target: LauncherApi
        function onConnectionReady(address) {
            addingComputer = true
            ComputerManager.addNewHostManually(address)
        }
        function onLoggedInChanged() {
            if (!LauncherApi.loggedIn)
                stackView.replace("qrc:/gui/LoginView.qml")
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
            color: "#9B6BFF"
            font.pixelSize: 30
            font.bold: true
            Layout.alignment: Qt.AlignHCenter
        }

        Label {
            text: LauncherApi.email
            color: "#A79BC9"
            Layout.alignment: Qt.AlignHCenter
        }

        Rectangle {
            Layout.fillWidth: true
            implicitHeight: statusColumn.implicitHeight + 48
            radius: 14
            color: "#201A33"
            border.width: 1
            border.color: "#E4D9FF"

            ColumnLayout {
                id: statusColumn
                anchors.fill: parent
                anchors.margins: 24
                spacing: 14

                Label {
                    text: statusTitle()
                    color: "#F1EDFB"
                    font.pixelSize: 24
                    font.bold: true
                    Layout.fillWidth: true
                }

                Label {
                    text: statusDetails()
                    color: "#A79BC9"
                    wrapMode: Text.WordWrap
                    Layout.fillWidth: true
                }

                Label {
                    visible: LauncherApi.errorMessage.length > 0
                    text: LauncherApi.errorMessage
                    color: "#FF5D5D"
                    wrapMode: Text.WordWrap
                    Layout.fillWidth: true
                }

                BusyIndicator {
                    running: LauncherApi.busy || addingComputer
                    visible: running
                    Layout.alignment: Qt.AlignHCenter
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
        anchors.centerIn: parent
        modal: true

        ColumnLayout {
            width: 320
            spacing: 12

            Label {
                text: qsTr("Choose a password for your PC (used for Windows login too).")
                wrapMode: Text.WordWrap
                color: "#A79BC9"
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
        anchors.centerIn: parent
        Label {
            text: qsTr("The Moonlight host is not ready yet. Try again in a few seconds.")
            wrapMode: Text.WordWrap
        }
    }
}
