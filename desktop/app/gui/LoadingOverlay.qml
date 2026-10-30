import QtQuick 2.15
import QtQuick.Controls 2.3
import QtQuick.Controls.Material 2.2

Rectangle {
    id: overlay
    property string statusText: ""
    property bool reducedMotion: false
    signal cancelRequested()
    color: "#08080b"
    Material.theme: Material.Dark
    Material.accent: "#ffffff"
    focus: true
    Keys.onEscapePressed: cancelRequested()
    Keys.onBackPressed: cancelRequested()
    onVisibleChanged: {
        if (visible)
            cancelButton.forceActiveFocus()
    }

    Image {
        id: logo
        source: Qt.resolvedUrl("../res/spaceconnect_icon_256.png")
        width: Math.min(112, parent.height * 0.22)
        height: width
        anchors.centerIn: parent
        anchors.verticalCenterOffset: -parent.height * 0.08
        fillMode: Image.PreserveAspectFit
        smooth: true
        Accessible.role: Accessible.Graphic
        Accessible.name: "Space Connect"
        SequentialAnimation on scale {
            running: overlay.visible && !overlay.reducedMotion
            loops: Animation.Infinite
            NumberAnimation { to: 1.05; duration: 1800; easing.type: Easing.InOutSine }
            NumberAnimation { to: 1; duration: 1800; easing.type: Easing.InOutSine }
        }
        SequentialAnimation on opacity {
            running: overlay.visible && !overlay.reducedMotion
            loops: Animation.Infinite
            NumberAnimation { to: 0.8; duration: 1800; easing.type: Easing.InOutSine }
            NumberAnimation { to: 1; duration: 1800; easing.type: Easing.InOutSine }
        }
    }

    Column {
        width: Math.min(640, parent.width - 64)
        anchors.horizontalCenter: parent.horizontalCenter
        anchors.bottom: cancelButton.top
        anchors.bottomMargin: 32
        spacing: 24
        Canvas {
            id: spinner
            width: 28
            height: 28
            anchors.horizontalCenter: parent.horizontalCenter
            onPaint: {
                var ctx = getContext("2d")
                ctx.clearRect(0, 0, width, height)
                ctx.lineWidth = 2
                ctx.lineCap = "round"
                ctx.strokeStyle = "#ffffff"
                ctx.beginPath()
                ctx.arc(14, 14, 11, 0, Math.PI * 1.45)
                ctx.stroke()
            }
            RotationAnimator on rotation {
                from: 0
                to: 360
                duration: 1100
                loops: Animation.Infinite
                running: overlay.visible && !overlay.reducedMotion
            }
            Accessible.ignored: true
        }
        StatusTicker {
            width: parent.width
            height: implicitHeight
            text: overlay.statusText
            reducedMotion: overlay.reducedMotion
        }
    }
    Button {
        id: cancelButton
        anchors.horizontalCenter: parent.horizontalCenter
        anchors.bottom: parent.bottom
        anchors.bottomMargin: Math.max(24, parent.height * 0.06)
        text: qsTr("Cancel connection")
        flat: true
        focus: true
        onClicked: overlay.cancelRequested()
        Accessible.description: qsTr("Return without turning off your machine")
    }
}
