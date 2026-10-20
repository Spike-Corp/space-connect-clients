pragma Singleton
import QtQuick 2.9

// SpaceTheme — fonte única de verdade do design do Space Connect desktop.
// Espelha o Brandbook v1.1 do site (frontend-v2/globals.css): mesmas cores,
// mesmas fontes (Bai Jamjuree p/ títulos, Inter p/ corpo), mesmos raios/espacos.
// Relativo a este arquivo para resolver tanto no qrc (build) quanto no
// harness de preview em disco.
QtObject {
    id: theme

    // ---------- Fontes (exatamente as do site) ----------
    property FontLoader _heading600: FontLoader { source: Qt.resolvedUrl("../../res/fonts/bai-jamjuree-600-normal.ttf") }
    property FontLoader _heading700: FontLoader { source: Qt.resolvedUrl("../../res/fonts/bai-jamjuree-700-normal.ttf") }
    property FontLoader _body400: FontLoader { source: Qt.resolvedUrl("../../res/fonts/inter-400-normal.ttf") }
    property FontLoader _body500: FontLoader { source: Qt.resolvedUrl("../../res/fonts/inter-500-normal.ttf") }
    property FontLoader _body600: FontLoader { source: Qt.resolvedUrl("../../res/fonts/inter-600-normal.ttf") }

    readonly property string headingFont: _heading700.name   // "Bai Jamjuree"
    readonly property string bodyFont: _body400.name          // "Inter"

    // ---------- Cores (Brandbook v1.1) ----------
    readonly property color bg:          "#0d0816"  // black-space (janela)
    readonly property color bgRaised:    "#110d17"  // dark-space (cards)
    readonly property color bgPure:      "#0b080a"
    readonly property color surface:     "#17111f"  // superfície levemente elevada
    readonly property color surfaceHover:"#1e1729"  // hover de card/linha
    readonly property color fg:          "#f8f5ff"  // white-soft
    readonly property color fgMuted:     "#9793aa"  // text-muted
    readonly property color kicker:      "#a993ff"  // rótulos/kicker
    readonly property color kickerLight: "#e8e2ff"
    readonly property color blue:        "#4572fa"  // brand-blue
    readonly property color purple:      "#a482fa"  // brand-purple
    readonly property color darkPurple:  "#231e2c"
    readonly property color green:       "#4cffa9"  // sucesso
    readonly property color orange:      "#ff974c"  // aviso
    readonly property color red:         "#ff4c6d"  // erro

    // Bordas sutis como no site (branco em baixa opacidade + marca)
    readonly property color border:       Qt.rgba(1, 1, 1, 0.10)   // border-white/10
    readonly property color borderSoft:   Qt.rgba(1, 1, 1, 0.06)
    readonly property color borderStrong: Qt.rgba(1, 1, 1, 0.20)
    readonly property color borderBrand:  Qt.rgba(0.643, 0.510, 0.980, 0.45) // #a482fa/45
    readonly property color overlay:      Qt.rgba(0, 0, 0, 0.60)

    // Gradiente de CTA do site: linear 95deg, #4572fa -> #a482fa
    readonly property color gradStart: "#4572fa"
    readonly property color gradEnd:   "#a482fa"

    // ---------- Raios ----------
    readonly property int radiusSm:   8
    readonly property int radiusMd:   12   // rounded-xl
    readonly property int radiusLg:   16   // rounded-2xl
    readonly property int radiusXl:   24
    readonly property int radiusPill: 999

    // ---------- Espaçamento (grid de 4px) ----------
    readonly property int space1: 4
    readonly property int space2: 8
    readonly property int space3: 12
    readonly property int space4: 16
    readonly property int space5: 24
    readonly property int space6: 32
    readonly property int space7: 48

    // ---------- Escala tipográfica (pt) ----------
    readonly property int fsKicker:  10
    readonly property int fsCaption: 11
    readonly property int fsBody:    12
    readonly property int fsBodyLg:  14
    readonly property int fsTitle:   18
    readonly property int fsH3:      20
    readonly property int fsH2:      26
    readonly property int fsH1:      34

    // Espaçamento de letra dos CTAs do site (uppercase tracking 0.083em)
    readonly property real ctaTracking: 1.2
}
