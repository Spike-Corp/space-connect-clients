import QtQuick 2.9
import QtQuick.Controls 2.2
import QtQuick.Layouts 1.3
import LauncherApi 1.0
import ComputerManager 1.0

// Sistema de amigos (beta) — estilo Parsec: adicionar por username, aceitar
// pedidos, conceder "mostrar minha VM" / "deixar conectar", e conectar nas
// máquinas que amigos compartilham comigo. Mesma base do site (Social) —
// tudo sincronizado pela mesma API.
Item {
    id: friendsView
    objectName: qsTr("Friends")
    property bool addingComputer: false

    Component.onCompleted: LauncherApi.refreshFriends()

    Connections {
        target: LauncherApi
        function onFriendActionResult(success, message) {
            friendResultDialog.isError = !success
            friendResultDialog.text = message
            friendResultDialog.open()
        }
        function onConnectionReady(address) {
            addingComputer = true
            ComputerManager.addNewHostManually(address)
        }
        function onUsernameCheckResult(available, reason) {
            usernameCheckLabel.visible = true
            if (available) {
                usernameCheckLabel.color = "#4ade80"
                usernameCheckLabel.text = qsTr("Available!")
            } else {
                usernameCheckLabel.color = "#F87171"
                usernameCheckLabel.text = reason === "taken" ? qsTr("Already in use") : qsTr("Invalid format")
            }
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

    function friendLabel(entry) {
        return entry.username ? "@" + entry.username : (entry.name || qsTr("No name"))
    }

    Flickable {
        anchors.fill: parent
        contentWidth: width
        contentHeight: Math.max(height, mainColumn.height + 60)
        boundsBehavior: Flickable.OvershootBounds

        ColumnLayout {
            id: mainColumn
            anchors.horizontalCenter: parent.horizontalCenter
            y: 24
            width: Math.min(640, parent.width - 60)
            spacing: 14

            RowLayout {
                Layout.fillWidth: true
                Label {
                    text: qsTr("FRIENDS")
                    color: "#a482fa"
                    font.pixelSize: 24
                    font.bold: true
                }
                Label {
                    text: "BETA"
                    color: "#4572fa"
                    font.pixelSize: 11
                    font.bold: true
                    Layout.alignment: Qt.AlignTop
                }
                Item { Layout.fillWidth: true }
                Button {
                    text: qsTr("Refresh")
                    onClicked: LauncherApi.refreshFriends()
                }
            }

            Rectangle {
                Layout.fillWidth: true
                implicitHeight: usernameColumn.implicitHeight + 32
                radius: 12
                color: "#110d17"
                border.width: 1
                border.color: "#e8e2ff"

                ColumnLayout {
                    id: usernameColumn
                    anchors.fill: parent
                    anchors.margins: 16
                    spacing: 8

                    Label {
                        text: LauncherApi.myUsername
                              ? qsTr("Your username: @%1").arg(LauncherApi.myUsername)
                              : qsTr("Create your username so friends can find you")
                        color: "#f8f5ff"
                        font.bold: true
                        wrapMode: Text.WordWrap
                        Layout.fillWidth: true
                    }
                    RowLayout {
                        Layout.fillWidth: true
                        TextField {
                            id: usernameField
                            Layout.fillWidth: true
                            placeholderText: qsTr("your_username")
                            text: LauncherApi.myUsername
                            maximumLength: 20
                            onTextChanged: {
                                var clean = text.toLowerCase().replace(/[^a-z0-9_]/g, "")
                                if (clean !== text) text = clean
                                usernameCheckLabel.visible = false
                            }
                        }
                        Button {
                            text: qsTr("Check")
                            enabled: usernameField.text.length >= 3 && !LauncherApi.busy
                            onClicked: LauncherApi.checkUsername(usernameField.text)
                        }
                        Button {
                            text: LauncherApi.myUsername ? qsTr("Update") : qsTr("Create")
                            highlighted: true
                            enabled: usernameField.text.length >= 3 && !LauncherApi.busy
                            onClicked: LauncherApi.setUsername(usernameField.text)
                        }
                    }
                    Label {
                        id: usernameCheckLabel
                        visible: false
                        font.pixelSize: 12
                        Layout.fillWidth: true
                    }
                }
            }

            Rectangle {
                Layout.fillWidth: true
                implicitHeight: addColumn.implicitHeight + 32
                radius: 12
                color: "#110d17"
                border.width: 1
                border.color: "#e8e2ff"

                ColumnLayout {
                    id: addColumn
                    anchors.fill: parent
                    anchors.margins: 16
                    spacing: 8

                    Label {
                        text: qsTr("Add a friend by username")
                        color: "#f8f5ff"
                        font.bold: true
                    }
                    RowLayout {
                        Layout.fillWidth: true
                        TextField {
                            id: addFriendField
                            Layout.fillWidth: true
                            placeholderText: qsTr("friend_username")
                            maximumLength: 20
                            onTextChanged: {
                                var clean = text.toLowerCase().replace(/[^a-z0-9_]/g, "")
                                if (clean !== text) text = clean
                            }
                            Keys.onReturnPressed: addFriendButton.clicked()
                        }
                        Button {
                            id: addFriendButton
                            text: qsTr("Add")
                            highlighted: true
                            enabled: addFriendField.text.length >= 3 && !LauncherApi.busy
                            onClicked: {
                                LauncherApi.addFriend(addFriendField.text)
                                addFriendField.text = ""
                            }
                        }
                    }
                }
            }

            ColumnLayout {
                Layout.fillWidth: true
                spacing: 8
                visible: LauncherApi.incomingRequests.length > 0

                Label {
                    text: qsTr("Friend requests")
                    color: "#f8f5ff"
                    font.bold: true
                }
                Repeater {
                    model: LauncherApi.incomingRequests
                    delegate: Rectangle {
                        Layout.fillWidth: true
                        implicitHeight: 56
                        radius: 10
                        color: "#1a1426"
                        RowLayout {
                            anchors.fill: parent
                            anchors.margins: 10
                            Label {
                                text: friendsView.friendLabel(modelData)
                                color: "#f8f5ff"
                                Layout.fillWidth: true
                            }
                            Button {
                                text: qsTr("Accept")
                                highlighted: true
                                onClicked: LauncherApi.acceptFriendRequest(modelData.requestId)
                            }
                            Button {
                                text: qsTr("Decline")
                                onClicked: LauncherApi.declineFriendRequest(modelData.requestId)
                            }
                        }
                    }
                }
            }

            ColumnLayout {
                Layout.fillWidth: true
                spacing: 4
                visible: LauncherApi.outgoingRequests.length > 0
                Label {
                    text: qsTr("Sent requests")
                    color: "#f8f5ff"
                    font.bold: true
                }
                Repeater {
                    model: LauncherApi.outgoingRequests
                    delegate: Label {
                        text: friendsView.friendLabel(modelData) + " — " + qsTr("waiting")
                        color: "#9793aa"
                        Layout.fillWidth: true
                    }
                }
            }

            ColumnLayout {
                Layout.fillWidth: true
                spacing: 8
                Label {
                    text: qsTr("Your friends")
                    color: "#f8f5ff"
                    font.bold: true
                }
                Label {
                    visible: LauncherApi.friends.length === 0
                    text: qsTr("No friends yet — add someone above!")
                    color: "#9793aa"
                    Layout.fillWidth: true
                }
                Repeater {
                    model: LauncherApi.friends
                    delegate: Rectangle {
                        Layout.fillWidth: true
                        implicitHeight: friendColumn.implicitHeight + 24
                        radius: 10
                        color: "#1a1426"
                        ColumnLayout {
                            id: friendColumn
                            anchors.fill: parent
                            anchors.margins: 12
                            spacing: 8
                            RowLayout {
                                Layout.fillWidth: true
                                Label {
                                    text: friendsView.friendLabel(modelData)
                                    color: "#f8f5ff"
                                    font.bold: true
                                    Layout.fillWidth: true
                                }
                                Button {
                                    text: qsTr("Remove")
                                    onClicked: LauncherApi.removeFriend(modelData.userId)
                                }
                            }
                            RowLayout {
                                Layout.fillWidth: true
                                spacing: 8
                                Button {
                                    text: modelData.showMachine
                                          ? qsTr("Show my machine: ON")
                                          : qsTr("Show my machine: OFF")
                                    highlighted: modelData.showMachine
                                    Layout.fillWidth: true
                                    onClicked: LauncherApi.setFriendPermissions(
                                        modelData.userId, !modelData.showMachine,
                                        !modelData.showMachine ? modelData.allowConnect : false)
                                }
                                Button {
                                    enabled: modelData.showMachine
                                    text: modelData.allowConnect
                                          ? qsTr("Can connect: ON")
                                          : qsTr("Can connect: OFF")
                                    highlighted: modelData.allowConnect
                                    Layout.fillWidth: true
                                    onClicked: LauncherApi.setFriendPermissions(
                                        modelData.userId, modelData.showMachine, !modelData.allowConnect)
                                }
                            }
                        }
                    }
                }
            }

            ColumnLayout {
                Layout.fillWidth: true
                spacing: 8
                visible: LauncherApi.friendMachines.length > 0

                Label {
                    text: qsTr("Machines shared with you")
                    color: "#f8f5ff"
                    font.bold: true
                }
                Repeater {
                    model: LauncherApi.friendMachines
                    delegate: Rectangle {
                        Layout.fillWidth: true
                        implicitHeight: 64
                        radius: 10
                        color: "#1a1426"
                        RowLayout {
                            anchors.fill: parent
                            anchors.margins: 12
                            ColumnLayout {
                                Layout.fillWidth: true
                                spacing: 2
                                Label {
                                    text: modelData.name || qsTr("Machine")
                                    color: "#f8f5ff"
                                    font.bold: true
                                }
                                Label {
                                    text: qsTr("from %1 · %2")
                                        .arg(modelData.owner && modelData.owner.username
                                             ? "@" + modelData.owner.username
                                             : (modelData.owner ? modelData.owner.name : ""))
                                        .arg(modelData.running ? qsTr("on") : qsTr("off"))
                                    color: "#9793aa"
                                    font.pixelSize: 11
                                }
                            }
                            Button {
                                enabled: modelData.canConnect && !LauncherApi.busy
                                highlighted: modelData.canConnect
                                text: modelData.canConnect ? qsTr("Connect") : qsTr("No access")
                                onClicked: LauncherApi.connectFriendMachine(modelData.machineId)
                            }
                        }
                    }
                }
                Label {
                    text: qsTr("Friend machines need to be running for you to connect — ask your friend to open it first.")
                    color: "#9793aa"
                    font.pixelSize: 11
                    wrapMode: Text.WordWrap
                    Layout.fillWidth: true
                }
            }
        }
    }

    Dialog {
        id: friendResultDialog
        property bool isError: false
        property alias text: friendResultLabel.text
        title: isError ? qsTr("Action failed") : qsTr("Done")
        modal: true
        parent: Overlay.overlay
        x: Math.round((parent.width - width) / 2)
        y: Math.round((parent.height - height) / 2)
        standardButtons: Dialog.Ok

        Label {
            id: friendResultLabel
            wrapMode: Text.WordWrap
            width: 300
        }
    }

    Dialog {
        id: errorDialog
        title: qsTr("Connection failed")
        standardButtons: Dialog.Ok
        modal: true
        parent: Overlay.overlay
        x: Math.round((parent.width - width) / 2)
        y: Math.round((parent.height - height) / 2)
        Label {
            text: qsTr("The Moonlight host is not ready yet. Try again in a few seconds.")
            wrapMode: Text.WordWrap
            width: 300
        }
    }
}
