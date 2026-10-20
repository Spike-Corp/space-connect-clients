import QtQuick

// SpaceKicker — rótulo pequeno em MAIÚSCULAS com tracking, na cor kicker do
// site (#a993ff). Usado como "olho"/eyebrow acima de títulos.
Text {
    font.family: SpaceTheme.bodyFont
    font.pixelSize: 11
    font.bold: true
    font.capitalization: Font.AllUppercase
    font.letterSpacing: 1.6
    color: SpaceTheme.kicker
}
