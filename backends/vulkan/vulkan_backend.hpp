#pragma once

#include "image.hpp"
#include "phytoy/phytoy.h"
#include "profile.hpp"

#include <cstdint>
#include <memory>

namespace phytoy {

class VulkanBackend {
public:
    VulkanBackend();
    ~VulkanBackend();
    VulkanBackend(const VulkanBackend&) = delete;
    VulkanBackend& operator=(const VulkanBackend&) = delete;

    [[nodiscard]] bool available() const noexcept;
    Image render(
        const pte_frame_f32_t& frame,
        const HostProfile& host,
        const ToyProfile& toy,
        uint64_t seed,
        pte_stage_callback_f32 stage_callback,
        void* stage_user_data);

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

bool vulkan_backend_available() noexcept;

}  // namespace phytoy

