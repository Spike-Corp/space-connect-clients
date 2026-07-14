import QtQuick 2.9
import QtQuick.Controls 2.2

// Custom-styled SpinBox matching SpaceComboBox/SpaceCheckBox's dark "chip" look (rounded dark
// background, purple border on focus/hover, purple +/- glyphs) instead of the generic Qt
// Material spin box, so it doesn't read as a "stock Moonlight" control. Used for free-form
// numeric entry (e.g. video bitrate) where the user can type or step to any value within
// [from, to], as opposed to SpaceComboBox's fixed list of presets.
SpinBox {
    id: control

    property color spaceBackgroundColor: "#201A33"
    property color spaceBorderColor: (control.activeFocus || control.hovered) ? "#9B6BFF" : "#4A4166"
    property color spaceTextColor: control.enabled ? "#F1EDFB" : "#6B6480"

    background: Rectangle {
        implicitWidth: 160
        implicitHeight: 38
        radius: 8
        color: control.spaceBackgroundColor
        border.width: 1.5
        border.color: control.spaceBorderColor
        opacity: control.enabled ? 1.0 : 0.5

        Behavior on border.color { ColorAnimation { duration: 100 } }
    }

    contentItem: TextInput {
        z: 2
        text: control.textFromValue(control.value, control.locale)
        font: control.font
        color: control.spaceTextColor
        selectionColor: "#9B6BFF"
        selectedTextColor: "#F1EDFB"
        horizontalAlignment: Qt.AlignHCenter
        verticalAlignment: Qt.AlignVCenter
        leftPadding: control.down.indicator.width
        rightPadding: control.up.indicator.width
        readOnly: !control.editable
        validator: control.validator
        inputMethodHints: Qt.ImhDigitsOnly
    }

    up.indicator: Rectangle {
        x: control.width - width
        height: control.height
        implicitWidth: 34
        color: "transparent"

        Text {
            text: "+"
            font.pixelSize: 18
            color: control.up.pressed ? "#F1EDFB" : "#9B6BFF"
            anchors.centerIn: parent
        }
    }

    down.indicator: Rectangle {
        x: 0
        height: control.height
        implicitWidth: 34
        color: "transparent"

        Text {
            text: "\u2212"
            font.pixelSize: 18
            color: control.down.pressed ? "#F1EDFB" : "#9B6BFF"
            anchors.centerIn: parent
        }
    }
}
