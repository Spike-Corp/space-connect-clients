import QtQuick 2.9
import QtQuick.Controls 2.2
import QtQuick.Controls.Material 2.2
import QtQuick.Layouts 1.3
import LauncherApi 1.0

Item {
    id: loginView
    objectName: qsTr("Sign In")

    FontLoader {
        id: displayFontLoader
        source: "qrc:/fonts/orbitron_bold.ttf"
    }

    function attemptLogin() {
        errorLabel.visible = false
        if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(emailField.text)) {
            errorLabel.text = qsTr("Enter a valid email address.")
            errorLabel.visible = true
            return
        }
        if (passwordField.text.length === 0) {
            errorLabel.text = qsTr("Enter your password.")
            errorLabel.visible = true
            return
        }
        LauncherApi.login(emailField.text, passwordField.text)
    }

    Component.onCompleted: {
        if (LauncherApi.loggedIn) {
            stackView.replace("qrc:/gui/LauncherView.qml")
        } else if (LauncherApi.savedEmail) {
            emailField.text = LauncherApi.savedEmail
            passwordField.forceActiveFocus()
        }
    }

    Connections {
        target: LauncherApi
        function onLoginSucceeded() {
            stackView.replace("qrc:/gui/LauncherView.qml")
        }
        function onLoggedInChanged() {
            if (LauncherApi.loggedIn) {
                stackView.replace("qrc:/gui/LauncherView.qml")
            }
        }
        function onTwoFactorRequired() {
            twoFactorDialog.open()
        }
        function onErrorMessageChanged() {
            if (LauncherApi.errorMessage) {
                errorLabel.text = LauncherApi.errorMessage
                errorLabel.visible = true
            }
        }
    }

    Flickable {
        anchors.fill: parent
        contentWidth: width
        contentHeight: Math.max(height, centerColumn.height + 80)
        boundsBehavior: Flickable.OvershootBounds

        ColumnLayout {
            id: centerColumn
            anchors.horizontalCenter: parent.horizontalCenter
            y: Math.max(40, (parent.height - height) / 2)
            width: Math.min(420, parent.width - 60)
            spacing: 8

            Label {
                text: "SPACE CONNECT"
                font.family: displayFontLoader.name
                font.pixelSize: 30
                font.letterSpacing: 2
                color: "#a482fa"
                Layout.alignment: Qt.AlignHCenter
            }

            Label {
                text: qsTr("Sign in to start streaming")
                color: "#9793aa"
                font.pointSize: 12
                Layout.alignment: Qt.AlignHCenter
                Layout.bottomMargin: 20
            }

            Rectangle {
                Layout.fillWidth: true
                implicitHeight: fieldsColumn.implicitHeight + 48
                radius: 14
                color: "#110d17"
                border.width: 1
                border.color: "#e8e2ff"

                ColumnLayout {
                    id: fieldsColumn
                    anchors.fill: parent
                    anchors.margins: 24
                    spacing: 14

                    TextField {
                        id: emailField
                        Layout.fillWidth: true
                        placeholderText: qsTr("Email")
                        inputMethodHints: Qt.ImhEmailCharactersOnly
                        Keys.onReturnPressed: passwordField.forceActiveFocus()
                    }

                    TextField {
                        id: passwordField
                        Layout.fillWidth: true
                        placeholderText: qsTr("Password")
                        echoMode: eyeToggle.checked ? TextInput.Normal : TextInput.Password
                        rightPadding: 46
                        Keys.onReturnPressed: loginView.attemptLogin()

                        ToolButton {
                            id: eyeToggle
                            checkable: true
                            width: 34
                            height: 34
                            anchors.right: parent.right
                            anchors.rightMargin: 6
                            anchors.verticalCenter: parent.verticalCenter
                            focusPolicy: Qt.NoFocus
                            Accessible.name: checked ? qsTr("Hide password") : qsTr("Show password")

                            contentItem: Item {
                                implicitWidth: 22
                                implicitHeight: 22

                                Canvas {
                                    id: eyeCanvas
                                    anchors.fill: parent
                                    anchors.margins: 4
                                    property bool showing: eyeToggle.checked
                                    onShowingChanged: requestPaint()
                                    onPaint: {
                                        var ctx = getContext("2d")
                                        ctx.reset()
                                        var w = width, h = height
                                        var cx = w / 2, cy = h / 2
                                        var col = showing ? "#a482fa" : "#9793aa"
                                        ctx.strokeStyle = col
                                        ctx.fillStyle = col
                                        ctx.lineWidth = 1.6
                                        // eye outline
                                        ctx.beginPath()
                                        ctx.moveTo(1, cy)
                                        ctx.quadraticCurveTo(cx, -1, w - 1, cy)
                                        ctx.quadraticCurveTo(cx, h + 1, 1, cy)
                                        ctx.closePath()
                                        ctx.stroke()
                                        // pupil
                                        ctx.beginPath()
                                        ctx.arc(cx, cy, 2.4, 0, Math.PI * 2)
                                        ctx.fill()
                                        // slash when password is hidden
                                        if (!showing) {
                                            ctx.beginPath()
                                            ctx.moveTo(2, h - 2)
                                            ctx.lineTo(w - 2, 2)
                                            ctx.stroke()
                                        }
                                    }
                                }
                            }
                        }
                    }

                    SpaceCheckBox {
                        id: rememberMeCheckbox
                        Layout.fillWidth: true
                        text: qsTr("Remember me for 30 days on this device")
                        checked: LauncherApi.rememberMe
                        onCheckedChanged: LauncherApi.rememberMe = checked
                    }

                    Label {
                        id: errorLabel
                        visible: false
                        color: "#F87171"
                        wrapMode: Label.WordWrap
                        Layout.fillWidth: true
                    }

                    BusyIndicator {
                        visible: LauncherApi.busy
                        running: visible
                        Layout.alignment: Qt.AlignHCenter
                    }

                    Button {
                        text: qsTr("Sign In")
                        highlighted: true
                        enabled: !LauncherApi.busy
                        Layout.fillWidth: true
                        Layout.preferredHeight: 44
                        onClicked: loginView.attemptLogin()
                    }
                }
            }

            Label {
                text: qsTr("Forgot your password?")
                color: "#4572fa"
                font.underline: true
                Layout.alignment: Qt.AlignHCenter
                Layout.topMargin: 20
                MouseArea {
                    anchors.fill: parent
                    cursorShape: Qt.PointingHandCursor
                    onClicked: Qt.openUrlExternally("https://spacecloud.gg/panel/forgot-password")
                }
            }
        }
    }

    Dialog {
        id: twoFactorDialog
        title: qsTr("Two-factor authentication")
        modal: true
        anchors.centerIn: parent
        standardButtons: Dialog.Ok | Dialog.Cancel
        onAccepted: LauncherApi.verifyTwoFactor(twoFactorField.text)

        TextField {
            id: twoFactorField
            placeholderText: qsTr("6-digit code")
            inputMethodHints: Qt.ImhDigitsOnly
            maximumLength: 6
        }
    }
}
