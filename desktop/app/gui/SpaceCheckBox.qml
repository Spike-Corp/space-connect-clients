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
        color: control.checked ? "#a482fa" : "#110d17"
        border.width: 1.5
        border.color: control.checked ? "#a482fa" : (control.hovered ? "#a482fa" : "#110d17")
        opacity: control.enabled ? 1.0 : 0.5

        Behavior on color { ColorAnimation { duration: 100 } }
        Behavior on border.color { ColorAnimation { duration: 100 } }

        Text {
            anchors.centerIn: parent
            visible: control.checked
            text: "\u2713"
            font.pixelSize: 14
            font.bold: true
            color: "#f8f5ff"
        }
    }

    contentItem: Text {
        text: control.text
        font: control.font
        color: control.enabled ? "#f8f5ff" : "#9793aa"
        wrapMode: Text.Wrap
        verticalAlignment: Text.AlignVCenter
        leftPadding: control.indicator.width + control.spacing
        width: control.availableWidth
    }
}
