#pragma once

#include "image.hpp"
#include "phytoy/phytoy.h"
#include "profile.hpp"

#include <cstdint>
#include <memory>

namespace phytoy {

struct VulkanRuntimeStats {
    uint64_t queue_submissions{};
    uint64_t resource_allocations{};
    uint64_t allocated_bytes{};
    uint64_t ahardware_buffer_imports{};
    uint64_t presented_frames{};
    uint64_t swapchain_recreates{};
    uint32_t output_width{};
    uint32_t output_height{};
};

class VulkanBackend {
public:
    VulkanBackend();
    ~VulkanBackend();
    VulkanBackend(const VulkanBackend&) = delete;
    VulkanBackend& operator=(const VulkanBackend&) = delete;

    [[nodiscard]] bool available() const noexcept;
    [[nodiscard]] bool ahardware_buffer_input_available() const noexcept;
    [[nodiscard]] VulkanRuntimeStats stats() const noexcept;
    void set_output_window(::ANativeWindow* window, uint32_t rotation_degrees);
    void forget_ahardware_buffer(::AHardwareBuffer* buffer) noexcept;
    void render(
        const pte_frame_f32_t& frame,
        const HostProfile& host,
        const ToyProfile& toy,
        uint64_t seed,
        pte_stage_callback_f32 stage_callback,
        void* stage_user_data,
        pte_output_f32_t& output);
    void render_ahardware_buffer(
        ::AHardwareBuffer* buffer,
        uint32_t width,
        uint32_t height,
        int acquire_fence_fd,
        const HostProfile& host,
        const ToyProfile& toy,
        uint64_t seed,
        pte_stage_callback_f32 stage_callback,
        void* stage_user_data,
        pte_output_f32_t* output);

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

bool vulkan_backend_available() noexcept;

}  // namespace phytoy
