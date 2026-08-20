#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <stdexcept>
#include <vector>

namespace phytoy {

struct Image {
    uint32_t width{};
    uint32_t height{};
    uint32_t channels{};
    std::vector<float> pixels;

    Image() = default;
    Image(uint32_t w, uint32_t h, uint32_t c)
        : width(w), height(h), channels(c),
          pixels(static_cast<size_t>(w) * h * c, 0.0F) {}

    [[nodiscard]] float& at(uint32_t x, uint32_t y, uint32_t c = 0) {
        return pixels[(static_cast<size_t>(y) * width + x) * channels + c];
    }
    [[nodiscard]] float at(uint32_t x, uint32_t y, uint32_t c = 0) const {
        return pixels[(static_cast<size_t>(y) * width + x) * channels + c];
    }
};

inline float clamp01(float value) {
    return std::clamp(value, 0.0F, 1.0F);
}

}  // namespace phytoy

