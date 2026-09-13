import QtQuick 2.9
import QtQuick.Controls 2.2
import QtQuick.Layouts 1.3
import LauncherApi 1.0
import ComputerManager 1.0

Item {
    id: launcherView
    objectName: qsTr("SpaceCloud")
    property bool addingComputer: false

    function crimsonText() {
        if (LauncherApi.crimsonState === "ready") return qsTr("Play")
        if (LauncherApi.crimsonState === "error") return qsTr("Try again")
        if (LauncherApi.crimsonState === "idle") return qsTr("Play")
        return qsTr("Preparing...")
    }

    function crimsonLoadingTitle() {
        if (LauncherApi.state === "queued")
            return qsTr("Waiting in queue...")
        if (LauncherApi.crimsonState === "provisioning")
            return qsTr("Starting your machine...")
        if (LauncherApi.crimsonState === "logging-in")
            return qsTr("Preparing game access...")
        if (LauncherApi.crimsonState === "downloading")
            return qsTr("Preparing Crimson Desert...")
        if (LauncherApi.crimsonState === "launching")
            return qsTr("Launching game...")
        return qsTr("Connecting...")
    }

    function crimsonLoadingDetails() {
        if (LauncherApi.state === "queued")
            return qsTr("Position %1 of %2").arg(LauncherApi.queuePosition).arg(LauncherApi.queueTotal)
        if (LauncherApi.crimsonState === "downloading")
            return qsTr("%1% ready on the dedicated machine").arg(LauncherApi.crimsonDownloadPercent)
        if (LauncherApi.machineName)
            return qsTr("%1 · %2 minutes remaining").arg(LauncherApi.machineName).arg(LauncherApi.remainingMinutes)
        return qsTr("This can take a few minutes while the dedicated GPU is prepared.")
    }

    Connections {
        target: LauncherApi
        function onConnectionReady(address) {
            addingComputer = true
            ComputerManager.addNewHostManually(address)
        }
        function onCrimsonConnectionReady(address) {
            addingComputer = true
            ComputerManager.addNewHostManually(address)
        }
        function onLoggedInChanged() {
            if (!LauncherApi.loggedIn) stackView.replace("qrc:/gui/LoginView.qml")
        }
    }

    Connections {
        target: ComputerManager
        function onComputerAddCompleted(success, detectedPortBlocking) {
            if (!addingComputer) return
            addingComputer = false
            if (success) stackView.replace("qrc:/gui/PcView.qml")
            else errorDialog.open()
        }
    }

    Timer {
        interval: 5000
        repeat: true
        running: true
        triggeredOnStart: true
        onTriggered: LauncherApi.refreshStatus()
    }

    Rectangle {
        anchors.fill: parent
        color: "#101114"
    }

    RowLayout {
        anchors.fill: parent
        spacing: 0

        Rectangle {
            Layout.fillHeight: true
            Layout.preferredWidth: 205
            color: "#0C0D0F"
            border.color: "#282B30"
            border.width: 1

            ColumnLayout {
                anchors.fill: parent
                anchors.margins: 14
                spacing: 4

                Label {
                    text: "SPACE" + "<font color='#7291FF'>CLOUD</font>"
                    textFormat: Text.RichText
                    font.pixelSize: 20
                    font.bold: true
                    Layout.leftMargin: 10
                    Layout.bottomMargin: 30
                }

                Label {
                    text: qsTr("LIBRARY")
                    color: "#626770"
                    font.pixelSize: 10
                    font.bold: true
                    Layout.leftMargin: 10
                }

                Button {
                    text: qsTr("▦   All games")
                    highlighted: true
                    Layout.fillWidth: true
                }
                Button {
                    text: qsTr("♡   Favorites")
                    Layout.fillWidth: true
                }
                Button {
                    text: qsTr("↺   Recent")
                    Layout.fillWidth: true
                }

                Item { Layout.fillHeight: true }

                Label {
                    text: LauncherApi.email
                    color: "#8C9099"
                    elide: Text.ElideMiddle
                    Layout.fillWidth: true
                }
                Button {
                    text: qsTr("Sign out")
                    Layout.fillWidth: true
                    onClicked: LauncherApi.logout()
                }
            }
        }

        ColumnLayout {
            Layout.fillWidth: true
            Layout.fillHeight: true
            Layout.margins: 28
            spacing: 18

            RowLayout {
                Layout.fillWidth: true
                Label {
                    text: qsTr("Games")
                    color: "#F2F3F5"
                    font.pixelSize: 26
                    font.bold: true
                }
                Item { Layout.fillWidth: true }
                TextField {
                    placeholderText: qsTr("Search games")
                    Layout.preferredWidth: 260
                }
            }

            RowLayout {
                Layout.fillWidth: true
                spacing: 18
                Label { text: qsTr("My Library"); color: "#F2F3F5"; font.pixelSize: 20; font.bold: true }
                Label { text: qsTr("4 games"); color: "#8C9099"; font.pixelSize: 12 }
                Item { Layout.fillWidth: true }
                Button { text: qsTr("All") }
                Button { text: qsTr("Available") }
                Button { text: qsTr("Coming soon") }
            }

            RowLayout {
                Layout.fillWidth: true
                spacing: 14

                Repeater {
                    model: [
                        { title: "Crimson Desert", image: "https://cdn1.epicgames.com/spt-assets/f9616d900e1048a29ee9ffe9523c1594/crimson-desert-191ki.jpg", available: true },
                        { title: "Elden Ring", image: "https://metagalaxia.com.br/wp-content/uploads/2024/07/Elden-Ring-Shadow-of-the-Erdtree.webp", available: false },
                        { title: "Red Dead Redemption 2", image: "https://images7.alphacoders.com/749/thumb-1920-749807.png", available: false },
                        { title: "Cyberpunk 2077", image: "https://images2.alphacoders.com/131/thumb-1920-1317789.jpeg", available: false }
                    ]

                    delegate: Rectangle {
                        Layout.fillWidth: true
                        Layout.preferredHeight: 190
                        color: "#1A1C20"
                        border.color: "#282B30"
                        radius: 5
                        opacity: modelData.available ? 1 : 0.62

                        Column {
                            anchors.fill: parent
                            spacing: 8
                            Image {
                                width: parent.width
                                height: 125
                                source: modelData.image
                                fillMode: Image.PreserveAspectCrop
                                opacity: modelData.available ? 1 : 0.55
                            }
                            Label {
                                text: modelData.title
                                color: "#F2F3F5"
                                font.pixelSize: 13
                                font.bold: true
                                elide: Text.ElideRight
                                anchors.left: parent.left
                                anchors.leftMargin: 10
                                anchors.right: parent.right
                                anchors.rightMargin: 10
                            }
                            Label {
                                text: modelData.available ? qsTr("Steam · Available") : qsTr("Coming soon")
                                color: modelData.available ? "#54D99B" : "#9B8BCE"
                                font.pixelSize: 10
                                anchors.left: parent.left
                                anchors.leftMargin: 10
                            }
                        }

                        Button {
                            visible: modelData.available
                            text: crimsonText()
                            anchors.right: parent.right
                            anchors.bottom: parent.bottom
                            anchors.rightMargin: 8
                            anchors.bottomMargin: 8
                            enabled: !LauncherApi.busy && !addingComputer
                            onClicked: LauncherApi.startCrimsonDesert()
                        }
                    }
                }
            }

            Rectangle {
                Layout.fillWidth: true
                implicitHeight: statusColumn.implicitHeight + 28
                color: "#15171B"
                border.color: "#282B30"
                radius: 5

                ColumnLayout {
                    id: statusColumn
                    anchors.fill: parent
                    anchors.margins: 14
                    spacing: 8

                    Label {
                        text: LauncherApi.crimsonState === "downloading"
                              ? qsTr("Preparing Crimson Desert · %1%").arg(LauncherApi.crimsonDownloadPercent)
                              : qsTr("Select Crimson Desert to start a game-only session")
                        color: "#9B9BA8"
                        Layout.fillWidth: true
                    }
                    Label {
                        visible: LauncherApi.errorMessage.length > 0
                        text: LauncherApi.errorMessage
                        color: "#FF5D5D"
                        wrapMode: Text.WordWrap
                        Layout.fillWidth: true
                    }
                    BusyIndicator {
                        running: LauncherApi.busy || addingComputer
                        visible: running
                        Layout.alignment: Qt.AlignLeft
                    }
                    RowLayout {
                        Layout.fillWidth: true
                        Label {
                            text: qsTr("The stream opens automatically when the game is ready.")
                            color: "#626770"
                            font.pixelSize: 11
                            Layout.fillWidth: true
                        }
                        Button {
                            text: qsTr("Refresh")
                            onClicked: LauncherApi.refreshStatus()
                        }
                    }
                }
            }
        }
    }

    Rectangle {
        id: gameLoading
        anchors.fill: parent
        z: 20
        visible: LauncherApi.state === "queued"
                 || (LauncherApi.crimsonState !== "idle"
                     && LauncherApi.crimsonState !== "ready"
                     && LauncherApi.crimsonState !== "error")
        color: "#101114"

        Image {
            anchors.fill: parent
            source: "https://cdn1.epicgames.com/spt-assets/f9616d900e1048a29ee9ffe9523c1594/crimson-desert-191ki.jpg"
            fillMode: Image.PreserveAspectCrop
            opacity: 0.42
        }

        Rectangle {
            anchors.fill: parent
            color: "#101114"
            opacity: 0.72
        }

        ColumnLayout {
            anchors.left: parent.left
            anchors.leftMargin: Math.max(30, parent.width * 0.08)
            anchors.verticalCenter: parent.verticalCenter
            width: Math.min(510, parent.width - 60)
            spacing: 14

            Label {
                text: qsTr("CRIMSON DESERT")
                color: "#9B9BA8"
                font.pixelSize: 11
                font.bold: true
                Layout.fillWidth: true
            }

            Label {
                text: crimsonLoadingTitle()
                color: "#F2F3F5"
                font.pixelSize: 34
                font.bold: true
                Layout.fillWidth: true
            }

            Label {
                text: crimsonLoadingDetails()
                color: "#C5C7CE"
                wrapMode: Text.WordWrap
                Layout.fillWidth: true
            }

            Rectangle {
                Layout.fillWidth: true
                height: 4
                color: "#3A3D43"

                Rectangle {
                    width: LauncherApi.crimsonDownloadPercent > 0
                           ? parent.width * LauncherApi.crimsonDownloadPercent / 100
                           : parent.width * 0.32
                    height: parent.height
                    color: "#54D99B"
                }
            }

            Label {
                visible: LauncherApi.state === "queued"
                text: qsTr("Your place is held while the machine becomes available.")
                color: "#8C9099"
                font.pixelSize: 11
                Layout.fillWidth: true
            }

            BusyIndicator {
                running: gameLoading.visible
                Layout.alignment: Qt.AlignLeft
            }
        }
    }

    Dialog {
        id: errorDialog
        title: qsTr("Connection failed")
        standardButtons: Dialog.Ok
        anchors.centerIn: parent
        Label {
            text: qsTr("The Moonlight host is not ready yet. Try again in a few seconds.")
            wrapMode: Text.WordWrap
        }
    }
}
