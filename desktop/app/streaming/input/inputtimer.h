#pragma once

#include <SDL.h>

class InputTimer
{
public:
    static constexpr int EventCode = 106;
    InputTimer() = default;
    ~InputTimer() { cancel(); }
    InputTimer(const InputTimer&) = delete;
    InputTimer& operator=(const InputTimer&) = delete;
    void start(Uint32 delay);
    void cancel();
    bool accept(const SDL_Event& event);

private:
    SDL_TimerID m_Id = 0;
    Uint32 m_Token = 0;
};
