#include "inputtimer.h"
#include <atomic>
#include <cstdint>

void InputTimer::start(Uint32 delay)
{
    cancel();
    static std::atomic<Uint32> nextToken{0};
    do { m_Token = ++nextToken; } while (!m_Token);
    m_Id = SDL_AddTimer(delay, [](Uint32, void* token) -> Uint32 {
        SDL_Event event{};
        event.type = SDL_USEREVENT;
        event.user.code = EventCode;
        event.user.windowID = static_cast<Uint32>(reinterpret_cast<uintptr_t>(token));
        if (SDL_PushEvent(&event) < 0)
            SDL_LogError(SDL_LOG_CATEGORY_APPLICATION, "Could not queue touch timer: %s", SDL_GetError());
        return 0;
    }, reinterpret_cast<void*>(static_cast<uintptr_t>(m_Token)));
    if (!m_Id) {
        m_Token = 0;
        SDL_LogError(SDL_LOG_CATEGORY_APPLICATION, "Could not schedule touch timer: %s", SDL_GetError());
    }
}

void InputTimer::cancel()
{
    m_Token = 0;
    if (m_Id) SDL_RemoveTimer(m_Id);
    m_Id = 0;
}

bool InputTimer::accept(const SDL_Event& event)
{
    if (!m_Token || event.type != SDL_USEREVENT || event.user.code != EventCode || event.user.windowID != m_Token)
        return false;
    m_Id = 0;
    m_Token = 0;
    return true;
}
