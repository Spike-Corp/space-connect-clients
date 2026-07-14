import QtQuick 2.9
import QtQuick.Controls 2.2

import SdlGamepadKeyNavigation 1.0
import SystemProperties 1.0

// https://stackoverflow.com/questions/45029968/how-do-i-set-the-combobox-width-to-fit-the-largest-item
ComboBox {
    property int textWidth
    property int desiredWidth : leftPadding + textWidth + indicator.width + rightPadding
    // Fall back to desiredWidth (i.e. "don't clamp") whenever the parent's width isn't a real,
    // resolved value yet. Anchored/positioner-based layout containers (e.g. a Column with
    // anchors.fill: parent inside a GroupBox) can report parent.width as 0 for a brief moment
    // (or, in some column arrangements, indefinitely) right when this control is first created -
    // clamping straight to that 0 permanently truncated the combo box to nothing, since
    // implicitWidth would then be stuck at 0 (e.g. "Som surround 5.1/7.1" rendering as
    // "Som surro..."). Once the parent genuinely has a resolved width, this correctly clamps
    // again like before.
    property int maximumWidth : (parent && parent.width > 0) ? parent.width : desiredWidth

    implicitWidth: desiredWidth < maximumWidth ? desiredWidth : maximumWidth

    TextMetrics {
        id: popupMetrics
    }

    TextMetrics {
        id: textMetrics
    }

    function recalculateWidth() {
        textMetrics.font = font
        popupMetrics.font = popup.font
        textWidth = 0
        for (var i = 0; i < count; i++){
            textMetrics.text = textAt(i)
            popupMetrics.text = textAt(i)
            textWidth = Math.max(textMetrics.width, textWidth)
            textWidth = Math.max(popupMetrics.width, textWidth)
        }
        // Small safety margin: TextMetrics' measured width can end up a bit narrower than what
        // the actual rendered Text needs (font hinting/kerning/rounding differences between the
        // metrics calculation and real glyph layout), which was silently eliding the last few
        // characters of longer strings (e.g. "Som surround 7.1" rendering as "Som surro...")
        // even though the computed width looked "close enough". Padding it out avoids that.
        textWidth += 40
    }

    // We call this every time the options change (and init)
    // so we can adjust the combo box width here too
    onActivated: recalculateWidth()

    // Belt-and-suspenders re-measurement, shortly after creation. The immediate
    // Component.onCompleted call below can still under-measure in some cases (e.g. this
    // control's font not having finished resolving to the app's custom "Rajdhani" font yet, or
    // an ancestor layout not having settled its final width yet on the very first pass) - a
    // second pass a moment later, once everything has settled, corrects any such stale/undersized
    // measurement instead of leaving the combo box permanently truncated.
    Timer {
        interval: 250
        running: true
        repeat: false
        onTriggered: recalculateWidth()
    }

    // NOTE: onActivated above is NOT reliable for the initial sizing. Several SettingsView.qml
    // combo boxes (e.g. the audio configuration one - "Som surround 5.1/7.1" being truncated is
    // the symptom) define their OWN "onActivated: { ... }" handler (to save the selected
    // preference) and then call the "activated(currentIndex)" signal manually from
    // Component.onCompleted to initialize things. In QML, a signal can only have one directly
    // bound handler per object - the instance's own "onActivated" REPLACES (shadows) this base
    // type's "onActivated: recalculateWidth()" entirely, so recalculateWidth() silently never
    // runs and the combo box (and its popup) stay at their tiny textWidth: 0 default width,
    // truncating every item's text. Component.onCompleted handlers, unlike plain signals, DO
    // chain across a type's inheritance/composition hierarchy (base type + instance both fire),
    // so doing the initial sizing here instead is immune to being shadowed by an instance's own
    // Component.onCompleted or onActivated overrides.
    Component.onCompleted: recalculateWidth()

    popup.onAboutToShow: {
        // Switch to normal navigation for combo boxes
        SdlGamepadKeyNavigation.setUiNavMode(false)

        // Override the popup color to improve contrast with the overridden
        // Material 2 background color set in main.qml.
        if (SystemProperties.usesMaterial3Theme) {
            popup.background.color = "#424242"
        }
    }

    popup.onAboutToHide: {
        SdlGamepadKeyNavigation.setUiNavMode(true)
    }
}
