import QtQuick 2.9
import QtQuick.Controls 2.2

// Reusable "gamer HUD" section header: colored Tabler-icon glyph + bold title,
// used as the `label:` override for every Settings GroupBox instead of the
// plain HTML-in-title-string trick (that approach can't include a real icon).
Row {
    id: root

    property alias iconSource: icon.source
    property alias text: label.text
    property color accentColor: "#9B6BFF"

    spacing: 8
    bottomPadding: 6

    Image {
        id: icon
        width: 20
        height: 20
        anchors.verticalCenter: label.verticalCenter
        sourceSize: Qt.size(20, 20)
        fillMode: Image.PreserveAspectFit
    }

    Label {
        id: label
        font.pointSize: 13
        font.bold: true
        color: root.accentColor
    }
}
