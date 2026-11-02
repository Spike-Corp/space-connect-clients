#pragma once

// Space Connect: video statistics shared with the headless "connect --ipc" mode.
// Licensed under GPL-3.0 like the rest of the project.

#include <Limelight.h>

#include <atomic>
#include <cstdint>
#include <mutex>

namespace IpcStats {

struct Snapshot {
    double fps = 0;
    double decodeMs = 0;
    double renderMs = 0;
    double rttMs = 0;
    double lossPct = 0;
    const char* codec = "";
};

struct State {
    std::atomic<bool> enabled{false};
    std::atomic<uint64_t> bytes{0};
    std::mutex lock;
    Snapshot snapshot;
};

inline State& state()
{
    static State s;
    return s;
}

inline bool enabled() { return state().enabled.load(std::memory_order_relaxed); }
inline void setEnabled(bool on) { state().enabled.store(on); }
inline void addBytes(uint64_t n) { if (enabled()) state().bytes.fetch_add(n, std::memory_order_relaxed); }
inline uint64_t totalBytes() { return state().bytes.load(std::memory_order_relaxed); }

inline void publish(const Snapshot& snap)
{
    std::lock_guard<std::mutex> g(state().lock);
    state().snapshot = snap;
}

inline Snapshot current()
{
    std::lock_guard<std::mutex> g(state().lock);
    return state().snapshot;
}

inline const char* codecName(int videoFormat)
{
    if (videoFormat & VIDEO_FORMAT_MASK_AV1) return "av1";
    if (videoFormat & VIDEO_FORMAT_MASK_H265) return "hevc";
    if (videoFormat & VIDEO_FORMAT_MASK_H264) return "h264";
    return "unknown";
}

}
