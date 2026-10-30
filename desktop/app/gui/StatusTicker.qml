import QtQuick 2.15

Item {
    id: ticker
    property string text: ""
    property bool reducedMotion: false
    property bool firstIsCurrent: true
    property bool ready: false
    property color textColor: "#f8f5ff"
    implicitHeight: Math.max(first.implicitHeight, second.implicitHeight, 28)
    Accessible.role: Accessible.StaticText
    Accessible.name: text

    function updateText() {
        if (!ready || crossfade.running)
            return
        var current = firstIsCurrent ? first : second
        var next = firstIsCurrent ? second : first
        if (current.text === text)
            return
        if (reducedMotion || current.text.length === 0) {
            current.text = text
            current.opacity = 1
            next.opacity = 0
            return
        }
        next.text = text
        next.opacity = 0
        crossfade.start()
    }

    onTextChanged: updateText()
    Component.onCompleted: {
        ready = true
        updateText()
    }

    Text {
        id: first
        objectName: "statusTextA"
        width: parent.width
        anchors.verticalCenter: parent.verticalCenter
        textFormat: Text.PlainText
        wrapMode: Text.WordWrap
        horizontalAlignment: Text.AlignHCenter
        font.family: "Inter"
        font.pixelSize: 20
        color: ticker.textColor
        Accessible.ignored: true
    }
    Text {
        id: second
        objectName: "statusTextB"
        width: parent.width
        anchors.verticalCenter: parent.verticalCenter
        textFormat: Text.PlainText
        wrapMode: Text.WordWrap
        horizontalAlignment: Text.AlignHCenter
        font.family: "Inter"
        font.pixelSize: 20
        color: ticker.textColor
        opacity: 0
        Accessible.ignored: true
    }
    ParallelAnimation {
        id: crossfade
        NumberAnimation {
            target: ticker.firstIsCurrent ? first : second
            property: "opacity"
            to: 0
            duration: 220
            easing.type: Easing.InOutQuad
        }
        NumberAnimation {
            target: ticker.firstIsCurrent ? second : first
            property: "opacity"
            to: 1
            duration: 220
            easing.type: Easing.InOutQuad
        }
        onFinished: {
            ticker.firstIsCurrent = !ticker.firstIsCurrent
            ticker.updateText()
        }
    }
}
