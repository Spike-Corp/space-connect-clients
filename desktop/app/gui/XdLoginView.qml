import QtQuick 2.9
import QtQuick.Controls 2.3
import QtQuick.Layouts 1.3
import XdApi 1.0

// XD CONSOLE — login exclusivo da staff (conta admin do site spacecloud.gg).
// Usa /auth/login + /auth/2fa/verify (sessão própria, separada do launcher).
Item {
    id: xdLoginView
    objectName: "XD Console"

    property bool twoFactorMode: false

    Component.onCompleted: {
        // Sessão salva de antes? Já entra direto no console.
        if (XdApi.loggedIn)
            stackView.push("qrc:/gui/XdView.qml")
        else
            XdApi.tryAutoLogin()
    }

    Connections {
        target: XdApi
        function onTwoFactorRequired() { twoFactorMode = true }
        function onLoggedInChanged() {
            if (XdApi.loggedIn) {
                twoFactorMode = false
                stackView.push("qrc:/gui/XdView.qml")
            }
        }
    }

    Rectangle {
        anchors.fill: parent
        color: "#0d0816"
    }

    ColumnLayout {
        anchors.centerIn: parent
        width: Math.min(420, parent.width - 60)
        spacing: 14

        Label {
            text: "XD CONSOLE"
            color: "#a482fa"
            font.pixelSize: 32
            font.bold: true
            Layout.alignment: Qt.AlignHCenter
        }
        Label {
            text: qsTr("Painel da equipe SpaceCloud — acesso restrito")
            color: "#9793aa"
            font.pixelSize: 13
            Layout.alignment: Qt.AlignHCenter
        }

        Rectangle {
            Layout.fillWidth: true
            Layout.topMargin: 12
            implicitHeight: formColumn.implicitHeight + 40
            color: "#110d17"
            radius: 10
            border.color: "#2a2140"

            ColumnLayout {
                id: formColumn
                anchors.fill: parent
                anchors.margins: 20
                spacing: 12

                TextField {
                    id: emailField
                    Layout.fillWidth: true
                    visible: !twoFactorMode
                    placeholderText: qsTr("E-mail da equipe")
                    color: "#e8e2ff"
                    selectByMouse: true
                    onAccepted: passwordField.forceActiveFocus()
                }
                TextField {
                    id: passwordField
                    Layout.fillWidth: true
                    visible: !twoFactorMode
                    placeholderText: qsTr("Senha")
                    echoMode: TextInput.Password
                    color: "#e8e2ff"
                    selectByMouse: true
                    onAccepted: loginButton.clicked()
                }
                TextField {
                    id: codeField
                    Layout.fillWidth: true
                    visible: twoFactorMode
                    placeholderText: qsTr("Código 2FA (6 dígitos)")
                    color: "#e8e2ff"
                    selectByMouse: true
                    inputMethodHints: Qt.ImhDigitsOnly
                    onAccepted: loginButton.clicked()
                }

                Label {
                    Layout.fillWidth: true
                    visible: XdApi.errorMessage.length > 0
                    text: XdApi.errorMessage
                    color: "#F87171"
                    font.pixelSize: 12
                    wrapMode: Text.WordWrap
                }

                Button {
                    id: loginButton
                    Layout.fillWidth: true
                    enabled: !XdApi.busy
                    text: XdApi.busy ? qsTr("Entrando…") : (twoFactorMode ? qsTr("Verificar código") : qsTr("Entrar"))
                    onClicked: {
                        if (twoFactorMode)
                            XdApi.verifyTwoFactor(codeField.text)
                        else
                            XdApi.login(emailField.text, passwordField.text)
                    }
                    contentItem: Label {
                        text: loginButton.text
                        color: "#ffffff"
                        font.bold: true
                        horizontalAlignment: Qt.AlignHCenter
                        verticalAlignment: Qt.AlignVCenter
                    }
                    background: Rectangle {
                        radius: 6
                        color: loginButton.enabled ? (loginButton.down ? "#3a5fd9" : "#4572fa") : "#2a2140"
                    }
                }
            }
        }

        Label {
            Layout.alignment: Qt.AlignHCenter
            text: qsTr("Ações ficam registradas em auditoria.")
            color: "#5b5470"
            font.pixelSize: 11
        }
    }
}
