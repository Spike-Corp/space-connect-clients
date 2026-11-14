import QtQuick 2.15
import QtQuick.Window 2.15

Window {
    id: loadingWindow
    property var orchestrator
    property var dashboardWindow
    property var tips: []
    transientParent: null
    width: screen.width
    height: screen.height
    visible: false
    color: "transparent"
    title: "Space Connect"
    flags: Qt.Window | Qt.FramelessWindowHint

    function sync() {
        if (orchestrator.active && orchestrator.phase !== "streaming") {
            reveal.stop()
            opacity = 1
            if (!visible) {
                if (dashboardWindow)
                    screen = dashboardWindow.screen
                showFullScreen()
                raise()
                requestActivate()
            }
        }
        if ((!orchestrator.active || orchestrator.phase === "streaming") && visible && !reveal.running)
            reveal.start()
    }

    Connections {
        target: loadingWindow.orchestrator
        function onChanged() { loadingWindow.sync() }
    }
    onClosing: function(close) {
        close.accepted = false
        orchestrator.cancel()
    }
    LoadingOverlay {
        anchors.fill: parent
        statusText: loadingWindow.orchestrator ? loadingWindow.orchestrator.statusText : ""
        reducedMotion: loadingWindow.orchestrator ? loadingWindow.orchestrator.reducedMotion : false
        backgroundAvailable: !!loadingWindow.orchestrator
                             && ["allocating", "queued", "booting"].indexOf(loadingWindow.orchestrator.phase) >= 0
        canBackground: !!loadingWindow.orchestrator && !!loadingWindow.orchestrator.canBackground
        tips: loadingWindow.tips
        onCancelRequested: loadingWindow.orchestrator.cancel()
        onBackgroundRequested: loadingWindow.orchestrator.followInBackground()
    }
    NumberAnimation {
        id: reveal
        target: loadingWindow
        property: "opacity"
        to: 0
        duration: loadingWindow.orchestrator && loadingWindow.orchestrator.reducedMotion ? 0 : 500
        easing.type: Easing.OutQuad
        onFinished: {
            var activateStream = loadingWindow.active
            loadingWindow.hide()
            if (loadingWindow.orchestrator.active && loadingWindow.orchestrator.phase === "streaming") {
                if (loadingWindow.dashboardWindow)
                    loadingWindow.dashboardWindow.visible = false
                loadingWindow.orchestrator.completeReveal(activateStream)
            }
        }
    }
}
