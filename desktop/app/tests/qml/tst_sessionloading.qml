import QtQuick 2.9
import QtTest 1.2
import "../../gui" as Connect

TestCase {
    id: testCase
    name: "SessionLoading"
    when: windowShown
    width: 800
    height: 600

    QtObject {
        id: progress
        property bool active: false
        property string phase: "idle"
        property string statusText: ""
        property bool reducedMotion: false
        property int reveals: 0
        signal changed()
        function completeReveal() { reveals++ }
        function cancel() { active = false; phase = "cancelled"; changed() }
    }

    Component {
        id: loadingFactory
        Connect.SessionLoadingWindow { orchestrator: progress }
    }
    Component {
        id: tickerFactory
        Connect.StatusTicker { width: 600; height: implicitHeight }
    }

    function init() {
        progress.active = false
        progress.phase = "idle"
        progress.statusText = ""
        progress.reducedMotion = false
        progress.reveals = 0
    }

    function test_overlay_waits_for_video_not_handshake() {
        var loading = createTemporaryObject(loadingFactory, testCase)
        verify(loading)
        progress.active = true
        var phases = ["queued", "booting", "pairing", "connecting", "video"]
        for (var i = 0; i < phases.length; i++) {
            progress.phase = phases[i]
            progress.statusText = phases[i]
            progress.changed()
            wait(30)
            compare(loading.visible, true)
            compare(loading.opacity, 1)
            compare(progress.reveals, 0)
        }
        progress.phase = "streaming"
        progress.changed()
        wait(180)
        verify(loading.visible)
        verify(loading.opacity > 0 && loading.opacity < 1)
        tryCompare(loading, "visible", false, 1500)
        compare(progress.reveals, 1)
    }

    function test_cancel_does_not_reveal_late_frame() {
        var loading = createTemporaryObject(loadingFactory, testCase)
        progress.active = true
        progress.phase = "pairing"
        progress.changed()
        tryCompare(loading, "visible", true)
        progress.cancel()
        progress.phase = "streaming"
        progress.changed()
        tryCompare(loading, "visible", false, 1500)
        compare(progress.reveals, 0)
    }

    function test_reduced_motion_reveals_without_animation() {
        var loading = createTemporaryObject(loadingFactory, testCase)
        progress.reducedMotion = true
        progress.active = true
        progress.phase = "video"
        progress.changed()
        tryCompare(loading, "visible", true)
        progress.phase = "streaming"
        progress.changed()
        tryCompare(loading, "visible", false)
        compare(progress.reveals, 1)
    }

    function test_retry_during_cancel_fade_keeps_overlay() {
        var loading = createTemporaryObject(loadingFactory, testCase)
        progress.active = true
        progress.phase = "pairing"
        progress.changed()
        tryCompare(loading, "visible", true)
        progress.cancel()
        wait(150)
        verify(loading.opacity > 0 && loading.opacity < 1)
        progress.active = true
        progress.phase = "allocating"
        progress.changed()
        wait(600)
        compare(loading.visible, true)
        compare(loading.opacity, 1)
        compare(progress.reveals, 0)
    }

    function test_status_crossfade_coalesces_rapid_updates() {
        var ticker = createTemporaryObject(tickerFactory, testCase, {text: "Fila: 2"})
        var first = findChild(ticker, "statusTextA")
        var second = findChild(ticker, "statusTextB")
        verify(first && second)
        compare(first.text, "Fila: 2")
        ticker.text = "Ligando"
        wait(90)
        verify(first.opacity > 0 && first.opacity < 1)
        verify(second.opacity > 0 && second.opacity < 1)
        ticker.text = "Autenticando"
        ticker.text = "Preparando a imagem"
        wait(600)
        var visibleText = first.opacity > second.opacity ? first : second
        compare(visibleText.text, "Preparando a imagem")
        compare(visibleText.opacity, 1)
    }

    function test_status_reduced_motion_and_long_copy() {
        var ticker = createTemporaryObject(tickerFactory, testCase, {
            width: 240, text: "Fila", reducedMotion: true
        })
        ticker.text = "Estamos preparando sua máquina com todos os seus arquivos. Aguarde a conexão segura."
        var first = findChild(ticker, "statusTextA")
        compare(first.text, ticker.text)
        compare(first.opacity, 1)
        verify(first.implicitHeight > 28)
        compare(first.width, 240)
    }
}
