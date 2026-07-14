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

    Connections {
        target: LauncherApi
        function onLoginSucceeded() {
            stackView.replace("qrc:/gui/LauncherView.qml")
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
                color: "#9B6BFF"
                Layout.alignment: Qt.AlignHCenter
            }

            Label {
                text: qsTr("Sign in to start streaming")
                color: "#A79BC9"
                font.pointSize: 12
                Layout.alignment: Qt.AlignHCenter
                Layout.bottomMargin: 20
            }

            Rectangle {
                Layout.fillWidth: true
                implicitHeight: fieldsColumn.implicitHeight + 48
                radius: 14
                color: "#201A33"
                border.width: 1
                border.color: "#E4D9FF"

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
                        echoMode: TextInput.Password
                        Keys.onReturnPressed: loginView.attemptLogin()
                    }

                    Label {
                        id: errorLabel
                        visible: false
                        color: "#FF5D5D"
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
                color: "#2EC4B6"
                font.underline: true
                Layout.alignment: Qt.AlignHCenter
                Layout.topMargin: 20
                MouseArea {
                    anchors.fill: parent
                    cursorShape: Qt.PointingHandCursor
                    onClicked: Qt.openUrlExternally("https://spacecloud.gg/forgot-password")
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
