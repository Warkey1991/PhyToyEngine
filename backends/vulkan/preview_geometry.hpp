#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <span>

namespace phytoy::vulkan_detail {

// Called only after the regular optics/ISP parameter validation. Match the
// shader's radius rounding exactly, including zero and half-step boundaries.
inline uint32_t preview_optics_radius(uint32_t kernel_width, uint32_t kernel_height) {
    return std::max(kernel_width / 2U, kernel_height / 2U);
}

inline uint32_t preview_filter_radius(float sigma) {
    return sigma <= 0.0F ? 0U : std::min(
        static_cast<uint32_t>(std::floor(4.0F * sigma + 0.5F)), 4U);
}

inline uint32_t preview_isp_halo(float denoise_sigma, float sharpen_amount, float sharpen_radius) {
    return preview_filter_radius(denoise_sigma) +
        (sharpen_amount != 0.0F ? preview_filter_radius(sharpen_radius) : 0U);
}

// IDs match the separate preview shaders. Parameters have already passed the
// regular optics/ISP validation; the cached key contains every fixed branch.
inline std::array<int32_t, 6> preview_optics_specialization(std::span<const float> parameters) {
    const auto dimension = [&parameters](size_t index) {
        return static_cast<int32_t>(parameters[index] + 0.5F);
    };
    const int32_t width = dimension(16U), height = dimension(17U);
    return {std::max(width / 2, height / 2), dimension(15U), width, height,
            dimension(18U), dimension(19U)};
}

inline std::array<int32_t, 7> preview_isp_specialization(std::span<const float> parameters) {
    const int32_t tones = static_cast<int32_t>(parameters[17U] + 0.5F);
    const size_t offset = 18U + static_cast<size_t>(tones) * 2U;
    const auto denoise = static_cast<int32_t>(preview_filter_radius(parameters[offset]));
    const bool sharpen = parameters[offset + 1U] != 0.0F;
    const auto lowpass = sharpen
        ? static_cast<int32_t>(preview_filter_radius(parameters[offset + 2U])) : 0;
    return {denoise + lowpass, static_cast<int32_t>(parameters[4U] + 0.5F), tones,
            denoise, lowpass, sharpen ? 1 : 0, parameters[offset + 3U] > 0.5F ? 1 : 0};
}

}  // namespace phytoy::vulkan_detail
