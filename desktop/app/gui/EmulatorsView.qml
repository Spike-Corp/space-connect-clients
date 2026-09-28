import QtQuick 2.9
import QtQuick.Controls 2.3
import QtQuick.Layouts 1.3
import LauncherApi 1.0

// Aba "Emuladores" — catálogo oficial da SpaceCloud (RetroArch e cia) com
// instalar/abrir em 1 clique direto na VM. O backend faz tudo via guest agent
// (mesmo mecanismo do upload de arquivo), então funciona em qualquer VM sem
// versão nova do agent. ROMs são responsabilidade do cliente (upload próprio).
Item {
    id: emulatorsView
    objectName: qsTr("Emulators")

    Component.onCompleted: LauncherApi.refreshEmulators()

    Connections {
        target: LauncherApi
        function onEmulatorActionResult(success, message) {
            resultDialog.isError = !success
            resultDialog.text = message
            resultDialog.open()
        }
    }

    function fmtSize(bytes) {
        if (!bytes || bytes <= 0) return ""
        return Math.round(bytes / 1024 / 1024) + " MB"
    }

    // Card de UM emulador
    component EmuCard: Rectangle {
        id: card
        property var emu: modelData

        Layout.fillWidth: true
        implicitHeight: cardCol.implicitHeight + 28
        radius: 12
        color: "#1a1426"
        border.width: 1
        border.color: card.emu.installed ? "#2dd4a0" : "#3a2d52"

        ColumnLayout {
            id: cardCol
            anchors.fill: parent
            anchors.margins: 14
            spacing: 8

            RowLayout {
                Layout.fillWidth: true
                Label {
                    text: (card.emu.icon || "🎮") + "  " + card.emu.name
                    color: "#f8f5ff"
                    font.pixelSize: 17
                    font.bold: true
                }
                Label {
                    text: "v" + (card.emu.version || "")
                    color: "#9793aa"
                    font.pixelSize: 11
                }
                Item { Layout.fillWidth: true }
                Label {
                    visible: !!card.emu.installed
                    text: qsTr("INSTALLED")
                    color: "#2dd4a0"
                    font.pixelSize: 11
                    font.bold: true
                }
            }

            Label {
                text: card.emu.tagline || ""
                color: "#cfc8e3"
                font.pixelSize: 13
                wrapMode: Text.WordWrap
                Layout.fillWidth: true
            }

            Label {
                visible: (card.emu.systems || []).length > 0
                text: (card.emu.systems || []).join(" · ")
                color: "#9793aa"
                font.pixelSize: 11
                wrapMode: Text.WordWrap
                Layout.fillWidth: true
            }

            RowLayout {
                Layout.fillWidth: true
                spacing: 10

                Label {
                    text: emulatorsView.fmtSize(card.emu.sizeBytes)
                    color: "#6b6580"
                    font.pixelSize: 11
                }
                Item { Layout.fillWidth: true }

                Button {
                    visible: !card.emu.installed
                    enabled: !LauncherApi.busy
                    text: LauncherApi.busy ? qsTr("Installing…") : qsTr("Install on my PC")
                    onClicked: LauncherApi.installEmulator(card.emu.id)
                }
                Button {
                    visible: !!card.emu.installed
                    enabled: !LauncherApi.busy
                    text: qsTr("Open on my PC")
                    onClicked: LauncherApi.launchEmulator(card.emu.id)
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
                Label { text: qsTr("EMULATORS"); color: "#a482fa"; font.pixelSize: 24; font.bold: true }
                Item { Layout.fillWidth: true }
                Button { text: qsTr("Refresh"); onClicked: LauncherApi.refreshEmulators() }
            }

            Label {
                text: qsTr("Install emulators on your cloud PC with one click and play the classics. Your game files (ROMs) are yours: send them from your computer or phone with \"Send file\".")
                color: "#9793aa"
                font.pixelSize: 12
                wrapMode: Text.WordWrap
                Layout.fillWidth: true
            }

            Repeater {
                model: LauncherApi.emulators
                delegate: EmuCard {}
            }

            Label {
                visible: LauncherApi.emulators.length === 0
                text: qsTr("No emulators available right now.")
                color: "#6b6580"
                font.pixelSize: 13
                horizontalAlignment: Text.AlignHCenter
                Layout.fillWidth: true
                Layout.topMargin: 30
            }

            Button {
                text: qsTr("Back")
                Layout.alignment: Qt.AlignHCenter
                onClicked: stackView.pop()
            }
        }
    }

    Dialog {
        id: resultDialog
        property bool isError: false
        property alias text: resultLabel.text
        title: isError ? qsTr("Something went wrong") : qsTr("Done")
        standardButtons: Dialog.Ok
        parent: Overlay.overlay
        x: Math.round((parent.width - width) / 2)
        y: Math.round((parent.height - height) / 2)
        modal: true
        Label {
            id: resultLabel
            color: "#e8e2ff"
            wrapMode: Text.WordWrap
            width: 300
        }
    }
}
