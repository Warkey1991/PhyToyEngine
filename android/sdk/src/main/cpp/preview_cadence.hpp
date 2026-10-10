#pragma once

#include <chrono>
#include <cstdint>

namespace phytoy::android_detail {

// Used under the session queue mutex. Cadence controls when the worker starts;
// camera callbacks keep replacing its one pending frame until that deadline.
class PreviewCadence {
public:
    using Clock = std::chrono::steady_clock;

    void reset() noexcept { started_ = false; }
    void begin(Clock::time_point now) noexcept { last_start_ = now; started_ = true; }
    Clock::time_point deadline(Clock::time_point now, uint32_t fps) const noexcept {
        if (!started_ || fps == 0U) return now;
        const auto due = last_start_ + std::chrono::nanoseconds(1'000'000'000ULL / fps);
        // GPU overruns do not accumulate a backlog of old cadence slots.
        return due > now ? due : now;
    }

private:
    Clock::time_point last_start_{};
    bool started_{};
};

}  // namespace phytoy::android_detail
