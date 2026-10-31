#pragma once

#include <QRect>
#include <QSet>
#include <SDL.h>

class StreamMenuState
{
public:
    enum Command { Toggle, CloseAndReturn, CloseExternal, MenuFocused, MenuUnfocused };
    static constexpr int EventCode = 105;
    static SDL_Event event(Uint32 sessionId, Command command);
    static bool command(const SDL_Event& event, Uint32 sessionId, Command& command);
    static QRect logicalBounds(const QRect& stream, const QRect& display, const QRect& logicalDisplay);
    static Uint32 fullscreenFlags(Uint32 requested, bool controlsAvailable);

    bool isOpen() const { return m_Open; }
    void open(bool captured, Uint32 pressedButtons = 0);
    void close(bool restoreCapture);
    void focusLeftApplication();
    bool consume(const SDL_Event& event);
    bool takeCaptureRestore(bool streamFocused);

private:
    bool m_Open = false, m_WasCaptured = false, m_RestorePending = false, m_ClosingClick = false;
    Uint32 m_Buttons = 0;
    QSet<SDL_Scancode> m_Keys, m_HeldKeys;
    QSet<QPair<SDL_TouchID, SDL_FingerID>> m_Fingers, m_HeldFingers;
};
