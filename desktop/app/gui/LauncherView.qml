import QtQuick 2.9
import QtQuick.Controls 2.2
import QtQuick.Layouts 1.3
import LauncherApi 1.0
import ComputerManager 1.0

Item {
    id: launcherView
    objectName: qsTr("SpaceCloud")

    // SpaceCloud beta tokens: one source of truth for the native experience.
    readonly property color ink: "#F5F7FF"
    readonly property color muted: "#8D96AA"
    readonly property color dim: "#5D667A"
    readonly property color canvas: "#080B12"
    readonly property color panel: "#101522"
    readonly property color panelRaised: "#171E2D"
    readonly property color line: "#263044"
    readonly property color accent: "#7C9CFF"
    readonly property color accentStrong: "#A8BCFF"
    readonly property color success: "#73E0B0"
    property bool addingComputer: false
    property string crimsonConnectionAddress: ""
    property string activeFilter: "todos"

    function crimsonText() {
        if (LauncherApi.crimsonState === "ready") return qsTr("Jogar agora")
        if (LauncherApi.crimsonState === "error") return qsTr("Tentar de novo")
        return qsTr("Jogar na nuvem")
    }

    function isLoading() {
        return LauncherApi.state === "queued"
                || (LauncherApi.crimsonState !== "idle"
                    && LauncherApi.crimsonState !== "ready"
                    && LauncherApi.crimsonState !== "error")
    }

    function loadingTitle() {
        if (LauncherApi.state === "queued") return qsTr("Estamos encontrando uma máquina")
        if (LauncherApi.crimsonState === "provisioning") return qsTr("Ligando sua máquina")
        if (LauncherApi.crimsonState === "logging-in") return qsTr("Preparando seu acesso")
        if (LauncherApi.crimsonState === "downloading") return qsTr("Preparando o jogo")
        if (LauncherApi.crimsonState === "launching") return qsTr("Abrindo o jogo")
        return qsTr("Conectando à sua sessão")
    }

    function loadingDetails() {
        if (LauncherApi.state === "queued")
            return qsTr("Posição %1 de %2 na fila").arg(LauncherApi.queuePosition).arg(LauncherApi.queueTotal)
        if (LauncherApi.crimsonState === "downloading")
            return qsTr("%1% pronto · o download fica salvo para a próxima vez").arg(LauncherApi.crimsonDownloadPercent)
        if (LauncherApi.machineName)
            return qsTr("%1 · cerca de %2 min restantes").arg(LauncherApi.machineName).arg(LauncherApi.remainingMinutes)
        return qsTr("Isso leva alguns minutos. Você pode acompanhar tudo por aqui.")
    }

    function progressValue() {
        if (LauncherApi.crimsonState === "downloading")
            return Math.max(0.04, LauncherApi.crimsonDownloadPercent / 100)
        if (LauncherApi.state === "queued") return 0.18
        if (LauncherApi.crimsonState === "provisioning") return 0.38
        if (LauncherApi.crimsonState === "logging-in") return 0.58
        if (LauncherApi.crimsonState === "launching") return 0.86
        return 0.26
    }

    Connections {
        target: LauncherApi
        function onConnectionReady(address) {
            addingComputer = true
            ComputerManager.addNewHostManually(address)
        }
        function onCrimsonConnectionReady(address) {
            addingComputer = true
            crimsonConnectionAddress = address
            ComputerManager.addNewHostManually(address)
        }
        function onLoggedInChanged() {
            if (!LauncherApi.loggedIn) stackView.replace("qrc:/gui/LoginView.qml")
        }
    }

    Connections {
        target: ComputerManager
        function onComputerAddCompleted(success, detectedPortBlocking) {
            if (!addingComputer) return
            addingComputer = false
            if (success) {
                var computerIndex = ComputerManager.findComputerIndex(crimsonConnectionAddress)
                if (computerIndex < 0) {
                    errorDialog.open()
                    return
                }
                stackView.replace("qrc:/gui/AppView.qml", {
                    "computerIndex": computerIndex,
                    "showHiddenGames": false,
                    "showGames": false,
                    "directLaunchAppName": "Crimson Desert"
                })
            } else {
                errorDialog.open()
            }
        }
    }

    Timer {
        interval: 5000
        repeat: true
        running: true
        triggeredOnStart: true
        onTriggered: LauncherApi.refreshStatus()
    }

    Rectangle { anchors.fill: parent; color: launcherView.canvas }

    RowLayout {
        anchors.fill: parent
        spacing: 0

        // Compact navigation keeps the game artwork and action dominant.
        Rectangle {
            Layout.fillHeight: true
            Layout.preferredWidth: 76
            color: "#0B0F18"
            border.color: "#182133"
            border.width: 1

            Column {
                anchors.fill: parent
                anchors.topMargin: 24
                anchors.bottomMargin: 20
                spacing: 12

                Rectangle {
                    width: 38
                    height: 38
                    radius: 12
                    anchors.horizontalCenter: parent.horizontalCenter
                    color: launcherView.accent
                    Label {
                        anchors.centerIn: parent
                        text: "S"
                        color: "#08101F"
                        font.pixelSize: 20
                        font.bold: true
                    }
                }

                Rectangle { width: 1; height: 24; color: launcherView.line; anchors.horizontalCenter: parent.horizontalCenter }

                Loader { sourceComponent: navButtonComponent; property string icon: "⌂"; property string label: qsTr("Início"); property bool selected: true; width: 52; height: 52; anchors.horizontalCenter: parent.horizontalCenter }
                Loader { sourceComponent: navButtonComponent; property string icon: "▦"; property string label: qsTr("Biblioteca"); width: 52; height: 52; anchors.horizontalCenter: parent.horizontalCenter }
                Loader { sourceComponent: navButtonComponent; property string icon: "♡"; property string label: qsTr("Favoritos"); width: 52; height: 52; anchors.horizontalCenter: parent.horizontalCenter }

                Item { width: 1; height: 1; anchors.topMargin: 4 }

                Loader { sourceComponent: navButtonComponent; property string icon: "⚙"; property string label: qsTr("Ajustes"); width: 52; height: 52; anchors.horizontalCenter: parent.horizontalCenter }

                Item { width: 1; height: 1 }

                Rectangle {
                    width: 38
                    height: 38
                    radius: 19
                    color: launcherView.panelRaised
                    anchors.horizontalCenter: parent.horizontalCenter
                    Label {
                        anchors.centerIn: parent
                        text: LauncherApi.email.length > 0 ? LauncherApi.email.charAt(0).toUpperCase() : "D"
                        color: launcherView.ink
                        font.bold: true
                    }
                }
            }
        }

        ColumnLayout {
            Layout.fillWidth: true
            Layout.fillHeight: true
            spacing: 0

            RowLayout {
                Layout.fillWidth: true
                Layout.preferredHeight: 76
                Layout.leftMargin: 34
                Layout.rightMargin: 34

                Label {
                    text: qsTr("Sua biblioteca")
                    color: launcherView.ink
                    font.pixelSize: 21
                    font.bold: true
                }
                Item { Layout.fillWidth: true }
                Rectangle {
                    Layout.preferredWidth: 270
                    Layout.preferredHeight: 38
                    radius: 19
                    color: launcherView.panel
                    border.color: launcherView.line
                    Row {
                        anchors.fill: parent
                        anchors.leftMargin: 14
                        spacing: 8
                        Label { text: "⌕"; color: launcherView.muted; font.pixelSize: 20; anchors.verticalCenter: parent.verticalCenter }
                        TextField {
                            width: 220
                            height: parent.height
                            placeholderText: qsTr("Buscar jogos")
                            placeholderTextColor: launcherView.dim
                            color: launcherView.ink
                            background: Item {}
                            font.pixelSize: 12
                        }
                    }
                }
                Button {
                    text: qsTr("Sair")
                    flat: true
                    contentItem: Text { text: parent.text; color: launcherView.muted; font.pixelSize: 12; horizontalAlignment: Text.AlignHCenter }
                    onClicked: LauncherApi.logout()
                }
            }

            Flickable {
                Layout.fillWidth: true
                Layout.fillHeight: true
                contentWidth: width
                contentHeight: pageColumn.implicitHeight + 52
                clip: true
                ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }

                ColumnLayout {
                    id: pageColumn
                    width: parent.width
                    spacing: 28

                    Rectangle {
                        Layout.fillWidth: true
                        Layout.preferredHeight: Math.min(360, Math.max(280, parent.width * 0.31))
                        Layout.leftMargin: 34
                        Layout.rightMargin: 34
                        radius: 18
                        clip: true
                        color: launcherView.panel

                        Image {
                            anchors.fill: parent
                            source: "https://cdn1.epicgames.com/spt-assets/f9616d900e1048a29ee9ffe9523c1594/crimson-desert-191ki.jpg"
                            fillMode: Image.PreserveAspectCrop
                            asynchronous: true
                        }
                        Rectangle {
                            anchors.fill: parent
                            gradient: Gradient {
                                GradientStop { position: 0.0; color: "#C9080B12" }
                                GradientStop { position: 0.55; color: "#B0080B12" }
                                GradientStop { position: 1.0; color: "#F0080B12" }
                            }
                        }
                        Column {
                            anchors.left: parent.left
                            anchors.leftMargin: 32
                            anchors.bottom: parent.bottom
                            anchors.bottomMargin: 30
                            width: Math.min(470, parent.width * 0.52)
                            spacing: 12
                            Label {
                                text: qsTr("DISPONÍVEL AGORA")
                                color: launcherView.accentStrong
                                font.pixelSize: 11
                                font.bold: true
                            }
                            Label {
                                text: "Crimson Desert"
                                color: launcherView.ink
                                font.pixelSize: 32
                                font.bold: true
                            }
                            Label {
                                text: qsTr("Uma máquina dedicada prepara o jogo e conecta você direto na partida.")
                                color: "#D8DEEC"
                                font.pixelSize: 13
                                wrapMode: Text.WordWrap
                                width: parent.width
                            }
                            Rectangle {
                                width: 154
                                height: 42
                                radius: 10
                                color: launcherView.accent
                                opacity: LauncherApi.busy ? 0.5 : 1
                                Text {
                                    anchors.centerIn: parent
                                    text: crimsonText()
                                    color: "#08101F"
                                    font.pixelSize: 12
                                    font.bold: true
                                }
                                MouseArea {
                                    anchors.fill: parent
                                    enabled: !LauncherApi.busy && !addingComputer
                                    onClicked: LauncherApi.startCrimsonDesert()
                                }
                            }
                        }
                    }

                    RowLayout {
                        Layout.fillWidth: true
                        Layout.leftMargin: 34
                        Layout.rightMargin: 34
                        spacing: 10
                        Label { text: qsTr("Jogos"); color: launcherView.ink; font.pixelSize: 20; font.bold: true }
                        Item { Layout.fillWidth: true }
                        Loader { sourceComponent: filterChipComponent; property string label: qsTr("Todos"); property bool active: activeFilter === "todos"; onLoaded: item.clicked.connect(function() { activeFilter = "todos" }) }
                        Loader { sourceComponent: filterChipComponent; property string label: qsTr("Disponíveis"); property bool active: activeFilter === "disponiveis"; onLoaded: item.clicked.connect(function() { activeFilter = "disponiveis" }) }
                        Loader { sourceComponent: filterChipComponent; property string label: qsTr("Em breve"); property bool active: activeFilter === "breve"; onLoaded: item.clicked.connect(function() { activeFilter = "breve" }) }
                    }

                    RowLayout {
                        Layout.fillWidth: true
                        Layout.leftMargin: 34
                        Layout.rightMargin: 34
                        spacing: 16

                        Repeater {
                            model: [
                                { title: "Crimson Desert", image: "https://cdn1.epicgames.com/spt-assets/f9616d900e1048a29ee9ffe9523c1594/crimson-desert-191ki.jpg", available: true },
                                { title: "Elden Ring", image: "https://metagalaxia.com.br/wp-content/uploads/2024/07/Elden-Ring-Shadow-of-the-Erdtree.webp", available: false },
                                { title: "Red Dead Redemption 2", image: "https://images7.alphacoders.com/749/thumb-1920-749807.png", available: false },
                                { title: "Cyberpunk 2077", image: "https://images2.alphacoders.com/131/thumb-1920-1317789.jpeg", available: false }
                            ]
                            delegate: Loader {
                                Layout.fillWidth: true
                                Layout.preferredHeight: 224
                                sourceComponent: gameCardComponent
                                property string title: modelData.title
                                property string imageSource: modelData.image
                                property bool available: modelData.available
                                property bool cardVisible: activeFilter === "todos"
                                                     || (activeFilter === "disponiveis" && modelData.available)
                                                     || (activeFilter === "breve" && !modelData.available)
                                onLoaded: {
                                    item.title = title
                                    item.imageSource = imageSource
                                    item.available = available
                                    item.play.connect(function() { LauncherApi.startCrimsonDesert() })
                                }
                            }
                        }
                    }

                    Rectangle {
                        Layout.fillWidth: true
                        Layout.leftMargin: 34
                        Layout.rightMargin: 34
                        Layout.preferredHeight: 68
                        radius: 12
                        color: launcherView.panel
                        border.color: launcherView.line
                        RowLayout {
                            anchors.fill: parent
                            anchors.leftMargin: 18
                            anchors.rightMargin: 18
                            spacing: 12
                            Label { text: "✦"; color: launcherView.success; font.pixelSize: 20 }
                            ColumnLayout {
                                Layout.fillWidth: true
                                Label { text: qsTr("Máquina dedicada"); color: launcherView.ink; font.bold: true; font.pixelSize: 12 }
                                Label { text: qsTr("GPU reservada para uma sessão por vez · encerra após 5 min sem atividade"); color: launcherView.muted; font.pixelSize: 11 }
                            }
                            Label { text: LauncherApi.errorMessage.length > 0 ? LauncherApi.errorMessage : qsTr("Pronto para jogar"); color: LauncherApi.errorMessage.length > 0 ? "#FF8D98" : launcherView.success; font.pixelSize: 11 }
                        }
                    }
                }
            }
        }
    }

    Rectangle {
        id: gameLoading
        anchors.fill: parent
        z: 20
        visible: launcherView.isLoading()
        color: launcherView.canvas

        Image {
            anchors.fill: parent
            source: "https://cdn1.epicgames.com/spt-assets/f9616d900e1048a29ee9ffe9523c1594/crimson-desert-191ki.jpg"
            fillMode: Image.PreserveAspectCrop
            opacity: 0.34
            asynchronous: true
        }
        Rectangle {
            anchors.fill: parent
            color: "#D9080B12"
        }
        Rectangle {
            anchors.centerIn: parent
            width: Math.min(570, parent.width - 48)
            height: 330
            radius: 18
            color: "#F0101624"
            border.color: "#445170"

            ColumnLayout {
                anchors.fill: parent
                anchors.margins: 32
                spacing: 16
                Label { text: qsTr("CRIMSON DESERT"); color: launcherView.accentStrong; font.pixelSize: 11; font.bold: true }
                Label { text: loadingTitle(); color: launcherView.ink; font.pixelSize: 27; font.bold: true; wrapMode: Text.WordWrap; Layout.fillWidth: true }
                Label { text: loadingDetails(); color: "#D0D7E7"; font.pixelSize: 13; Layout.fillWidth: true; wrapMode: Text.WordWrap }
                Item { Layout.fillHeight: true }
                RowLayout {
                    Layout.fillWidth: true
                    Label { text: qsTr("PROGRESSO"); color: launcherView.muted; font.pixelSize: 10; font.bold: true }
                    Item { Layout.fillWidth: true }
                    Label { text: LauncherApi.crimsonState === "downloading" ? LauncherApi.crimsonDownloadPercent + "%" : qsTr("Preparando"); color: launcherView.ink; font.pixelSize: 11 }
                }
                Rectangle {
                    Layout.fillWidth: true
                    height: 7
                    radius: 4
                    color: "#2A344A"
                    Rectangle { width: parent.width * progressValue(); height: parent.height; radius: 4; color: launcherView.accent }
                }
                Label { text: qsTr("Você será conectado automaticamente quando estiver pronto."); color: launcherView.muted; font.pixelSize: 11; Layout.fillWidth: true; wrapMode: Text.WordWrap }
            }
        }
    }

    Component {
        id: navButtonComponent
        Rectangle {
            property string icon: ""
            property string label: ""
            property bool selected: false
            width: 52
            height: 52
            radius: 12
            color: selected ? "#202B48" : "transparent"
            anchors.horizontalCenter: parent.horizontalCenter
            Column {
                anchors.centerIn: parent
                spacing: 2
                Label { anchors.horizontalCenter: parent.horizontalCenter; text: icon; color: selected ? launcherView.accentStrong : launcherView.muted; font.pixelSize: 18 }
                Label { anchors.horizontalCenter: parent.horizontalCenter; text: label; color: selected ? launcherView.ink : launcherView.dim; font.pixelSize: 8 }
            }
        }
    }

    Component {
        id: filterChipComponent
        Rectangle {
            property string label: ""
            property bool active: false
            signal clicked()
            width: chipText.implicitWidth + 26
            height: 32
            radius: 16
            color: active ? "#26365E" : launcherView.panel
            border.color: active ? launcherView.accent : launcherView.line
            Label { id: chipText; anchors.centerIn: parent; text: label; color: active ? launcherView.accentStrong : launcherView.muted; font.pixelSize: 11 }
            MouseArea { anchors.fill: parent; onClicked: parent.clicked() }
        }
    }

    Component {
        id: gameCardComponent
        Rectangle {
            property string title: ""
            property string imageSource: ""
            property bool available: false
            property bool cardVisible: true
            signal play()
            visible: cardVisible
            radius: 14
            color: launcherView.panel
            border.color: available ? "#34466F" : launcherView.line
            opacity: available ? 1 : 0.62
            clip: true
            Column {
                anchors.fill: parent
                spacing: 0
                Image { width: parent.width; height: 142; source: imageSource; fillMode: Image.PreserveAspectCrop; asynchronous: true; opacity: available ? 1 : 0.5 }
                Item {
                    width: parent.width
                    height: parent.height - 142
                    Label { anchors.left: parent.left; anchors.leftMargin: 12; anchors.top: parent.top; anchors.topMargin: 10; text: title; color: launcherView.ink; font.pixelSize: 12; font.bold: true; elide: Text.ElideRight; width: parent.width - 24 }
                    Label { anchors.left: parent.left; anchors.leftMargin: 12; anchors.bottom: parent.bottom; anchors.bottomMargin: 12; text: available ? qsTr("Disponível · Steam") : qsTr("Em breve"); color: available ? launcherView.success : launcherView.muted; font.pixelSize: 10 }
                    Rectangle {
                        visible: available
                        anchors.right: parent.right
                        anchors.bottom: parent.bottom
                        anchors.rightMargin: 10
                        anchors.bottomMargin: 9
                        width: 30
                        height: 30
                        radius: 15
                        color: launcherView.accent
                        Label { anchors.centerIn: parent; text: "▶"; color: "#08101F"; font.pixelSize: 11 }
                        MouseArea { anchors.fill: parent; onClicked: parent.parent.parent.parent.play() }
                    }
                }
            }
        }
    }

    Loader { sourceComponent: navButtonComponent; property string icon: "⌂"; property string label: qsTr("Início"); property bool selected: true; active: false }
    Loader { sourceComponent: filterChipComponent; active: false }
    Loader { sourceComponent: gameCardComponent; active: false }

    Dialog {
        id: errorDialog
        title: qsTr("Não foi possível conectar")
        standardButtons: Dialog.Ok
        anchors.centerIn: parent
        Label {
            text: qsTr("A máquina dedicada ainda não está pronta. Tente novamente em alguns segundos.")
            color: launcherView.ink
            wrapMode: Text.WordWrap
        }
    }
}
