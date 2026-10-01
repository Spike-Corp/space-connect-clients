import QtQuick 2.9
import QtQuick.Controls 2.3
import QtQuick.Layouts 1.3
import LauncherApi 1.0

// Aba "Downloads" — biblioteca de arquivos/links gerenciada pelo admin
// (Admin → Downloads do app). O cliente baixa pro PC (arquivo vai pra pasta
// Downloads) ou abre o link no navegador, com 1 clique.
Item {
    id: downloadsView
    objectName: qsTr("Downloads")

    property int downloadProgress: -1
    property string downloadingId: ""

    Component.onCompleted: LauncherApi.refreshDownloads()

    Connections {
        target: LauncherApi
        function onDownloadItemProgress(percent) {
            downloadsView.downloadProgress = percent
        }
        function onDownloadItemFinished(success, message) {
            downloadsView.downloadingId = ""
            downloadsView.downloadProgress = -1
            resultDialog.isError = !success
            resultDialog.text = message
            resultDialog.open()
        }
    }

    function fmtSize(bytes) {
        if (!bytes || bytes <= 0) return ""
        if (bytes >= 1073741824) return (bytes / 1073741824).toFixed(1) + " GB"
        return Math.round(bytes / 1024 / 1024) + " MB"
    }

    // Card de UM download
    component DownloadCard: Rectangle {
        id: card
        property var item: modelData

        Layout.fillWidth: true
        implicitHeight: cardCol.implicitHeight + 28
        radius: 12
        color: "#1a1426"
        border.width: 1
        border.color: "#3a2d52"

        ColumnLayout {
            id: cardCol
            anchors.fill: parent
            anchors.margins: 14
            spacing: 8

            RowLayout {
                Layout.fillWidth: true
                Label {
                    text: (card.item.icon || "📦") + "  " + card.item.name
                    color: "#f8f5ff"
                    font.pixelSize: 17
                    font.bold: true
                }
                Item { Layout.fillWidth: true }
                Label {
                    visible: card.item.kind === "link"
                    text: qsTr("LINK")
                    color: "#a482fa"
                    font.pixelSize: 11
                    font.bold: true
                }
            }

            Label {
                visible: (card.item.description || "").length > 0
                text: card.item.description || ""
                color: "#cfc8e3"
                font.pixelSize: 13
                wrapMode: Text.WordWrap
                Layout.fillWidth: true
            }

            RowLayout {
                Layout.fillWidth: true
                spacing: 10

                Label {
                    text: downloadsView.fmtSize(card.item.sizeBytes)
                    color: "#6b6580"
                    font.pixelSize: 11
                }
                Item { Layout.fillWidth: true }

                Button {
                    enabled: downloadsView.downloadingId === ""
                    text: card.item.kind === "link"
                          ? qsTr("Abrir link")
                          : (downloadsView.downloadingId === card.item.id
                             ? qsTr("Baixando… %1%").arg(Math.max(0, downloadsView.downloadProgress))
                             : qsTr("Baixar"))
                    onClicked: {
                        downloadsView.downloadingId = card.item.id
                        downloadsView.downloadProgress = 0
                        LauncherApi.downloadItem(card.item.id, card.item.url, card.item.name, card.item.kind)
                    }
                }
            }

            ProgressBar {
                visible: downloadsView.downloadingId === card.item.id
                Layout.fillWidth: true
                from: 0
                to: 100
                value: Math.max(0, downloadsView.downloadProgress)
                indeterminate: downloadsView.downloadProgress < 0
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
                Label { text: qsTr("DOWNLOADS"); color: "#a482fa"; font.pixelSize: 24; font.bold: true }
                Item { Layout.fillWidth: true }
                Button { text: qsTr("Atualizar"); onClicked: LauncherApi.refreshDownloads() }
            }

            Label {
                text: qsTr("Arquivos e ferramentas oficiais da SpaceCloud pra baixar no seu PC.")
                color: "#9793aa"
                font.pixelSize: 12
                wrapMode: Text.WordWrap
                Layout.fillWidth: true
            }

            Repeater {
                model: LauncherApi.downloads
                delegate: DownloadCard {}
            }

            Label {
                visible: LauncherApi.downloads.length === 0
                text: qsTr("Nenhum download disponível no momento.")
                color: "#6b6580"
                font.pixelSize: 13
                horizontalAlignment: Text.AlignHCenter
                Layout.fillWidth: true
                Layout.topMargin: 30
            }

            Button {
                text: qsTr("Voltar")
                Layout.alignment: Qt.AlignHCenter
                onClicked: stackView.pop()
            }
        }
    }

    Dialog {
        id: resultDialog
        property bool isError: false
        property alias text: resultLabel.text
        title: isError ? qsTr("Algo deu errado") : qsTr("Feito")
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
