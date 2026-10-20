import QtQuick
import QtQuick.Controls 2.2

// SpaceButton — CTA do site: pílula com gradiente azul->roxo, título em
// Bai Jamjuree bold UPPERCASE com tracking, hover em escala. Variantes:
//   variant: "primary" (gradiente), "secondary" (contorno), "ghost" (plano)
Button {
    id: control

    property string variant: "primary"
    readonly property bool _primary: variant === "primary"
    readonly property bool _secondary: variant === "secondary"

    implicitHeight: 44
    padding: SpaceTheme.space3
    leftPadding: SpaceTheme.space5
    rightPadding: SpaceTheme.space5
    font.family: SpaceTheme.headingFont
    font.pixelSize: 14
    font.bold: true
    font.capitalization: Font.AllUppercase
    font.letterSpacing: SpaceTheme.ctaTracking

    scale: control.down ? 0.98 : (control.hovered ? 1.03 : 1.0)
    Behavior on scale { NumberAnimation { duration: 120; easing.type: Easing.OutCubic } }

    contentItem: Text {
        text: control.text
        font: control.font
        color: SpaceTheme.fg
        horizontalAlignment: Text.AlignHCenter
        verticalAlignment: Text.AlignVCenter
        elide: Text.ElideRight
    }

    background: Rectangle {
        implicitWidth: 120
        radius: SpaceTheme.radiusPill
        color: control._secondary ? "transparent"
                                  : (control._primary ? "transparent"
                                                      : (control.hovered ? SpaceTheme.surfaceHover : "transparent"))
        border.width: control._secondary ? 1 : 0
        border.color: control.hovered ? SpaceTheme.purple : SpaceTheme.borderStrong
        opacity: control.enabled ? 1.0 : 0.45

        Rectangle {
            anchors.fill: parent
            radius: parent.radius
            visible: control._primary
            gradient: Gradient {
                orientation: Gradient.Horizontal
                GradientStop { position: 0.0; color: SpaceTheme.gradStart }
                GradientStop { position: 1.0; color: SpaceTheme.gradEnd }
            }
        }
    }
}
