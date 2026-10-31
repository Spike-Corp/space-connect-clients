#include <QtTest>
#include "../streaming/streammenustate.h"

class StreamMenuTest : public QObject
{
    Q_OBJECT
    static SDL_Event button(Uint32 type, Uint8 button = SDL_BUTTON_LEFT) {
        SDL_Event event{};
        event.type = type;
        event.button.button = button;
        return event;
    }
    static SDL_Event key(Uint32 type, SDL_Scancode key) {
        SDL_Event event{};
        event.type = type;
        event.key.keysym.scancode = key;
        event.key.keysym.sym = key == SDL_SCANCODE_ESCAPE ? SDLK_ESCAPE : SDLK_RETURN;
        return event;
    }

private slots:
    void guestWindowNeverRequiresSwitchingOutOfExclusiveFullscreen() {
        QCOMPARE(StreamMenuState::fullscreenFlags(SDL_WINDOW_FULLSCREEN, true), Uint32(SDL_WINDOW_FULLSCREEN_DESKTOP));
        QCOMPARE(StreamMenuState::fullscreenFlags(SDL_WINDOW_FULLSCREEN_DESKTOP, true), Uint32(SDL_WINDOW_FULLSCREEN_DESKTOP));
        QCOMPARE(StreamMenuState::fullscreenFlags(0, true), Uint32(0));
        QCOMPARE(StreamMenuState::fullscreenFlags(SDL_WINDOW_FULLSCREEN, false), Uint32(SDL_WINDOW_FULLSCREEN));
    }

    void externalClickClosesWithoutForwardingEitherHalfOrRecapturingEarly() {
        StreamMenuState menu;
        menu.open(true);
        QVERIFY(menu.isOpen());
        QVERIFY(menu.consume(button(SDL_MOUSEBUTTONDOWN)));
        QVERIFY(!menu.isOpen());
        QVERIFY(!menu.takeCaptureRestore(true));
        QVERIFY(menu.consume(button(SDL_MOUSEBUTTONUP)));
        QVERIFY(menu.takeCaptureRestore(true));
        QVERIFY(!menu.takeCaptureRestore(true));
        QVERIFY(!menu.consume(button(SDL_MOUSEBUTTONDOWN)));
    }

    void freeCursorStaysFreeAfterClosing() {
        StreamMenuState menu;
        menu.open(false);
        menu.close(true);
        QVERIFY(!menu.takeCaptureRestore(true));
        menu.open(false);
        QVERIFY(menu.consume(button(SDL_MOUSEBUTTONDOWN)));
        QVERIFY(menu.consume(button(SDL_MOUSEBUTTONUP)));
        QVERIFY(!menu.takeCaptureRestore(true));
    }

    void escapeAndOtherMenuKeyReleasesDoNotReachTheHost() {
        StreamMenuState menu;
        menu.open(true);
        QVERIFY(menu.consume(key(SDL_KEYDOWN, SDL_SCANCODE_RETURN)));
        QVERIFY(menu.consume(key(SDL_KEYDOWN, SDL_SCANCODE_ESCAPE)));
        QVERIFY(!menu.isOpen());
        QVERIFY(menu.consume(key(SDL_KEYUP, SDL_SCANCODE_ESCAPE)));
        QVERIFY(menu.consume(key(SDL_KEYUP, SDL_SCANCODE_RETURN)));
        QVERIFY(!menu.consume(key(SDL_KEYDOWN, SDL_SCANCODE_RETURN)));
    }

    void altTabPreventsCaptureAndConsumesAPendingClosingClick() {
        StreamMenuState menu;
        menu.open(true);
        menu.focusLeftApplication();
        QVERIFY(!menu.isOpen());
        QVERIFY(!menu.takeCaptureRestore(true));
        QVERIFY(menu.consume(button(SDL_MOUSEBUTTONDOWN)));
        QVERIFY(menu.consume(button(SDL_MOUSEBUTTONUP)));
        QVERIFY(!menu.takeCaptureRestore(true));
        QVERIFY(!menu.consume(button(SDL_MOUSEBUTTONDOWN)));
    }

    void losingFocusDuringClosingGestureDoesNotStealItBack() {
        StreamMenuState menu;
        menu.open(true);
        QVERIFY(menu.consume(button(SDL_MOUSEBUTTONDOWN)));
        menu.focusLeftApplication();
        QVERIFY(menu.consume(button(SDL_MOUSEBUTTONUP)));
        QVERIFY(!menu.takeCaptureRestore(false));
        QVERIFY(!menu.takeCaptureRestore(true));
    }

    void heldButtonsDelayRestoringCaptureUntilAllAreReleased() {
        StreamMenuState menu;
        menu.open(true, SDL_BUTTON_LMASK | SDL_BUTTON_RMASK);
        menu.close(true);
        QVERIFY(!menu.takeCaptureRestore(true));
        QVERIFY(menu.consume(button(SDL_MOUSEBUTTONUP)));
        QVERIFY(!menu.takeCaptureRestore(true));
        QVERIFY(menu.consume(button(SDL_MOUSEBUTTONUP, SDL_BUTTON_RIGHT)));
        QVERIFY(!menu.takeCaptureRestore(false));
        QVERIFY(menu.takeCaptureRestore(true));
    }

    void outsideTouchIsConsumedUntilRelease() {
        StreamMenuState menu;
        menu.open(true);
        SDL_Event touch{};
        touch.type = SDL_FINGERDOWN;
        touch.tfinger.touchId = 4;
        touch.tfinger.fingerId = 12;
        QVERIFY(menu.consume(touch));
        QVERIFY(!menu.isOpen());
        QVERIFY(!menu.takeCaptureRestore(true));
        touch.type = SDL_FINGERMOTION;
        QVERIFY(menu.consume(touch));
        touch.type = SDL_FINGERUP;
        QVERIFY(menu.consume(touch));
        QVERIFY(menu.takeCaptureRestore(true));
    }

    void aTouchHeldBeforeOpeningCannotLeakOrRestoreCaptureAfterClosing() {
        StreamMenuState menu;
        SDL_Event touch{};
        touch.type = SDL_FINGERDOWN;
        touch.tfinger.touchId = 4;
        touch.tfinger.fingerId = 12;
        QVERIFY(!menu.consume(touch));
        menu.open(true);
        menu.close(true);
        QVERIFY(!menu.takeCaptureRestore(true));
        touch.type = SDL_FINGERMOTION;
        QVERIFY(menu.consume(touch));
        touch.type = SDL_FINGERUP;
        QVERIFY(menu.consume(touch));
        QVERIFY(menu.takeCaptureRestore(true));
    }

    void releasesOfKeysHeldBeforeOpeningStayLocal() {
        StreamMenuState menu;
        QVERIFY(!menu.consume(key(SDL_KEYDOWN, SDL_SCANCODE_RETURN)));
        menu.open(true);
        menu.close(true);
        QVERIFY(menu.consume(key(SDL_KEYUP, SDL_SCANCODE_RETURN)));
        QVERIFY(!menu.consume(key(SDL_KEYDOWN, SDL_SCANCODE_RETURN)));
    }

    void eventsFromAnOldSessionCannotOperateTheNewMenu() {
        auto event = StreamMenuState::event(41, StreamMenuState::Toggle);
        StreamMenuState::Command action = StreamMenuState::CloseExternal;
        QVERIFY(!StreamMenuState::command(event, 42, action));
        QVERIFY(StreamMenuState::command(event, 41, action));
        QCOMPARE(action, StreamMenuState::Toggle);
        event.user.code = 0;
        QVERIFY(!StreamMenuState::command(event, 41, action));
    }

    void placementHandlesScaledAndNegativeOriginDisplays_data() {
        QTest::addColumn<QRect>("stream");
        QTest::addColumn<QRect>("native");
        QTest::addColumn<QRect>("logical");
        QTest::addColumn<QRect>("expected");
        QTest::newRow("primary-150-percent") << QRect(150, 90, 1500, 900) << QRect(0, 0, 2880, 1620)
            << QRect(0, 0, 1920, 1080) << QRect(100, 60, 1000, 600);
        QTest::newRow("second-monitor-200-percent") << QRect(2240, 180, 1920, 1080) << QRect(1920, 0, 3840, 2160)
            << QRect(1920, 0, 1920, 1080) << QRect(2080, 90, 960, 540);
        QTest::newRow("left-monitor") << QRect(-2400, 180, 1280, 720) << QRect(-2560, 0, 2560, 1440)
            << QRect(-2048, 0, 2048, 1152) << QRect(-1920, 144, 1024, 576);
    }

    void placementHandlesScaledAndNegativeOriginDisplays() {
        QFETCH(QRect, stream);
        QFETCH(QRect, native);
        QFETCH(QRect, logical);
        QFETCH(QRect, expected);
        QCOMPARE(StreamMenuState::logicalBounds(stream, native, logical), expected);
    }
};

QTEST_APPLESS_MAIN(StreamMenuTest)
#include "stream_menu_test.moc"
