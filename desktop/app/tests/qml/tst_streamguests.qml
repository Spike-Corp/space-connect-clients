import QtQuick 2.15
import QtTest 1.2
import "../../gui" as Connect

TestCase {
    id: testCase
    name: "StreamGuestsMenu"
    when: windowShown
    width: 1000
    height: 700

    QtObject {
        id: guests
        property bool ownerSession: true
        property bool busy: false
        property bool loaded: true
        property var guests: [
            {userId: "friend", displayName: "Friend with a long account name", devices: [{platform: "android"}, {platform: "windows"}]}
        ]
        property int unidentifiedCount: 1
        property string errorMessage: ""
        property string notice: ""
        property var partialResult: ({})
        property string confirmationName: ""
        property int confirmed: 0
        function refresh() { busy = true }
        function requestDisconnect(id) { if (id === "friend") confirmationName = this.guests[0].displayName }
        function cancelDisconnect() { confirmationName = "" }
        function confirmDisconnect() { if (confirmationName) { confirmed++; confirmationName = ""; busy = true } }
    }
    QtObject {
        id: stream
        property var streamGuests: guests
        property rect streamMenuBounds: Qt.rect(100, 50, 900, 640)
        property bool streamMenuVisible: true
        property bool streamMenuOpen: false
        property var streamMenuParent: null
        property bool returnFocus: false
        signal streamMenuChanged()
        function toggleStreamMenu() { streamMenuOpen = !streamMenuOpen; streamMenuChanged() }
        function closeStreamMenu(restore) { returnFocus = restore; streamMenuOpen = false; streamMenuChanged() }
        function setStreamMenuFocused(active) {}
    }
    Component {
        id: menuFactory
        Connect.StreamSideMenuWindow { session: stream; reducedMotion: true }
    }

    function init() {
        guests.ownerSession = true
        guests.busy = false
        guests.loaded = true
        guests.errorMessage = ""
        guests.notice = ""
        guests.confirmationName = ""
        guests.confirmed = 0
        guests.partialResult = ({})
        stream.streamMenuBounds = Qt.rect(100, 50, 900, 640)
        stream.streamMenuVisible = true
        stream.streamMenuOpen = false
        stream.returnFocus = false
    }

    function test_bubble_is_local_to_the_stream_and_has_a_real_touch_target() {
        var menu = createTemporaryObject(menuFactory, testCase)
        verify(menu)
        tryCompare(menu, "visible", true)
        compare(menu.width, 48)
        compare(menu.height, 48)
        verify(menu.x >= stream.streamMenuBounds.x)
        verify(menu.x + menu.width <= stream.streamMenuBounds.x + stream.streamMenuBounds.width)
        var bubble = findChild(menu, "streamGuestsBubble")
        verify(bubble && bubble.width >= 48 && bubble.height >= 48)
        mouseClick(bubble)
        tryCompare(stream, "streamMenuOpen", true)
        verify(menu.width > 48)
        verify(findChild(menu, "streamGuestsPanel").visible)
        var close = findChild(menu, "streamGuestsClose")
        mouseClick(close)
        tryCompare(menu, "width", 48)
        compare(stream.returnFocus, true)
    }

    function test_friend_selection_requires_confirmation_and_does_not_block_close() {
        stream.streamMenuOpen = true
        var menu = createTemporaryObject(menuFactory, testCase)
        tryCompare(menu, "visible", true)
        verify(waitForRendering(menu.contentItem))
        var list = findChild(menu, "streamGuestsList")
        compare(list.count, 1)
        var friend = list.itemAt(0)
        verify(friend && friend.height >= 48 && friend.width > 0)
        mouseClick(friend)
        tryCompare(guests, "confirmationName", guests.guests[0].displayName)
        compare(guests.confirmed, 0)
        var confirm = findChild(menu, "streamGuestConfirm")
        verify(confirm && confirm.visible && confirm.height >= 48)
        mouseClick(confirm)
        compare(guests.confirmed, 1)
        verify(findChild(menu, "streamGuestsBusy").running)
        mouseClick(findChild(menu, "streamGuestsClose"))
        compare(stream.streamMenuOpen, false)
    }

    function test_guests_errors_and_partial_counts_remain_visible() {
        stream.streamMenuOpen = true
        guests.errorMessage = "Could not complete the operation."
        guests.partialResult = ({requested: 1, alreadyDisconnected: 2, failed: 1, notAttempted: 3})
        var menu = createTemporaryObject(menuFactory, testCase)
        tryCompare(menu, "visible", true)
        compare(findChild(menu, "streamGuestsError").text, guests.errorMessage)
        var partial = findChild(menu, "streamGuestsPartial")
        verify(partial.visible && partial.text.indexOf("1") >= 0 && partial.text.indexOf("2") >= 0 && partial.text.indexOf("3") >= 0)
        mouseClick(findChild(menu, "streamGuestsRefresh"))
        verify(guests.busy)
    }

    function test_small_stream_scrolls_content_without_losing_close_or_confirm() {
        stream.streamMenuBounds = Qt.rect(0, 0, 480, 240)
        stream.streamMenuOpen = true
        guests.confirmationName = "Friend with a very long account name that must remain readable"
        var menu = createTemporaryObject(menuFactory, testCase)
        tryCompare(menu, "visible", true)
        verify(menu.height <= 240)
        var close = findChild(menu, "streamGuestsClose")
        var confirm = findChild(menu, "streamGuestConfirm")
        verify(close.height >= 48 && confirm.height >= 48)
        verify(close.mapToItem(menu.contentItem, 0, 0).y >= 0)
        verify(confirm.mapToItem(menu.contentItem, 0, confirm.height).y <= menu.height)
        mouseClick(confirm)
        compare(guests.confirmed, 1)
    }

    function test_owner_only_and_no_window_after_alt_tab_or_session_end() {
        var menu = createTemporaryObject(menuFactory, testCase)
        tryCompare(menu, "visible", true)
        guests.ownerSession = false
        tryCompare(menu, "visible", false)
        guests.ownerSession = true
        tryCompare(menu, "visible", true)
        stream.streamMenuVisible = false
        tryCompare(menu, "visible", false)
        stream.streamMenuBounds = Qt.rect(0, 0, 0, 0)
        stream.streamMenuVisible = true
        compare(menu.visible, false)
    }
}
