import QtQuick

// SpaceCard — card do site: fundo dark-space, borda branca sutil, cantos
// arredondados (rounded-2xl), realce de borda no hover. Use como container.
Rectangle {
    id: card

    property bool hoverable: false
    property bool hovered: false
    property alias radiusValue: card.radius

    color: SpaceTheme.bgRaised
    radius: SpaceTheme.radiusLg
    border.width: 1
    border.color: (hoverable && hovered) ? SpaceTheme.borderBrand : SpaceTheme.border

    Behavior on border.color { ColorAnimation { duration: 150 } }
}
