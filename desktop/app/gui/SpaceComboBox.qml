import QtQuick 2.9
import QtQuick.Controls 2.2

// Custom-styled drop-in replacement for AutoResizingComboBox (inherits its auto-width-to-
// content-fit logic unchanged). Overrides background/contentItem/indicator/popup styling with
// a rounded dark "chip" look (purple border on focus/hover, purple chevron) instead of the
// generic Qt Material combo box look, so it doesn't read as a "stock Moonlight" selector.
AutoResizingComboBox {
    id: control

    // AutoResizingComboBox.qml's desiredWidth formula computes
    // "leftPadding + textWidth + indicator.width + rightPadding" using the CONTROL's own
    // leftPadding/rightPadding (inherited from QQC2 Control). Without setting them explicitly
    // here, they fell back to the stock Material style's defaults, which are smaller than what
    // this custom contentItem actually consumes below (12px left + indicator.width+16 right) -
    // so the computed width was consistently a bit too narrow for the real rendered padding,
    // clipping/eliding text that should have fit (e.g. "Som surround 5.1/7.1" showing as
    // "Som surro..."). Setting them explicitly here keeps the two in sync.
    leftPadding: 12
    rightPadding: 16

    background: Rectangle {
        implicitHeight: 38
        radius: 8
        color: "#201A33"
        border.width: 1.5
        border.color: (control.activeFocus || control.hovered || control.popup.visible) ? "#9B6BFF" : "#4A4166"
        opacity: control.enabled ? 1.0 : 0.5

        Behavior on border.color { ColorAnimation { duration: 100 } }
    }

    contentItem: Text {
        text: control.displayText
        font: control.font
        color: control.enabled ? "#F1EDFB" : "#6B6480"
        verticalAlignment: Text.AlignVCenter
        elide: Text.ElideRight
        leftPadding: control.leftPadding
        rightPadding: control.rightPadding + control.indicator.width
    }

    indicator: Text {
        x: control.width - width - 14
        y: control.topPadding + (control.availableHeight - height) / 2
        text: "\u25BE"
        color: "#9B6BFF"
        font.pixelSize: 14
    }

    popup.background: Rectangle {
        radius: 8
        color: "#201A33"
        border.width: 1.5
        border.color: "#9B6BFF"
    }
}
