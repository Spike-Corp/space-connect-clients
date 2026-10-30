#pragma once

#include <atomic>
#include <memory>

using ConnectionCancellation = std::shared_ptr<std::atomic_bool>;

inline bool connectionCancelled(const ConnectionCancellation& cancellation)
{
    return cancellation && cancellation->load();
}
