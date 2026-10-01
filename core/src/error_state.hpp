#pragma once

namespace phytoy::detail {

// Error storage is an allocation boundary too. Kept generic so a rejecting
// allocator can exercise the fallback without inducing process-wide OOM.
template <typename String>
void store_error(String& target, const char*& fallback, const char* message) noexcept {
    try {
        target.assign(message);
        fallback = nullptr;
    } catch (...) {
        target.clear();
        // Static storage: never retain exception.what() beyond its catch.
        fallback = "unable to allocate error message";
    }
}

}  // namespace phytoy::detail
