import QtQuick 2.9
import QtQuick.Controls 2.2

// Custom-styled drop-in replacement for CheckBox. Overrides the indicator and contentItem
// entirely (rounded, filled dark chip with a purple fill + checkmark when checked) instead of
// relying on the generic Qt Material outline-square look, so it doesn't read as a "stock
// Moonlight"/default Qt Quick Controls checkbox - matches the app's dark/purple gamer theme.
CheckBox {
    id: control

    indicator: Rectangle {
        implicitWidth: 22
        implicitHeight: 22
        x: control.leftPadding
        y: control.topPadding + (control.availableHeight - height) / 2
        radius: 6
        color: control.checked ? "#9B6BFF" : "#201A33"
        border.width: 1.5
        border.color: control.checked ? "#9B6BFF" : (control.hovered ? "#9B6BFF" : "#4A4166")
        opacity: control.enabled ? 1.0 : 0.5

        Behavior on color { ColorAnimation { duration: 100 } }
        Behavior on border.color { ColorAnimation { duration: 100 } }

        Text {
            anchors.centerIn: parent
            visible: control.checked
            text: "\u2713"
            font.pixelSize: 14
            font.bold: true
            color: "#F1EDFB"
        }
    }

    contentItem: Text {
        text: control.text
        font: control.font
        color: control.enabled ? "#F1EDFB" : "#6B6480"
        wrapMode: Text.Wrap
        verticalAlignment: Text.AlignVCenter
        leftPadding: control.indicator.width + control.spacing
        width: control.availableWidth
    }
}
