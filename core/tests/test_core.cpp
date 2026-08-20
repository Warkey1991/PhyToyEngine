#include "phytoy/phytoy.h"

#include <algorithm>
#include <array>
#include <cassert>
#include <cmath>
#include <cstdint>
#include <filesystem>
#include <iostream>
#include <string>
#include <vector>

namespace {

std::filesystem::path profile(const char* name) {
    return std::filesystem::path(PHYTOY_TEST_PROFILE_DIR) / name;
}

struct Engine {
    pte_engine_t* value{};
    Engine(const std::filesystem::path& host, const std::filesystem::path& toy) {
        const auto status = pte_engine_create(host.c_str(), toy.c_str(), &value);
        if (status != PTE_STATUS_OK) {
            std::cerr << "create failed: " << pte_last_error() << '\n';
        }
        assert(status == PTE_STATUS_OK);
        assert(value != nullptr);
    }
    ~Engine() { pte_engine_destroy(value); }
    Engine(const Engine&) = delete;
    Engine& operator=(const Engine&) = delete;
};

struct StageCapture {
    std::array<uint32_t, 6> calls{};
    std::array<uint32_t, 6> channels{};
};

void capture_stage(void* user, pte_stage_t stage, const float*, uint32_t, uint32_t, uint32_t channels) {
    auto& capture = *static_cast<StageCapture*>(user);
    ++capture.calls[static_cast<size_t>(stage)];
    capture.channels[static_cast<size_t>(stage)] = channels;
}

std::vector<float> render(
    Engine& engine,
    const pte_frame_f32_t& frame,
    uint64_t seed,
    StageCapture* capture = nullptr) {
    std::vector<float> output(static_cast<size_t>(frame.width) * frame.height * 3U);
    pte_output_f32_t destination{output.data(), output.size(), frame.width * 3U};
    pte_render_options_t options{};
    options.abi_version = PTE_ABI_VERSION;
    options.seed = seed;
    options.stage_callback = capture == nullptr ? nullptr : capture_stage;
    options.stage_user_data = capture;
    const auto status = pte_engine_render(engine.value, &frame, &options, &destination);
    if (status != PTE_STATUS_OK) {
        std::cerr << "render failed: " << pte_engine_last_error(engine.value) << '\n';
    }
    assert(status == PTE_STATUS_OK);
    return output;
}

void test_srgb_determinism_and_stages() {
    Engine engine(profile("host_generic_srgb.ptp"), profile("toy_fixed_focus_alpha.ptp"));
    constexpr uint32_t width = 20U;
    constexpr uint32_t height = 14U;
    std::vector<float> input(static_cast<size_t>(width) * height * 3U);
    for (uint32_t y = 0; y < height; ++y) {
        for (uint32_t x = 0; x < width; ++x) {
            input[(static_cast<size_t>(y) * width + x) * 3U] = static_cast<float>(x) / static_cast<float>(width - 1U);
            input[(static_cast<size_t>(y) * width + x) * 3U + 1U] = static_cast<float>(y) / static_cast<float>(height - 1U);
            input[(static_cast<size_t>(y) * width + x) * 3U + 2U] = 0.35F;
        }
    }
    pte_frame_f32_t frame{};
    frame.abi_version = PTE_ABI_VERSION;
    frame.format = PTE_PIXEL_FORMAT_SRGB_F32;
    frame.width = width;
    frame.height = height;
    frame.plane[0] = {input.data(), width * 3U};

    StageCapture capture;
    const auto first = render(engine, frame, 991U, &capture);
    const auto second = render(engine, frame, 991U);
    const auto third = render(engine, frame, 992U);
    assert(first == second);
    assert(first != third);
    for (size_t stage = 1; stage <= 5; ++stage) assert(capture.calls[stage] == 1U);
    assert(capture.channels[PTE_STAGE_TARGET_SENSOR_DN] == 1U);
    assert(capture.channels[PTE_STAGE_OUTPUT_SRGB] == 3U);
    assert(std::all_of(first.begin(), first.end(), [](float value) {
        return std::isfinite(value) && value >= 0.0F && value <= 1.0F;
    }));
}

void test_yuv_and_raw_execute() {
    constexpr uint32_t width = 12U;
    constexpr uint32_t height = 10U;
    const auto toy = profile("toy_fixed_focus_alpha.ptp");
    {
        Engine engine(profile("host_generic_yuv420.ptp"), toy);
        std::vector<float> y(static_cast<size_t>(width) * height, 0.45F);
        std::vector<float> uv(static_cast<size_t>((width + 1U) / 2U) * ((height + 1U) / 2U) * 2U, 0.5F);
        pte_frame_f32_t frame{};
        frame.abi_version = PTE_ABI_VERSION;
        frame.format = PTE_PIXEL_FORMAT_YUV420_BT709_F32;
        frame.width = width;
        frame.height = height;
        frame.plane[0] = {y.data(), width};
        frame.plane[1] = {uv.data(), ((width + 1U) / 2U) * 2U};
        frame.yuv_full_range = 1U;
        const auto output = render(engine, frame, 1U);
        assert(output.size() == static_cast<size_t>(width) * height * 3U);
    }
    for (const char* raw_profile : {
             "host_reference_raw.ptp", "host_samsung_sm_s9210_camera0_raw.ptp"}) {
        Engine engine(profile(raw_profile), toy);
        std::vector<float> raw(static_cast<size_t>(width) * height);
        for (size_t i = 0; i < raw.size(); ++i) raw[i] = 300.0F + static_cast<float>(i % width) * 50.0F;
        pte_frame_f32_t frame{};
        frame.abi_version = PTE_ABI_VERSION;
        frame.format = PTE_PIXEL_FORMAT_RAW_BAYER_F32;
        frame.width = width;
        frame.height = height;
        frame.plane[0] = {raw.data(), width};
        const auto output = render(engine, frame, 2U);
        assert(output.size() == static_cast<size_t>(width) * height * 3U);
    }
}

void test_argument_validation() {
    Engine engine(profile("host_generic_srgb.ptp"), profile("toy_fixed_focus_alpha.ptp"));
    std::vector<float> input(4U * 4U * 3U, 0.5F);
    std::vector<float> output(2U, 0.0F);
    pte_frame_f32_t frame{};
    frame.abi_version = PTE_ABI_VERSION;
    frame.format = PTE_PIXEL_FORMAT_SRGB_F32;
    frame.width = 4U;
    frame.height = 4U;
    frame.plane[0] = {input.data(), 12U};
    pte_output_f32_t destination{output.data(), output.size(), 12U};
    pte_render_options_t options{PTE_ABI_VERSION, 1U, nullptr, nullptr};
    assert(pte_engine_render(engine.value, &frame, &options, &destination) == PTE_STATUS_BUFFER_TOO_SMALL);
    assert(pte_backend_is_available(PTE_BACKEND_CPU) == 1U);
    assert(pte_backend_is_available(PTE_BACKEND_AUTO) == 1U);
    assert(pte_engine_set_backend(engine.value, PTE_BACKEND_AUTO) == PTE_STATUS_OK);
#if !defined(__ANDROID__)
    assert(pte_backend_is_available(PTE_BACKEND_VULKAN) == 0U);
    assert(pte_engine_set_backend(engine.value, PTE_BACKEND_VULKAN) != PTE_STATUS_OK);
#endif
}

}  // namespace

int main() {
    std::cout << pte_version_string() << '\n';
    test_srgb_determinism_and_stages();
    test_yuv_and_raw_execute();
    test_argument_validation();
    std::cout << "all native core tests passed\n";
    return 0;
}
