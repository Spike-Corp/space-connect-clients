import QtQuick 2.9
import QtQuick.Controls 2.2
import QtQuick.Layouts 1.3

import LatencyTester 1.0

// Teste de rede/latência — paridade com o Android (LatencyTestActivity).
// Pinga servidores de borda (Cloudflare/Google), mostra mediana/min/max/jitter
// e um veredito pensado pra cloud gaming. Paleta = identidade nova do site.
Item {
    id: root

    // Tokens da identidade SpaceCloud (frontend-v2 / design-guide).
    readonly property color bg: "#0d0816"
    readonly property color surface: "#110d17"
    readonly property color textMain: "#f8f5ff"
    readonly property color textMuted: "#9793aa"
    readonly property color brandBlue: "#4572fa"
    readonly property color brandPurple: "#a482fa"

    function verdictFor(median, jitter) {
        if (median <= 30 && jitter <= 15) return { "t": qsTr("Excelente — perfeito pra jogar na nuvem"), "c": "#4ADE80" }
        if (median <= 60 && jitter <= 30) return { "t": qsTr("Boa — experiência lisa"), "c": "#A3E635" }
        if (median <= 100 && jitter <= 50) return { "t": qsTr("Razoável — dá pra jogar; prefira Wi-Fi 5GHz ou cabo"), "c": "#FB923C" }
        return { "t": qsTr("Instável — pode engasgar; chegue perto do roteador ou use cabo/5GHz"), "c": "#F87171" }
    }

    Rectangle { anchors.fill: parent; color: bg }

    ColumnLayout {
        anchors.centerIn: parent
        width: Math.min(520, parent.width - 60)
        spacing: 18

        Label {
            text: qsTr("Teste de rede / latência")
            color: textMain
            font.pixelSize: 26
            font.bold: true
            Layout.alignment: Qt.AlignHCenter
        }

        Label {
            text: LatencyTester.running
                  ? LatencyTester.progressText
                  : (LatencyTester.medianMs >= 0 ? LatencyTester.medianMs + " ms" : "—")
            color: brandPurple
            font.pixelSize: LatencyTester.running ? 18 : 64
            font.bold: !LatencyTester.running
            Layout.alignment: Qt.AlignHCenter
        }

        Label {
            id: verdictLabel
            visible: !LatencyTester.running && LatencyTester.medianMs >= 0
            text: ""
            font.pixelSize: 16
            font.bold: true
            horizontalAlignment: Text.AlignHCenter
            wrapMode: Text.WordWrap
            Layout.fillWidth: true
            Layout.alignment: Qt.AlignHCenter
        }

        Label {
            visible: !LatencyTester.running && LatencyTester.medianMs >= 0
            text: qsTr("min %1 ms · max %2 ms · jitter %3 ms")
                    .arg(LatencyTester.minMs).arg(LatencyTester.maxMs).arg(LatencyTester.jitterMs)
            color: textMuted
            font.pixelSize: 13
            Layout.alignment: Qt.AlignHCenter
        }

        Label {
            visible: !LatencyTester.running && LatencyTester.medianMs < 0 && LatencyTester.progressText.length > 0
            text: qsTr("Sem conexão — verifique sua internet")
            color: "#F87171"
            font.pixelSize: 15
            font.bold: true
            Layout.alignment: Qt.AlignHCenter
        }

        Label {
            visible: !LatencyTester.running && LatencyTester.medianMs < 0 && LatencyTester.progressText.length === 0
            text: qsTr("Medimos sua resposta até servidores de borda (Cloudflare/Google) pra avaliar sua conexão pra jogar na nuvem.")
            color: textMuted
            font.pixelSize: 13
            horizontalAlignment: Text.AlignHCenter
            wrapMode: Text.WordWrap
            Layout.fillWidth: true
        }

        BusyIndicator {
            running: LatencyTester.running
            visible: running
            Layout.alignment: Qt.AlignHCenter
        }

        Button {
            text: LatencyTester.running ? qsTr("Medindo…") : qsTr("Iniciar teste")
            enabled: !LatencyTester.running
            highlighted: true
            Layout.fillWidth: true
            onClicked: LatencyTester.startTest()
        }
    }

    Connections {
        target: LatencyTester
        function onFinished() {
            if (LatencyTester.medianMs >= 0) {
                const v = root.verdictFor(LatencyTester.medianMs, LatencyTester.jitterMs)
                verdictLabel.text = v.t
                verdictLabel.color = v.c
            }
        }
    }
}
