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
            // VM de amigo é marcada como "friend" — nunca é removida pela
            // limpeza automática de PCs obsoletos da conta.
            ComputerManager.addNewHostForMachine(address, machineId || "", "friend")
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
        return entry && entry.username ? "@" + entry.username
             : (entry && entry.name ? entry.name : qsTr("No name"))
    }

    function profileName(entry) {
        return entry && entry.name ? entry.name : friendLabel(entry || {})
    }

    function profileUsername(entry) {
        return entry && entry.username ? "@" + entry.username : ""
    }

    function searchActionLabel(relationship, profile) {
        if (relationship === "none" && profile && profile.acceptsFriendRequests === false)
            return qsTr("Does not accept friend requests")
        if (relationship === "self") return qsTr("This is you")
        if (relationship === "friends") return qsTr("Already friends")
        if (relationship === "outgoing") return qsTr("Request sent")
        if (relationship === "incoming") return qsTr("Accept friendship")
        return qsTr("Add friend")
    }

    component StaffBadge: Rectangle {
        id: staffBadge
        property string role: ""
        property color badgeColor: "#ff2bd6"

        visible: role.length > 0
        implicitWidth: staffBadgeLabel.implicitWidth + 18
        implicitHeight: 22
        radius: 11
        color: "#130d1b"
        border.width: 2
        border.color: badgeColor

        SequentialAnimation {
            running: staffBadge.visible
            loops: Animation.Infinite
            ColorAnimation { target: staffBadge; property: "badgeColor"; from: "#ff2bd6"; to: "#00e5ff"; duration: 850 }
            ColorAnimation { target: staffBadge; property: "badgeColor"; from: "#00e5ff"; to: "#7c3aed"; duration: 850 }
            ColorAnimation { target: staffBadge; property: "badgeColor"; from: "#7c3aed"; to: "#ffea00"; duration: 850 }
            ColorAnimation { target: staffBadge; property: "badgeColor"; from: "#ffea00"; to: "#00ff85"; duration: 850 }
            ColorAnimation { target: staffBadge; property: "badgeColor"; from: "#00ff85"; to: "#ff2bd6"; duration: 850 }
        }

        Label {
            id: staffBadgeLabel
            anchors.centerIn: parent
            text: parent.role.toUpperCase()
            color: parent.badgeColor
            font.bold: true
            font.pixelSize: 9
            font.letterSpacing: 1
        }
    }

    component ProfileAvatar: Item {
        id: avatarRoot
        property var profile
        property int avatarSize: 44
        property string avatarUrl: (profile && profile.avatarUrl) ? String(profile.avatarUrl) : ""

        width: avatarSize
        height: avatarSize
        implicitWidth: avatarSize
        implicitHeight: avatarSize

        function initials() {
            var label = profile && profile.name
                ? String(profile.name)
                : (profile && profile.username ? String(profile.username) : "?")
            var parts = label.trim().split(/\s+/)
            if (!parts.length || !parts[0]) return "?"
            if (parts.length === 1) return parts[0].substring(0, 2).toUpperCase()
            return (parts[0].charAt(0) + parts[parts.length - 1].charAt(0)).toUpperCase()
        }

        Canvas {
            id: avatarCanvas
            anchors.fill: parent
            antialiasing: true

            // Carrega a foto pela API nativa do Canvas (download próprio, sem
            // depender de um item Image invisível — que no Qt 6 podia desenhar
            // preto/vazio no drawImage). Funciona igual no Qt 5.15 e 6.7.
            onImageLoaded: {
                if (avatarRoot.avatarUrl && isImageLoaded(avatarRoot.avatarUrl)) {
                    requestPaint()
                }
            }

            onPaint: {
                var ctx = getContext("2d")
                ctx.clearRect(0, 0, width, height)
                ctx.save()
                ctx.beginPath()
                ctx.arc(width / 2, height / 2, Math.min(width, height) / 2, 0, Math.PI * 2)
                ctx.closePath()
                ctx.clip()

                if (avatarRoot.avatarUrl && isImageLoaded(avatarRoot.avatarUrl)) {
                    ctx.drawImage(avatarRoot.avatarUrl, 0, 0, width, height)
                } else {
                    var gradient = ctx.createLinearGradient(0, 0, width, height)
                    gradient.addColorStop(0, "#5b8cff")
                    gradient.addColorStop(1, "#9b5cff")
                    ctx.fillStyle = gradient
                    ctx.fillRect(0, 0, width, height)
                    ctx.fillStyle = "#ffffff"
                    ctx.font = "bold " + Math.max(11, Math.round(width * 0.34)) + "px sans-serif"
                    ctx.textAlign = "center"
                    ctx.textBaseline = "middle"
                    ctx.fillText(avatarRoot.initials(), width / 2, height / 2 + 1)
                }
                ctx.restore()
            }
        }

        function loadAvatar() {
            if (avatarUrl) {
                avatarCanvas.loadImage(avatarUrl)
            }
            avatarCanvas.requestPaint()
        }

        onAvatarUrlChanged: loadAvatar()
        Component.onCompleted: loadAvatar()

        Rectangle {
            anchors.fill: parent
            radius: width / 2
            color: "transparent"
            border.width: 1
            border.color: "#5c5070"
        }
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
                spacing: 10
                ProfileAvatar {
                    profile: card.friend
                    avatarSize: 44
                }
                ColumnLayout {
                    Layout.fillWidth: true
                    spacing: 1
                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 6
                        Label {
                            text: friendsView.profileName(card.friend)
                            color: "#f8f5ff"
                            font.bold: true
                            elide: Text.ElideRight
                            Layout.fillWidth: true
                        }
                        StaffBadge { role: card.friend.staffRole || "" }
                    }
                    Label {
                        text: friendsView.profileUsername(card.friend)
                        visible: text.length > 0 && text !== friendsView.profileName(card.friend)
                        color: "#9793aa"
                        font.pixelSize: 11
                        elide: Text.ElideRight
                        Layout.fillWidth: true
                    }
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
                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 12
                        ProfileAvatar {
                            profile: LauncherApi.myProfile
                            avatarSize: 52
                        }
                        ColumnLayout {
                            Layout.fillWidth: true
                            spacing: 2
                            Label {
                                text: LauncherApi.myProfile && LauncherApi.myProfile.name
                                      ? LauncherApi.myProfile.name
                                      : qsTr("Your profile")
                                color: "#f8f5ff"
                                font.bold: true
                                elide: Text.ElideRight
                                Layout.fillWidth: true
                            }
                            Label {
                                text: LauncherApi.myUsername
                                      ? qsTr("Your username: @%1").arg(LauncherApi.myUsername)
                                      : qsTr("Create your username so friends can find you")
                                color: "#9793aa"
                                font.pixelSize: 12
                                elide: Text.ElideRight
                                Layout.fillWidth: true
                            }
                            StaffBadge {
                                role: LauncherApi.myProfile && LauncherApi.myProfile.staffRole
                                      ? LauncherApi.myProfile.staffRole : ""
                            }
                        }
                    }
                    Button {
                        visible: LauncherApi.myProfile && LauncherApi.myProfile.staffRole
                        text: LauncherApi.myProfile && LauncherApi.myProfile.acceptsFriendRequests === false
                              ? qsTr("Receive requests: OFF")
                              : qsTr("Receive requests: ON")
                        highlighted: LauncherApi.myProfile
                                     && LauncherApi.myProfile.acceptsFriendRequests !== false
                        onClicked: LauncherApi.setFriendRequestSettings(
                            LauncherApi.myProfile.acceptsFriendRequests === false)
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
                                LauncherApi.clearFriendSearch()
                            }
                            Keys.onReturnPressed: addFriendButton.clicked()
                        }
                        Button {
                            id: addFriendButton
                            text: qsTr("Search")
                            highlighted: true
                            enabled: addFriendField.text.length >= 3 && !LauncherApi.busy
                            onClicked: LauncherApi.searchFriend(addFriendField.text)
                        }
                    }
                    Rectangle {
                        Layout.fillWidth: true
                        implicitHeight: searchResultRow.implicitHeight + 24
                        radius: 12
                        color: "#1a1426"
                        border.width: 1
                        border.color: "#6c54a3"
                        visible: LauncherApi.friendSearchResult
                                 && LauncherApi.friendSearchResult.profile !== undefined
                        RowLayout {
                            id: searchResultRow
                            anchors.fill: parent
                            anchors.margins: 12
                            spacing: 12
                            ProfileAvatar {
                                profile: LauncherApi.friendSearchResult.profile
                                avatarSize: 52
                            }
                            ColumnLayout {
                                Layout.fillWidth: true
                                spacing: 2
                                RowLayout {
                                    Layout.fillWidth: true
                                    spacing: 6
                                    Label {
                                        text: LauncherApi.friendSearchResult.profile
                                              ? friendsView.profileName(LauncherApi.friendSearchResult.profile)
                                              : ""
                                        color: "#f8f5ff"
                                        font.bold: true
                                        elide: Text.ElideRight
                                        Layout.fillWidth: true
                                    }
                                    StaffBadge {
                                        role: LauncherApi.friendSearchResult.profile
                                              && LauncherApi.friendSearchResult.profile.staffRole
                                              ? LauncherApi.friendSearchResult.profile.staffRole : ""
                                    }
                                }
                                Label {
                                    text: LauncherApi.friendSearchResult.profile
                                          ? friendsView.profileUsername(LauncherApi.friendSearchResult.profile)
                                          : ""
                                    visible: text.length > 0
                                             && text !== friendsView.profileName(LauncherApi.friendSearchResult.profile)
                                    color: "#b4adc5"
                                    font.pixelSize: 11
                                    elide: Text.ElideRight
                                    Layout.fillWidth: true
                                }
                                Label {
                                    text: LauncherApi.friendSearchResult.relationship === "none"
                                          && LauncherApi.friendSearchResult.profile
                                          && LauncherApi.friendSearchResult.profile.acceptsFriendRequests === false
                                          ? qsTr("This user does not accept friend requests.")
                                          : qsTr("Check the photo and profile before adding.")
                                    color: "#9793aa"
                                    font.pixelSize: 11
                                    wrapMode: Text.WordWrap
                                    Layout.fillWidth: true
                                }
                            }
                            Button {
                                text: friendsView.searchActionLabel(
                                    LauncherApi.friendSearchResult.relationship,
                                    LauncherApi.friendSearchResult.profile)
                                highlighted: (LauncherApi.friendSearchResult.relationship === "none"
                                              && LauncherApi.friendSearchResult.profile
                                              && LauncherApi.friendSearchResult.profile.acceptsFriendRequests !== false)
                                             || LauncherApi.friendSearchResult.relationship === "incoming"
                                enabled: highlighted && !LauncherApi.busy
                                onClicked: {
                                    LauncherApi.addFriend(LauncherApi.friendSearchResult.profile.username)
                                    addFriendField.text = ""
                                }
                            }
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
                        implicitHeight: modelData.staffRole ? 84 : 70
                        radius: 10
                        color: "#1a1426"
                        RowLayout {
                            anchors.fill: parent
                            anchors.margins: 10
                            spacing: 10
                            ProfileAvatar {
                                profile: modelData
                                avatarSize: 44
                            }
                            ColumnLayout {
                                Layout.fillWidth: true
                                spacing: 1
                                RowLayout {
                                    Layout.fillWidth: true
                                    spacing: 6
                                    Label {
                                        text: friendsView.profileName(modelData)
                                        color: "#f8f5ff"
                                        font.bold: true
                                        elide: Text.ElideRight
                                        Layout.fillWidth: true
                                    }
                                    StaffBadge { role: modelData.staffRole || "" }
                                }
                                Label {
                                    text: friendsView.profileUsername(modelData)
                                    visible: text.length > 0
                                             && text !== friendsView.profileName(modelData)
                                    color: "#9793aa"
                                    font.pixelSize: 11
                                    elide: Text.ElideRight
                                    Layout.fillWidth: true
                                }
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
                    delegate: Rectangle {
                        Layout.fillWidth: true
                        implicitHeight: modelData.staffRole ? 74 : 58
                        radius: 10
                        color: "#1a1426"
                        RowLayout {
                            anchors.fill: parent
                            anchors.margins: 8
                            spacing: 10
                            ProfileAvatar {
                                profile: modelData
                                avatarSize: 40
                            }
                            ColumnLayout {
                                Layout.fillWidth: true
                                spacing: 1
                                RowLayout {
                                    Layout.fillWidth: true
                                    spacing: 6
                                    Label {
                                        text: friendsView.profileName(modelData)
                                        color: "#f8f5ff"
                                        font.bold: true
                                        elide: Text.ElideRight
                                        Layout.fillWidth: true
                                    }
                                    StaffBadge { role: modelData.staffRole || "" }
                                }
                                Label {
                                    text: (friendsView.profileUsername(modelData)
                                           && friendsView.profileUsername(modelData) !== friendsView.profileName(modelData)
                                           ? friendsView.profileUsername(modelData) + " · "
                                           : "") + qsTr("waiting")
                                    color: "#9793aa"
                                    font.pixelSize: 11
                                    elide: Text.ElideRight
                                    Layout.fillWidth: true
                                }
                            }
                        }
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
                        implicitHeight: modelData.owner && modelData.owner.staffRole ? 92 : 72
                        radius: 10
                        color: "#1a1426"
                        RowLayout {
                            anchors.fill: parent
                            anchors.margins: 12
                            spacing: 10
                            ProfileAvatar {
                                profile: modelData.owner
                                avatarSize: 46
                            }
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
                                StaffBadge {
                                    role: modelData.owner && modelData.owner.staffRole
                                          ? modelData.owner.staffRole : ""
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
        title: qsTr("Falha na conexão")
        standardButtons: Dialog.Ok
        modal: true
        parent: Overlay.overlay
        x: Math.round((parent.width - width) / 2)
        y: Math.round((parent.height - height) / 2)
        Label { text: qsTr("Não foi possível alcançar o host Moonlight. Tente novamente em alguns segundos."); wrapMode: Text.WordWrap; width: 300 }
    }
}
