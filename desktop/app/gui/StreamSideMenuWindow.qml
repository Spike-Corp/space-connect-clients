import QtQuick 2.15
import QtQuick.Window 2.15
import QtQuick.Controls 2.15
import QtQuick.Controls.Material 2.15
import QtQuick.Layouts 1.15

Window {
    id: menu
    property var session
    property bool reducedMotion: false
    readonly property var controller: session ? session.streamGuests : null
    readonly property bool opened: session ? session.streamMenuOpen : false
    readonly property rect bounds: session ? session.streamMenuBounds : Qt.rect(0, 0, 0, 0)
    readonly property bool confirming: controller && controller.confirmationName.length > 0
    readonly property real bodyPointSize: Math.max(11, Qt.application.font.pointSize)
    transientParent: null
    title: qsTr("Space Connect — Guests")
    color: "transparent"
    flags: Qt.Tool | Qt.FramelessWindowHint | Qt.WindowStaysOnTopHint
           | (opened ? 0 : Qt.WindowDoesNotAcceptFocus)
    width: opened ? Math.min(340, Math.max(48, bounds.width - 32)) : 48
    height: opened ? Math.min(520, Math.max(48, bounds.height - 32)) : 48
    x: bounds.x + bounds.width - width - 16
    y: bounds.y + 16
    visible: !!session && !!controller && controller.ownerSession && session.streamMenuVisible
             && bounds.width >= 80 && bounds.height >= 80
    Material.theme: Material.Dark
    Material.accent: brand.purple
    BrandTheme { id: brand }

    function focusPanel() {
        if (visible && opened) {
            raise()
            requestActivate()
            closeButton.forceActiveFocus()
        }
    }
    onOpenedChanged: if (opened) Qt.callLater(focusPanel)
    onVisibleChanged: if (visible && opened) Qt.callLater(focusPanel)
    onActiveChanged: if (session) session.setStreamMenuFocused(active)
    onClosing: function(close) {
        close.accepted = false
        if (session) session.closeStreamMenu(true)
    }

    component BodyText: Label {
        font.family: "Inter"
        font.pointSize: menu.bodyPointSize
        color: brand.foreground
        wrapMode: Text.Wrap
        textFormat: Text.PlainText
    }
    component MenuButton: Button {
        id: button
        implicitHeight: Math.max(48, buttonText.implicitHeight + 20)
        font.family: "Inter"
        font.pointSize: menu.bodyPointSize
        contentItem: Text {
            id: buttonText
            text: button.text
            font: button.font
            color: button.enabled ? brand.foreground : brand.muted
            textFormat: Text.PlainText
            wrapMode: Text.Wrap
            horizontalAlignment: Text.AlignHCenter
            verticalAlignment: Text.AlignVCenter
        }
        background: Rectangle {
            radius: 10
            color: button.down ? Qt.tint(brand.surface, "#334572fa") : brand.surface
            border.color: button.activeFocus || button.hovered ? brand.purple : brand.border
        }
    }

    FocusScope {
        anchors.fill: parent
        focus: true
        Keys.onEscapePressed: {
            if (menu.confirming) menu.controller.cancelDisconnect()
            else if (menu.session) menu.session.closeStreamMenu(true)
        }
        Shortcut {
            sequence: "Ctrl+Alt+Shift+O"
            enabled: menu.opened && menu.active
            onActivated: menu.session.closeStreamMenu(true)
        }
        ToolButton {
            objectName: "streamGuestsBubble"
            visible: !menu.opened
            width: 48
            height: 48
            icon.source: Qt.resolvedUrl("../res/icons/user-circle.svg")
            icon.color: brand.foreground
            icon.width: 26
            icon.height: 26
            Accessible.name: qsTr("Guests")
            ToolTip.visible: hovered
            ToolTip.text: qsTr("Guests · Ctrl+Alt+Shift+O")
            ToolTip.delay: 600
            onClicked: menu.session.toggleStreamMenu()
            background: Rectangle {
                radius: 24
                border.width: 1
                border.color: brand.purple
                gradient: Gradient {
                    GradientStop { position: 0; color: Qt.tint(brand.surface, Qt.rgba(brand.blue.r, brand.blue.g, brand.blue.b, 0.25)) }
                    GradientStop { position: 1; color: Qt.tint(brand.surface, Qt.rgba(brand.purple.r, brand.purple.g, brand.purple.b, 0.2)) }
                }
            }
        }
        Rectangle {
            id: panel
            objectName: "streamGuestsPanel"
            anchors.fill: parent
            visible: menu.opened
            color: Qt.rgba(brand.surface.r, brand.surface.g, brand.surface.b, 0.97)
            radius: 16
            border.color: brand.purple
            opacity: menu.opened ? 1 : 0
            Behavior on opacity { NumberAnimation { duration: menu.reducedMotion ? 0 : 150 } }

            ColumnLayout {
                anchors.fill: parent
                anchors.margins: 12
                spacing: 8
                RowLayout {
                    Layout.fillWidth: true
                    Image {
                        source: Qt.resolvedUrl("../res/spacecloud_wordmark.png")
                        Layout.preferredWidth: 110
                        Layout.preferredHeight: 40
                        fillMode: Image.PreserveAspectFit
                        Accessible.name: "Space Connect"
                        Accessible.role: Accessible.Graphic
                    }
                    Item { Layout.fillWidth: true }
                    MenuButton {
                        id: closeButton
                        objectName: "streamGuestsClose"
                        Layout.preferredWidth: 48
                        text: "\u00d7"
                        Accessible.name: qsTr("Close")
                        onClicked: menu.session.closeStreamMenu(true)
                    }
                }
                ScrollView {
                    id: scroll
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    Layout.minimumHeight: 0
                    clip: true
                    ScrollBar.horizontal.policy: ScrollBar.AlwaysOff
                    Column {
                        width: scroll.availableWidth
                        spacing: 12
                        BodyText {
                            width: parent.width
                            text: menu.confirming ? qsTr("Disconnect %1?").arg(menu.controller.confirmationName) : qsTr("Guests")
                            font.family: brand.headingFont
                            font.pointSize: menu.bodyPointSize + 3
                        }
                        BodyText {
                            width: parent.width
                            visible: menu.confirming
                            text: qsTr("This temporarily disconnects your friend's devices. They can reconnect. Your session, friendship and VM are preserved.")
                        }
                        BusyIndicator {
                            objectName: "streamGuestsBusy"
                            anchors.horizontalCenter: parent.horizontalCenter
                            width: 36
                            height: 36
                            visible: running
                            running: !!menu.controller && menu.controller.busy
                            Accessible.name: qsTr("Loading guests")
                        }
                        BodyText {
                            objectName: "streamGuestsError"
                            width: parent.width
                            visible: text.length > 0
                            text: menu.controller ? menu.controller.errorMessage : ""
                            color: brand.danger
                        }
                        BodyText {
                            objectName: "streamGuestsPartial"
                            width: parent.width
                            visible: !!menu.controller && menu.controller.partialResult.requested !== undefined
                            text: !visible ? "" : qsTr("Requested: %1 · Already disconnected: %2 · Failed: %3 · Not attempted: %4")
                                .arg(menu.controller.partialResult.requested).arg(menu.controller.partialResult.alreadyDisconnected)
                                .arg(menu.controller.partialResult.failed).arg(menu.controller.partialResult.notAttempted)
                        }
                        BodyText {
                            width: parent.width
                            text: menu.controller ? menu.controller.notice : ""
                            visible: text.length > 0 && !menu.confirming
                        }
                        BodyText {
                            width: parent.width
                            visible: !!menu.controller && menu.controller.loaded && !menu.controller.busy
                                     && menu.controller.guests.length === 0 && !menu.confirming
                            text: qsTr("No identified guests are connected.")
                        }
                        Repeater {
                            objectName: "streamGuestsList"
                            model: menu.controller && !menu.confirming && !menu.controller.busy
                                   && !menu.controller.errorMessage ? menu.controller.guests : []
                            delegate: MenuButton {
                                objectName: "streamGuest_" + modelData.userId
                                width: parent.width
                                implicitHeight: Math.max(64, summary.implicitHeight + 20)
                                Accessible.name: qsTr("Disconnect %1?").arg(modelData.displayName)
                                onClicked: menu.controller.requestDisconnect(modelData.userId)
                                contentItem: Column {
                                    id: summary
                                    spacing: 4
                                    BodyText {
                                        width: parent.width
                                        text: modelData.displayName
                                        wrapMode: Text.NoWrap
                                        elide: Text.ElideRight
                                    }
                                    BodyText {
                                        width: parent.width
                                        text: qsTr("%n device(s)", "", modelData.devices.length)
                                        color: brand.muted
                                    }
                                }
                            }
                        }
                        BodyText {
                            width: parent.width
                            visible: !!menu.controller && menu.controller.loaded && menu.controller.unidentifiedCount > 0 && !menu.confirming
                            text: menu.controller ? qsTr("%n unidentified connection(s). Ask your friend to reconnect with the updated app.",
                                                         "", menu.controller.unidentifiedCount) : ""
                            color: brand.muted
                        }
                    }
                }
                RowLayout {
                    Layout.fillWidth: true
                    MenuButton {
                        objectName: "streamGuestsRefresh"
                        Layout.fillWidth: true
                        visible: !menu.confirming
                        enabled: !!menu.controller && !menu.controller.busy
                        text: qsTr("Refresh")
                        onClicked: menu.controller.refresh()
                    }
                    MenuButton {
                        Layout.fillWidth: true
                        visible: menu.confirming
                        text: qsTr("Cancel")
                        onClicked: menu.controller.cancelDisconnect()
                    }
                    MenuButton {
                        objectName: "streamGuestConfirm"
                        Layout.fillWidth: true
                        visible: menu.confirming
                        enabled: !!menu.controller && !menu.controller.busy
                        text: qsTr("Disconnect")
                        onClicked: menu.controller.confirmDisconnect()
                    }
                }
            }
        }
    }
}
