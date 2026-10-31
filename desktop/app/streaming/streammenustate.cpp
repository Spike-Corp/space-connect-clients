#include "streammenustate.h"
#include <cstdint>

Uint32 StreamMenuState::fullscreenFlags(Uint32 requested, bool controlsAvailable)
{
    return controlsAvailable && requested == SDL_WINDOW_FULLSCREEN
            ? Uint32(SDL_WINDOW_FULLSCREEN_DESKTOP) : requested;
}

SDL_Event StreamMenuState::event(Uint32 sessionId, Command command)
{
    SDL_Event result{};
    result.type = SDL_USEREVENT;
    result.user.code = EventCode;
    result.user.windowID = sessionId;
    result.user.data1 = reinterpret_cast<void*>(static_cast<uintptr_t>(command));
    return result;
}

bool StreamMenuState::command(const SDL_Event& event, Uint32 sessionId, Command& command)
{
    if (event.type != SDL_USEREVENT || event.user.code != EventCode || event.user.windowID != sessionId)
        return false;
    const auto value = reinterpret_cast<uintptr_t>(event.user.data1);
    if (value > MenuUnfocused) return false;
    command = static_cast<Command>(value);
    return true;
}

QRect StreamMenuState::logicalBounds(const QRect& stream, const QRect& display, const QRect& logicalDisplay)
{
    if (stream.isEmpty() || display.isEmpty() || logicalDisplay.isEmpty()) return {};
    const qreal sx = qreal(logicalDisplay.width()) / display.width();
    const qreal sy = qreal(logicalDisplay.height()) / display.height();
    return QRect(logicalDisplay.x() + qRound((stream.x() - display.x()) * sx),
                 logicalDisplay.y() + qRound((stream.y() - display.y()) * sy),
                 qRound(stream.width() * sx), qRound(stream.height() * sy)).intersected(logicalDisplay);
}

void StreamMenuState::open(bool captured, Uint32 pressedButtons)
{
    if (m_Open) return;
    m_Open = true;
    m_WasCaptured = captured;
    m_RestorePending = m_ClosingClick = false;
    m_Buttons = pressedButtons;
    m_Keys.unite(m_HeldKeys);
    m_Fingers.unite(m_HeldFingers);
}

void StreamMenuState::close(bool restoreCapture)
{
    if (!m_Open) return;
    m_Open = false;
    m_RestorePending = restoreCapture && m_WasCaptured;
}

void StreamMenuState::focusLeftApplication()
{
    m_ClosingClick = m_Open || m_Buttons != 0 || !m_Fingers.isEmpty();
    m_Open = m_RestorePending = false;
    m_Buttons = 0;
    m_Fingers.clear();
    m_HeldFingers.clear();
    m_HeldKeys.clear();
}

bool StreamMenuState::consume(const SDL_Event& event)
{
    switch (event.type) {
    case SDL_MOUSEBUTTONDOWN:
    case SDL_MOUSEBUTTONUP: {
        const Uint32 mask = event.button.button > 0 && event.button.button <= 32 ? SDL_BUTTON(event.button.button) : 0;
        if (event.type == SDL_MOUSEBUTTONDOWN && (m_Open || m_ClosingClick || m_Buttons != 0)) {
            m_Buttons |= mask;
            m_ClosingClick = false;
            close(true);
            return true;
        }
        if (event.type == SDL_MOUSEBUTTONUP && ((m_Buttons & mask) || m_ClosingClick)) {
            m_Buttons &= ~mask;
            m_ClosingClick = false;
            return true;
        }
        return m_Open;
    }
    case SDL_MOUSEMOTION:
    case SDL_MOUSEWHEEL:
        return m_Open || m_Buttons != 0;
    case SDL_KEYDOWN:
    case SDL_KEYUP: {
        const auto key = event.key.keysym.scancode;
        if (event.type == SDL_KEYDOWN) m_HeldKeys.insert(key);
        else m_HeldKeys.remove(key);
        if (!m_Open && !m_Keys.contains(key)) return false;
        if (event.type == SDL_KEYDOWN) {
            m_Keys.insert(key);
            if (event.key.keysym.sym == SDLK_ESCAPE ||
                    ((event.key.keysym.sym == SDLK_o || key == SDL_SCANCODE_O) &&
                     (event.key.keysym.mod & KMOD_CTRL) && (event.key.keysym.mod & KMOD_ALT) &&
                     (event.key.keysym.mod & KMOD_SHIFT))) close(true);
        }
        else {
            m_Keys.remove(key);
        }
        return true;
    }
    case SDL_FINGERDOWN:
    case SDL_FINGERMOTION:
    case SDL_FINGERUP: {
        const auto finger = qMakePair(event.tfinger.touchId, event.tfinger.fingerId);
        if (event.type == SDL_FINGERDOWN) m_HeldFingers.insert(finger);
        else if (event.type == SDL_FINGERUP) m_HeldFingers.remove(finger);
        if (event.type == SDL_FINGERDOWN && (m_Open || m_ClosingClick || !m_Fingers.isEmpty())) {
            m_Fingers.insert(finger);
            m_ClosingClick = false;
            close(true);
            return true;
        }
        if (event.type == SDL_FINGERUP && (m_Fingers.remove(finger) || m_ClosingClick)) {
            m_ClosingClick = false;
            return true;
        }
        return m_Open || m_Fingers.contains(finger);
    }
    default:
        return false;
    }
}

bool StreamMenuState::takeCaptureRestore(bool streamFocused)
{
    if (m_Open || !m_RestorePending || !streamFocused || m_Buttons != 0 || !m_Fingers.isEmpty()) return false;
    m_RestorePending = false;
    return true;
}
