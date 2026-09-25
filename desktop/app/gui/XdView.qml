import QtQuick 2.9
import QtQuick.Controls 2.3
import QtQuick.Layouts 1.3
import XdApi 1.0

// XD CONSOLE — tela principal: overview ao vivo, busca por
// e-mail/@username/nome/VM, plano, tempo de sessão restante, ações de energia
// (as mesmas do Space Connect) e histórico de sessões. Tudo via /admin/xd.
Item {
    id: xdView
    objectName: "XD Console"

    property string lastQuery: ""
    property bool onlyRunning: false
    property var confirmTarget: null   // { id, name, action, inSession, owner }
    property var historyTarget: null

    Component.onCompleted: XdApi.refresh(lastQuery, onlyRunning)

    // Auto-refresh silencioso a cada 15s.
    Timer {
        interval: 15000
        repeat: true
        running: true
        onTriggered: XdApi.refresh(lastQuery, onlyRunning)
    }
    // Tick de 1s pros contadores de sessão.
    Timer {
        interval: 1000
        repeat: true
        running: true
        onTriggered: tickLabel.text = tickLabel.text // força reavaliação dos bindings de tempo
    }

    Connections {
        target: XdApi
        function onLoggedInChanged() {
            if (!XdApi.loggedIn)
                stackView.pop(null)  // volta pro login
        }
        function onActionFinished(success, message) {
            resultDialog.isError = !success
            resultDialog.text = message
            resultDialog.open()
            if (success)
                refreshTimer2.start()
        }
    }
    Timer {
        id: refreshTimer2
        interval: 2500
        onTriggered: XdApi.refresh(lastQuery, onlyRunning)
    }

    function fmtRemaining(endAtIso) {
        if (!endAtIso) return "—"
        var ms = new Date(endAtIso).getTime() - Date.now()
        if (ms <= 0) return qsTr("encerrando…")
        var totalMin = Math.floor(ms / 60000)
        var h = Math.floor(totalMin / 60)
        var m = totalMin % 60
        return h > 0 ? (h + "h " + (m < 10 ? "0" : "") + m + "min") : (totalMin + "min")
    }

    function statusLabel(st) {
        switch (st) {
        case "busy": return qsTr("EM SESSÃO")
        case "online": return qsTr("LIGADA")
        case "idle": return qsTr("LIGADA (OCIOSA)")
        case "offline": return qsTr("DESLIGADA")
        case "provisioning": return qsTr("PROVISIONANDO")
        default: return ("" + st).toUpperCase()
        }
    }
    function statusColor(st) {
        switch (st) {
        case "busy": return "#4ade80"
        case "online": return "#4ade80"
        case "idle": return "#fbbf24"
        case "offline": return "#5b5470"
        case "provisioning": return "#4572fa"
        default: return "#9793aa"
        }
    }

    Rectangle {
        anchors.fill: parent
        color: "#0d0816"
    }

    ColumnLayout {
        anchors.fill: parent
        anchors.margins: 16
        spacing: 12

        // ── Overview ────────────────────────────────────────────────────────
        RowLayout {
            Layout.fillWidth: true
            spacing: 10

            Repeater {
                model: [
                    { "label": qsTr("VMs ligadas"), "value": XdApi.overview.runningMachines },
                    { "label": qsTr("Sessões ativas"), "value": XdApi.overview.activeSessions },
                    { "label": qsTr("Na fila"), "value": XdApi.overview.queueSize },
                    { "label": qsTr("Total de VMs"), "value": XdApi.overview.totalMachines }
                ]
                Rectangle {
                    Layout.fillWidth: true
                    implicitHeight: 64
                    color: "#110d17"
                    radius: 8
                    border.color: "#2a2140"
                    Column {
                        anchors.centerIn: parent
                        spacing: 2
                        Label {
                            anchors.horizontalCenter: parent.horizontalCenter
                            text: modelData.value === undefined || modelData.value === null ? "—" : modelData.value
                            color: "#ffffff"
                            font.pixelSize: 24
                            font.bold: true
                        }
                        Label {
                            anchors.horizontalCenter: parent.horizontalCenter
                            text: modelData.label
                            color: "#9793aa"
                            font.pixelSize: 11
                        }
                    }
                }
            }
        }

        // ── Busca ───────────────────────────────────────────────────────────
        RowLayout {
            Layout.fillWidth: true
            spacing: 10

            TextField {
                id: searchField
                Layout.fillWidth: true
                placeholderText: qsTr("Buscar por e-mail, @username, nome do cliente ou da VM…")
                color: "#e8e2ff"
                selectByMouse: true
                onTextChanged: searchDebounce.restart()
                Timer {
                    id: searchDebounce
                    interval: 400
                    onTriggered: { lastQuery = searchField.text; XdApi.refresh(lastQuery, onlyRunning) }
                }
            }
            CheckBox {
                id: runningCheck
                text: qsTr("Só ligadas")
                checked: false
                onCheckedChanged: { onlyRunning = checked; XdApi.refresh(lastQuery, onlyRunning) }
                contentItem: Label {
                    text: runningCheck.text
                    color: "#e8e2ff"
                    leftPadding: runningCheck.indicator.width + 6
                    verticalAlignment: Qt.AlignVCenter
                }
            }
            Button {
                text: XdApi.busy ? qsTr("…") : qsTr("Atualizar")
                enabled: !XdApi.busy
                onClicked: XdApi.refresh(lastQuery, onlyRunning)
            }
            Button {
                text: qsTr("Sair")
                onClicked: XdApi.logout()
            }
        }

        Label {
            text: qsTr("Logado como %1 — ações são auditadas.").arg(XdApi.accountLabel)
            color: "#5b5470"
            font.pixelSize: 11
        }

        // ── Lista de VMs ────────────────────────────────────────────────────
        Rectangle {
            Layout.fillWidth: true
            Layout.fillHeight: true
            color: "#110d17"
            radius: 8
            border.color: "#2a2140"

            ListView {
                id: machineList
                anchors.fill: parent
                anchors.margins: 6
                clip: true
                spacing: 4
                model: XdApi.machines

                Label {
                    anchors.centerIn: parent
                    visible: machineList.count === 0 && !XdApi.busy
                    text: qsTr("Nenhuma VM encontrada.")
                    color: "#5b5470"
                }

                delegate: Rectangle {
                    width: machineList.width - 12
                    implicitHeight: 74
                    color: index % 2 === 0 ? "#160f22" : "#110d17"
                    radius: 6

                    property var m: modelData

                    RowLayout {
                        anchors.fill: parent
                        anchors.margins: 10
                        spacing: 12

                        ColumnLayout {
                            Layout.fillWidth: true
                            spacing: 2
                            Label {
                                text: m.name || "—"
                                color: "#ffffff"
                                font.bold: true
                                font.pixelSize: 13
                                elide: Label.ElideRight
                                Layout.fillWidth: true
                            }
                            Label {
                                text: (m.owner ? (m.owner.email + (m.owner.username ? " · @" + m.owner.username : "")) : qsTr("sem dono"))
                                color: "#9793aa"
                                font.pixelSize: 11
                                elide: Label.ElideRight
                                Layout.fillWidth: true
                            }
                            Label {
                                text: (m.plan ? m.plan.name + (m.plan.unlimited ? " · ilimitado" : " · " + (Math.round((m.plan.hoursRemaining || 0) * 10) / 10) + "h") : "—")
                                      + (m.node ? "   ·   " + m.node + " #" + (m.vmId || "?") : "")
                                color: "#5b5470"
                                font.pixelSize: 11
                                elide: Label.ElideRight
                                Layout.fillWidth: true
                            }
                        }

                        ColumnLayout {
                            spacing: 2
                            Label {
                                text: statusLabel(m.status)
                                color: statusColor(m.status)
                                font.bold: true
                                font.pixelSize: 11
                            }
                            Label {
                                id: tickLabel
                                visible: !!m.session
                                text: m.session ? ("⏱ " + fmtRemaining(m.session.endAt)) : ""
                                color: "#4ade80"
                                font.pixelSize: 11
                            }
                        }

                        RowLayout {
                            spacing: 4
                            Button {
                                text: qsTr("Histórico")
                                onClicked: {
                                    historyTarget = m
                                    historyDialog.machineName = m.name
                                    historyDialog.machineInfo = (m.owner ? m.owner.email : "")
                                    XdApi.loadMachineDetail(m.id)
                                    historyDialog.open()
                                }
                            }
                            Button {
                                visible: m.status === "offline"
                                text: qsTr("Ligar")
                                enabled: !XdApi.busy
                                onClicked: {
                                    confirmTarget = { "id": m.id, "name": m.name, "action": "start", "inSession": !!m.session, "owner": m.owner ? m.owner.email : "" }
                                    confirmDialog.open()
                                }
                                contentItem: Label { text: parent.text; color: "#4ade80"; horizontalAlignment: Qt.AlignHCenter }
                            }
                            Button {
                                visible: m.status !== "offline"
                                text: qsTr("Reiniciar")
                                enabled: !XdApi.busy
                                onClicked: {
                                    confirmTarget = { "id": m.id, "name": m.name, "action": "restart", "inSession": !!m.session, "owner": m.owner ? m.owner.email : "" }
                                    confirmDialog.open()
                                }
                                contentItem: Label { text: parent.text; color: "#fbbf24"; horizontalAlignment: Qt.AlignHCenter }
                            }
                            Button {
                                visible: m.status !== "offline"
                                text: qsTr("Desligar")
                                enabled: !XdApi.busy
                                onClicked: {
                                    confirmTarget = { "id": m.id, "name": m.name, "action": "shutdown", "inSession": !!m.session, "owner": m.owner ? m.owner.email : "" }
                                    confirmDialog.open()
                                }
                                contentItem: Label { text: parent.text; color: "#F87171"; horizontalAlignment: Qt.AlignHCenter }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Confirmação de energia ──────────────────────────────────────────────
    Dialog {
        id: confirmDialog
        title: confirmTarget ? confirmTarget.name : ""
        modal: true
        anchors.centerIn: parent
        standardButtons: Dialog.Ok | Dialog.Cancel

        property string actionLabel: confirmTarget
            ? (confirmTarget.action === "start" ? qsTr("ligar")
             : confirmTarget.action === "restart" ? qsTr("reiniciar") : qsTr("desligar"))
            : ""

        Label {
            width: Math.min(420, xdView.width - 120)
            wrapMode: Text.WordWrap
            color: "#e8e2ff"
            text: confirmTarget
                ? (confirmTarget.inSession && confirmTarget.action !== "start"
                    ? qsTr("ATENÇÃO: essa VM está EM SESSÃO (%1) — %2 derruba o cliente na hora. Confirmar?")
                        .arg(confirmTarget.owner).arg(confirmDialog.actionLabel)
                    : qsTr("Confirmar %1 a VM de %2?").arg(confirmDialog.actionLabel).arg(confirmTarget.owner || "—"))
                : ""
        }

        onAccepted: if (confirmTarget) XdApi.powerAction(confirmTarget.id, confirmTarget.action)
    }

    // ── Resultado de ação ───────────────────────────────────────────────────
    Dialog {
        id: resultDialog
        property bool isError: false
        property alias text: resultLabel.text
        title: isError ? qsTr("Falhou") : qsTr("Feito")
        modal: true
        anchors.centerIn: parent
        standardButtons: Dialog.Ok
        Label {
            id: resultLabel
            width: Math.min(420, xdView.width - 120)
            wrapMode: Text.WordWrap
            color: "#e8e2ff"
        }
    }

    // ── Histórico de sessões ────────────────────────────────────────────────
    Dialog {
        id: historyDialog
        property string machineName: ""
        property string machineInfo: ""
        title: qsTr("Histórico — %1").arg(machineName)
        modal: true
        anchors.centerIn: parent
        width: Math.min(640, xdView.width - 80)
        height: Math.min(480, xdView.height - 80)
        standardButtons: Dialog.Close

        ColumnLayout {
            anchors.fill: parent
            spacing: 8
            Label {
                text: historyDialog.machineInfo
                color: "#9793aa"
                font.pixelSize: 11
            }
            ListView {
                Layout.fillWidth: true
                Layout.fillHeight: true
                clip: true
                spacing: 4
                model: XdApi.machineHistory
                Label {
                    anchors.centerIn: parent
                    visible: XdApi.machineHistory.length === 0
                    text: qsTr("Nenhuma sessão registrada.")
                    color: "#5b5470"
                }
                delegate: Rectangle {
                    width: parent.width
                    implicitHeight: 44
                    color: index % 2 === 0 ? "#160f22" : "#110d17"
                    radius: 4
                    RowLayout {
                        anchors.fill: parent
                        anchors.margins: 8
                        Label {
                            Layout.fillWidth: true
                            text: {
                                var s = new Date(modelData.startAt)
                                var label = s.toLocaleString(Qt.locale("pt-BR"), "dd/MM HH:mm")
                                label += "  ·  " + (modelData.source || "queue") + " · " + modelData.maxHours + "h"
                                if (modelData.endReason) label += " · " + modelData.endReason
                                label
                            }
                            color: "#e8e2ff"
                            font.pixelSize: 12
                            elide: Label.ElideRight
                        }
                        Label {
                            text: modelData.active ? qsTr("ATIVA") : qsTr("encerrada")
                            color: modelData.active ? "#4ade80" : "#5b5470"
                            font.bold: true
                            font.pixelSize: 11
                        }
                    }
                }
            }
        }
    }
}
