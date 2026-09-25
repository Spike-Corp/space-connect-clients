import QtQuick 2.9
import QtQuick.Controls 2.3
import QtQuick.Layouts 1.3
import LauncherApi 1.0
import ComputerManager 1.0

// Sistema de amigos (beta) — estilo Parsec: username, pedidos, permissões
// (global ou por máquina) e conectar nas VMs que amigos compartilham comigo.
// Mesma base do site (Social) — /launcher/v1/friends.
Item {
    id: friendsView
    objectName: qsTr("Friends")
    property bool addingComputer: false
    property string friendConnectAddress: ""
    // Nome que o dono deu à VM — aplicado no PcView por cima do SCG-VMF.
    property string friendConnectName: ""

    Component.onCompleted: LauncherApi.refreshFriends()

    Connections {
        target: LauncherApi
        function onFriendActionResult(success, message) {
            friendResultDialog.isError = !success
            friendResultDialog.text = message
            friendResultDialog.open()
        }
        function onConnectionReady(address, machineId, name) {
            addingComputer = true
            friendConnectAddress = address
            friendConnectName = name || ""
            ComputerManager.addNewHostManually(address)
        }
        function onUsernameCheckResult(available, reason) {
            usernameCheckLabel.visible = true
            usernameCheckLabel.color = available ? "#4ade80" : "#F87171"
            usernameCheckLabel.text = available
                ? qsTr("Available!")
                : (reason === "taken" ? qsTr("Already in use") : qsTr("Invalid format"))
        }
    }

    Connections {
        target: ComputerManager
        function onComputerAddCompleted(success, detectedPortBlocking) {
            if (!addingComputer)
                return
            addingComputer = false
            if (success) {
                // Abre DIRETO o PC do amigo (PcView abre o AppView pelo endereço,
                // pareando se precisar) — antes caía na grade e o usuário clicava
                // no PRÓPRIO PC por engano.
                // push (e nao replace) pra manter a pilha e o botao de voltar.
                stackView.push("qrc:/gui/PcView.qml", {
                    "autoOpenAddress": friendConnectAddress,
                    "autoAddress": friendConnectAddress,
                    "autoName": friendConnectName
                })
            }
            else
                errorDialog.open()
        }
    }

    function friendLabel(entry) {
        return entry.username ? "@" + entry.username : (entry.name || qsTr("No name"))
    }

    // Card de UM amigo — escopo próprio (lê o modelData dele).
    component FriendCard: Rectangle {
        id: card
        property var friend: modelData

        Layout.fillWidth: true
        implicitHeight: friendCol.implicitHeight + 24
        radius: 10
        color: "#1a1426"

        ColumnLayout {
            id: friendCol
            anchors.fill: parent
            anchors.margins: 12
            spacing: 8

            RowLayout {
                Layout.fillWidth: true
                Label {
                    text: friendsView.friendLabel(card.friend)
                    color: "#f8f5ff"
                    font.bold: true
                    Layout.fillWidth: true
                }
                Button {
                    text: qsTr("Remove")
                    onClicked: LauncherApi.removeFriend(card.friend.userId)
                }
            }

            RowLayout {
                Layout.fillWidth: true
                spacing: 8
                Button {
                    text: card.friend.showMachine ? qsTr("Show my machine: ON") : qsTr("Show my machine: OFF")
                    highlighted: card.friend.showMachine
                    Layout.fillWidth: true
                    onClicked: LauncherApi.setFriendPermissions(
                        card.friend.userId, !card.friend.showMachine,
                        !card.friend.showMachine ? card.friend.allowConnect : false)
                }
                Button {
                    enabled: card.friend.showMachine
                    text: card.friend.allowConnect ? qsTr("Can connect: ON") : qsTr("Can connect: OFF")
                    highlighted: card.friend.allowConnect
                    Layout.fillWidth: true
                    onClicked: LauncherApi.setFriendPermissions(
                        card.friend.userId, card.friend.showMachine, !card.friend.allowConnect)
                }
            }

            // Por máquina (override vence o global) — só quando há 2+ VMs minhas.
            ColumnLayout {
                Layout.fillWidth: true
                spacing: 4
                visible: LauncherApi.myMachines.length > 1

                Label {
                    text: qsTr("Per machine:")
                    color: "#9793aa"
                    font.pixelSize: 11
                }
                Repeater {
                    model: LauncherApi.myMachines
                    delegate: RowLayout {
                        id: machineRow
                        property var machine: modelData
                        property var per: (card.friend.perMachine && card.friend.perMachine[machine.machineId])
                            ? card.friend.perMachine[machine.machineId] : null
                        property bool showOn: per && per.showMachine !== undefined
                            ? per.showMachine : card.friend.showMachine
                        property bool connectOn: per && per.allowConnect !== undefined
                            ? per.allowConnect : card.friend.allowConnect

                        Layout.fillWidth: true
                        spacing: 6
                        Label {
                            text: (machine.name || qsTr("Machine")) + (machine.running ? "" : " (" + qsTr("off") + ")")
                            color: "#f8f5ff"
                            font.pixelSize: 11
                            Layout.fillWidth: true
                            elide: Text.ElideRight
                        }
                        Button {
                            text: machineRow.showOn ? qsTr("Show: ON") : qsTr("Show: OFF")
                            font.pixelSize: 10
                            onClicked: LauncherApi.setFriendMachinePermission(
                                card.friend.userId, machine.machineId, !machineRow.showOn, false)
                        }
                        Button {
                            enabled: machineRow.showOn
                            text: machineRow.connectOn ? qsTr("Connect: ON") : qsTr("Connect: OFF")
                            font.pixelSize: 10
                            onClicked: LauncherApi.setFriendMachinePermission(
                                card.friend.userId, machine.machineId, true, !machineRow.connectOn)
                        }
                    }
                }
            }
        }
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
                Label { text: qsTr("FRIENDS"); color: "#a482fa"; font.pixelSize: 24; font.bold: true }
                Label { text: "BETA"; color: "#4572fa"; font.pixelSize: 11; font.bold: true; Layout.alignment: Qt.AlignTop }
                Item { Layout.fillWidth: true }
                Button { text: qsTr("Refresh"); onClicked: LauncherApi.refreshFriends() }
            }

            // username
            Rectangle {
                Layout.fillWidth: true
                implicitHeight: userCol.implicitHeight + 32
                radius: 12
                color: "#110d17"
                border.width: 1
                border.color: "#e8e2ff"
                ColumnLayout {
                    id: userCol
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
                    Label { id: usernameCheckLabel; visible: false; font.pixelSize: 12; Layout.fillWidth: true }
                }
            }

            // adicionar
            Rectangle {
                Layout.fillWidth: true
                implicitHeight: addCol.implicitHeight + 32
                radius: 12
                color: "#110d17"
                border.width: 1
                border.color: "#e8e2ff"
                ColumnLayout {
                    id: addCol
                    anchors.fill: parent
                    anchors.margins: 16
                    spacing: 8
                    Label { text: qsTr("Add a friend by username"); color: "#f8f5ff"; font.bold: true }
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
                            onClicked: { LauncherApi.addFriend(addFriendField.text); addFriendField.text = "" }
                        }
                    }
                }
            }

            // pedidos recebidos
            ColumnLayout {
                Layout.fillWidth: true
                spacing: 8
                visible: LauncherApi.incomingRequests.length > 0
                Label { text: qsTr("Friend requests"); color: "#f8f5ff"; font.bold: true }
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
                            Button { text: qsTr("Accept"); highlighted: true; onClicked: LauncherApi.acceptFriendRequest(modelData.requestId) }
                            Button { text: qsTr("Decline"); onClicked: LauncherApi.declineFriendRequest(modelData.requestId) }
                        }
                    }
                }
            }

            // pedidos enviados
            ColumnLayout {
                Layout.fillWidth: true
                spacing: 4
                visible: LauncherApi.outgoingRequests.length > 0
                Label { text: qsTr("Sent requests"); color: "#f8f5ff"; font.bold: true }
                Repeater {
                    model: LauncherApi.outgoingRequests
                    delegate: Label {
                        text: friendsView.friendLabel(modelData) + " — " + qsTr("waiting")
                        color: "#9793aa"
                        Layout.fillWidth: true
                    }
                }
            }

            // amigos
            ColumnLayout {
                Layout.fillWidth: true
                spacing: 8
                Label { text: qsTr("Your friends"); color: "#f8f5ff"; font.bold: true }
                Label {
                    visible: LauncherApi.friends.length === 0
                    text: qsTr("No friends yet, add someone above!")
                    color: "#9793aa"
                    Layout.fillWidth: true
                }
                Repeater {
                    model: LauncherApi.friends
                    delegate: FriendCard {}
                }
            }

            // máquinas compartilhadas comigo
            ColumnLayout {
                Layout.fillWidth: true
                spacing: 8
                visible: LauncherApi.friendMachines.length > 0
                Label { text: qsTr("Machines shared with you"); color: "#f8f5ff"; font.bold: true }
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
                                Label { text: modelData.name || qsTr("Machine"); color: "#f8f5ff"; font.bold: true }
                                Label {
                                    text: qsTr("from %1 · %2")
                                        .arg(modelData.owner && modelData.owner.username ? "@" + modelData.owner.username : (modelData.owner ? modelData.owner.name : ""))
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
                    text: qsTr("Friend machines need to be running for you to connect, ask your friend to open it first.")
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
        Label { id: friendResultLabel; wrapMode: Text.WordWrap; width: 300 }
    }

    Dialog {
        id: errorDialog
        title: qsTr("Connection failed")
        standardButtons: Dialog.Ok
        modal: true
        parent: Overlay.overlay
        x: Math.round((parent.width - width) / 2)
        y: Math.round((parent.height - height) / 2)
        Label { text: qsTr("The Moonlight host is not ready yet. Try again in a few seconds."); wrapMode: Text.WordWrap; width: 300 }
    }
}
