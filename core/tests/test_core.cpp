#include "phytoy/phytoy.h"
#include "../src/error_state.hpp"
#include "../../backends/vulkan/preview_geometry.hpp"
#include "../../android/sdk/src/main/cpp/preview_cadence.hpp"

#include <algorithm>
#include <array>
#include <cassert>

#ifdef NDEBUG
#error "Native regression tests require active assertions"
#endif
#include <cmath>
#include <cstdint>
#include <filesystem>
#include <iostream>
#include <limits>
#include <memory>
#include <new>
#include <stdexcept>
#include <string>
#include <thread>
#include <utility>
#include <vector>

namespace {

void test_preview_tile_geometry() {
    using namespace phytoy::vulkan_detail;
    // Radius boundaries must match the GLSL truncate=4 contract, including
    // disabled sharpening whose radius may exceed the active filter limit.
    for (const auto& [sigma, radius] : std::array{
             std::pair{0.0F, 0U}, std::pair{0.124F, 0U}, std::pair{0.125F, 1U},
             std::pair{0.374F, 1U}, std::pair{0.375F, 2U}, std::pair{0.624F, 2U},
             std::pair{0.625F, 3U}, std::pair{0.874F, 3U}, std::pair{0.875F, 4U},
             std::pair{1.0F, 4U},
         }) assert(preview_filter_radius(sigma) == radius);
    assert(preview_isp_halo(1.0F, 0.0F, 12.0F) == 4U);
    assert(preview_isp_halo(1.0F, -0.5F, 1.0F) == 8U);
    assert(preview_isp_halo(0.0F, 0.0F, 1.0F) == 0U);
    assert(preview_optics_radius(3U, 9U) == 4U);
    assert(preview_optics_radius(1U, 1U) == 0U);

    std::vector<float> optics(21U);
    optics[15] = 8.0F; optics[16] = 3.0F; optics[17] = 9.0F;
    optics[18] = 2.0F; optics[19] = 4.0F;
    assert((preview_optics_specialization(optics) == std::array<int32_t, 6>{4, 8, 3, 9, 2, 4}));
    std::vector<float> isp(28U);
    isp[4] = 3.0F; isp[17] = 3.0F; isp[24] = 0.375F;
    isp[25] = -0.5F; isp[26] = 0.875F; isp[27] = 1.0F;
    assert((preview_isp_specialization(isp) == std::array<int32_t, 7>{6, 3, 3, 2, 4, 1, 1}));
    isp[25] = 0.0F; isp[26] = 12.0F;
    assert((preview_isp_specialization(isp) == std::array<int32_t, 7>{2, 3, 3, 2, 0, 0, 1}));

    using Cadence = phytoy::android_detail::PreviewCadence;
    using namespace std::chrono_literals;
    Cadence cadence;
    const auto start = Cadence::Clock::time_point{};
    assert(cadence.deadline(start, 30U) == start);
    cadence.begin(start);
    // New arrivals replace pending input without postponing the existing slot.
    for (const auto arrival : {34ms, 67ms, 134ms, 199ms}) {
        assert(cadence.deadline(start + arrival, 5U) == start + 200ms);
    }
    // An overrun starts from now; it never replays a backlog of stale slots.
    assert(cadence.deadline(start + 473ms, 30U) == start + 473ms);
    cadence.begin(start + 473ms);
    assert(cadence.deadline(start + 480ms, 15U) == start + 473ms + 66'666'666ns);
    cadence.reset();
    assert(cadence.deadline(start + 480ms, 30U) == start + 480ms);
    assert(cadence.deadline(start + 480ms, 0U) == start + 480ms);
}

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
    Engine engine(profile("host_generic_srgb.ptp"), profile("toy_phytoy_digital_01_v1.ptp"));
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
    pte_runtime_stats_t stats{};
    stats.abi_version = PTE_ABI_VERSION;
    assert(pte_engine_get_runtime_stats(engine.value, &stats) == PTE_STATUS_OK);
    assert(stats.rendered_frames == 3U);
    assert(stats.cpu_frames == 3U);
    assert(stats.vulkan_frames == 0U);
    assert(stats.vulkan_queue_submissions == 0U);
    assert(stats.ahardware_buffer_imports == 0U);
}

void test_yuv_and_raw_execute() {
    constexpr uint32_t width = 12U;
    constexpr uint32_t height = 10U;
    const auto toy = profile("toy_phytoy_digital_01_v1.ptp");
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
    Engine engine(profile("host_generic_srgb.ptp"), profile("toy_phytoy_digital_01_v1.ptp"));
    pte_engine_profile_info_t info{};
    info.abi_version = PTE_ABI_VERSION;
    assert(pte_engine_get_profile_info(engine.value, &info) == PTE_STATUS_OK);
    assert(std::string(info.host_profile_id) == "phytoy.host.generic_srgb");
    assert(std::string(info.toy_profile_id) == "phytoy.toy.digital_01");
    assert(std::string(info.toy_profile_version) == "1.0.0");
    assert(std::string(info.toy_profile_type) == "designed");
    assert(std::string(info.toy_calibration_status) == "synthetic_locked");
    assert(std::string(info.toy_target_name) == "PhyToy Digital 01");
    assert(std::string(info.toy_dataset_id).empty());
    info.abi_version = PTE_ABI_VERSION + 1U;
    assert(pte_engine_get_profile_info(engine.value, &info) == PTE_STATUS_INVALID_ARGUMENT);
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
    assert(pte_engine_supports_ahardware_buffer_input(engine.value) == 0U);
    assert(pte_engine_forget_ahardware_buffer(engine.value, nullptr) == PTE_STATUS_OK);
    assert(pte_engine_process_ahardware_buffer(engine.value, nullptr, nullptr) ==
           PTE_STATUS_INVALID_ARGUMENT);
    assert(pte_backend_is_available(PTE_BACKEND_VULKAN) == 0U);
    assert(pte_engine_set_backend(engine.value, PTE_BACKEND_VULKAN) != PTE_STATUS_OK);
#endif
}

void test_dimensions_and_padded_stride_validation() {
    Engine engine(profile("host_generic_srgb.ptp"), profile("toy_fixed_focus_conformance.ptp"));
    std::vector<float> input(4U * 4U * 3U, 0.35F);
    std::vector<float> output(input.size(), -7.0F);
    pte_frame_f32_t frame{};
    frame.abi_version = PTE_ABI_VERSION;
    frame.format = PTE_PIXEL_FORMAT_SRGB_F32;
    frame.width = 4U;
    frame.height = 4U;
    frame.plane[0] = {input.data(), 12U};
    pte_output_f32_t destination{output.data(), output.size(), 12U};
    pte_render_options_t options{PTE_ABI_VERSION, 1234U, nullptr, nullptr};
    for (const auto& [width, height] : std::array{
             std::pair{0U, 4U}, std::pair{4U, 0U},
             std::pair{8193U, 1U}, std::pair{1U, 8193U},
             std::pair{8192U, 2049U}, std::pair{65536U, 65536U},
             std::pair{std::numeric_limits<uint32_t>::max(), 2U}}) {
        frame.width = width;
        frame.height = height;
        assert(pte_engine_render(engine.value, &frame, &options, &destination) == PTE_STATUS_INVALID_ARGUMENT);
        assert(std::string(pte_engine_last_error(engine.value)).find("dimension") != std::string::npos);
        assert(std::all_of(output.begin(), output.end(), [](float value) { return value == -7.0F; }));
    }
    // A frame exactly at the budget passes dimension validation but cannot read
    // the tiny test input, because output capacity is checked before rendering.
    frame.width = 8192U;
    frame.height = 2048U;
    frame.plane[0].row_stride_floats = frame.width * 3U;
    assert(pte_engine_render(engine.value, &frame, &options, &destination) == PTE_STATUS_INVALID_ARGUMENT);
    destination.row_stride_floats = 0U;
    assert(pte_engine_render(engine.value, &frame, &options, &destination) == PTE_STATUS_BUFFER_TOO_SMALL);
    frame.width = frame.height = 4U;
    frame.plane[0].row_stride_floats = std::numeric_limits<uint32_t>::max();
    assert(pte_engine_render(engine.value, &frame, &options, &destination) == PTE_STATUS_INVALID_ARGUMENT);
    assert(std::string(pte_engine_last_error(engine.value)).find("memory budget") != std::string::npos);
    frame.plane[0].row_stride_floats = 12U;
    destination.row_stride_floats = std::numeric_limits<uint32_t>::max();
    assert(pte_engine_render(engine.value, &frame, &options, &destination) == PTE_STATUS_INVALID_ARGUMENT);
    assert(std::string(pte_engine_last_error(engine.value)).find("memory budget") != std::string::npos);

    // AHardwareBuffer dimensions are checked even on hosts without Vulkan, so
    // invalid dimensions never reach buffer import or backend initialization.
    pte_ahardware_buffer_frame_t ahb{};
    ahb.abi_version = PTE_ABI_VERSION;
    ahb.buffer = reinterpret_cast<AHardwareBuffer*>(static_cast<uintptr_t>(1U));
    ahb.width = std::numeric_limits<uint32_t>::max();
    ahb.height = 4U;
    ahb.acquire_fence_fd = -1;
    assert(pte_engine_process_ahardware_buffer(engine.value, &ahb, &options) == PTE_STATUS_INVALID_ARGUMENT);
    assert(pte_engine_render_ahardware_buffer(engine.value, &ahb, &options, &destination) == PTE_STATUS_INVALID_ARGUMENT);

    pte_runtime_stats_t stats{};
    stats.abi_version = PTE_ABI_VERSION;
    assert(pte_engine_get_runtime_stats(engine.value, &stats) == PTE_STATUS_OK);
    assert(stats.rendered_frames == 0U);
}

void test_padded_last_row_capacity_and_guards() {
    Engine engine(profile("host_generic_srgb.ptp"), profile("toy_fixed_focus_conformance.ptp"));
    constexpr uint32_t width = 5U;
    constexpr uint32_t height = 3U;
    constexpr uint32_t input_stride = 19U;
    constexpr uint32_t output_stride = 22U;
    constexpr size_t output_span = (height - 1U) * output_stride + width * 3U;
    std::vector<float> padded_input((height - 1U) * input_stride + width * 3U, -77.0F);
    std::vector<float> packed_input(width * height * 3U);
    for (uint32_t y = 0; y < height; ++y) {
        for (uint32_t x = 0; x < width * 3U; ++x) {
            const float value = 0.15F + static_cast<float>((x + y * 3U) % 9U) * 0.055F;
            padded_input[y * input_stride + x] = value;
            packed_input[y * width * 3U + x] = value;
        }
    }
    pte_frame_f32_t frame{};
    frame.abi_version = PTE_ABI_VERSION;
    frame.format = PTE_PIXEL_FORMAT_SRGB_F32;
    frame.width = width;
    frame.height = height;
    frame.plane[0] = {packed_input.data(), width * 3U};
    const auto expected = render(engine, frame, 1234U);
    frame.plane[0] = {padded_input.data(), input_stride};
    std::vector<float> guarded_output(output_span + 2U, -77.0F);
    pte_output_f32_t destination{guarded_output.data() + 1U, output_span - 1U, output_stride};
    pte_render_options_t options{PTE_ABI_VERSION, 1234U, nullptr, nullptr};
    assert(pte_engine_render(engine.value, &frame, &options, &destination) == PTE_STATUS_BUFFER_TOO_SMALL);
    assert(std::all_of(guarded_output.begin(), guarded_output.end(), [](float value) { return value == -77.0F; }));
    destination.capacity_floats = output_span;
    assert(pte_engine_render(engine.value, &frame, &options, &destination) == PTE_STATUS_OK);
    assert(guarded_output.front() == -77.0F && guarded_output.back() == -77.0F);
    for (uint32_t y = 0; y < height; ++y) {
        for (uint32_t x = 0; x < width * 3U; ++x) {
            assert(guarded_output[1U + y * output_stride + x] == expected[y * width * 3U + x]);
        }
        if (y + 1U < height) {
            for (uint32_t x = width * 3U; x < output_stride; ++x) {
                assert(guarded_output[1U + y * output_stride + x] == -77.0F);
            }
        }
    }
}

void test_yuv_and_raw_stride_budget_validation() {
    for (const auto& [host, format] : std::array{
             std::pair{"host_generic_yuv420.ptp", PTE_PIXEL_FORMAT_YUV420_BT709_F32},
             std::pair{"host_reference_raw.ptp", PTE_PIXEL_FORMAT_RAW_BAYER_F32}}) {
        Engine engine(profile(host), profile("toy_fixed_focus_conformance.ptp"));
        std::vector<float> input(16U, 0.4F);
        std::vector<float> uv(8U, 0.5F);
        std::vector<float> output(48U, -77.0F);
        pte_frame_f32_t frame{};
        frame.abi_version = PTE_ABI_VERSION;
        frame.format = format;
        frame.width = frame.height = 4U;
        frame.plane[0] = {input.data(), std::numeric_limits<uint32_t>::max()};
        frame.plane[1] = {uv.data(), 4U};
        pte_output_f32_t destination{output.data(), output.size(), 0U};
        assert(pte_engine_render(engine.value, &frame, nullptr, &destination) == PTE_STATUS_INVALID_ARGUMENT);
        if (format == PTE_PIXEL_FORMAT_YUV420_BT709_F32) {
            frame.plane[0].row_stride_floats = 4U;
            frame.plane[1].row_stride_floats = std::numeric_limits<uint32_t>::max();
            assert(pte_engine_render(engine.value, &frame, nullptr, &destination) == PTE_STATUS_INVALID_ARGUMENT);
        }
        assert(std::all_of(output.begin(), output.end(), [](float value) { return value == -77.0F; }));
    }
}

void test_error_snapshot_survives_a_render_on_another_thread() {
    Engine engine(profile("host_generic_srgb.ptp"), profile("toy_fixed_focus_conformance.ptp"));
    std::vector<float> input(4U * 4U * 3U, 0.3F);
    std::vector<float> output(input.size());
    pte_frame_f32_t frame{};
    frame.abi_version = PTE_ABI_VERSION;
    frame.format = PTE_PIXEL_FORMAT_SRGB_F32;
    frame.width = frame.height = 4U;
    frame.plane[0] = {input.data(), 12U};
    pte_output_f32_t destination{output.data(), 1U, 12U};
    assert(pte_engine_render(engine.value, &frame, nullptr, &destination) == PTE_STATUS_BUFFER_TOO_SMALL);
    const char* snapshot = pte_engine_last_error(engine.value);
    const std::string expected(snapshot);
    assert(expected.find("capacity") != std::string::npos);
    std::thread worker([&] {
        destination.capacity_floats = output.size();
        assert(pte_engine_render(engine.value, &frame, nullptr, &destination) == PTE_STATUS_OK);
        assert(std::string(pte_engine_last_error(engine.value)).empty());
    });
    worker.join();
    assert(std::string(snapshot) == expected);
    assert(std::string(pte_engine_last_error(engine.value)).empty());
}

thread_local bool reject_error_allocations{};

template <typename T>
struct RejectingAllocator {
    using value_type = T;
    RejectingAllocator() = default;
    template <typename U> RejectingAllocator(const RejectingAllocator<U>&) noexcept {}
    T* allocate(size_t count) {
        if (reject_error_allocations) throw std::bad_alloc();
        return std::allocator<T>{}.allocate(count);
    }
    void deallocate(T* value, size_t count) noexcept {
        std::allocator<T>{}.deallocate(value, count);
    }
    template <typename U> bool operator==(const RejectingAllocator<U>&) const noexcept { return true; }
};

void test_error_storage_allocation_failure_has_static_fallback() {
    std::basic_string<char, std::char_traits<char>, RejectingAllocator<char>> message;
    const char* fallback{};
    reject_error_allocations = true;
    phytoy::detail::store_error(message, fallback,
        "A diagnostic message longer than small-string storage must fail allocation in this test, "
        "while the static fallback remains readable after the exception has been destroyed.");
    reject_error_allocations = false;
    assert(message.empty());
    assert(fallback != nullptr);
    assert(std::string(fallback) == "unable to allocate error message");
    const char* snapshot = fallback;
    phytoy::detail::store_error(message, fallback, "recovered error storage");
    assert(fallback == nullptr);
    assert(message == "recovered error storage");
    assert(std::string(snapshot) == "unable to allocate error message");
}

enum class CallbackFailure { Runtime, InvalidArgument, Allocation, Unknown };

void throwing_stage(void* user, pte_stage_t, const float*, uint32_t, uint32_t, uint32_t) {
    switch (*static_cast<CallbackFailure*>(user)) {
        case CallbackFailure::Runtime:
            throw std::runtime_error("stage callback raised a runtime error that must be contained by the C ABI");
        case CallbackFailure::InvalidArgument:
            throw std::invalid_argument("stage callback rejected its input");
        case CallbackFailure::Allocation:
            throw std::bad_alloc();
        case CallbackFailure::Unknown:
            throw 42;
    }
}

void test_c_abi_contains_callback_exceptions_and_recovers() {
    Engine engine(profile("host_generic_srgb.ptp"), profile("toy_fixed_focus_conformance.ptp"));
    std::vector<float> input(4U * 4U * 3U, 0.3F);
    std::vector<float> output(input.size(), -77.0F);
    pte_frame_f32_t frame{};
    frame.abi_version = PTE_ABI_VERSION;
    frame.format = PTE_PIXEL_FORMAT_SRGB_F32;
    frame.width = frame.height = 4U;
    frame.plane[0] = {input.data(), 12U};
    pte_output_f32_t destination{output.data(), output.size(), 12U};
    for (auto failure : {CallbackFailure::Runtime, CallbackFailure::InvalidArgument,
                         CallbackFailure::Allocation, CallbackFailure::Unknown}) {
        pte_render_options_t options{PTE_ABI_VERSION, 1234U, throwing_stage, &failure};
        const auto expected = failure == CallbackFailure::InvalidArgument
            ? PTE_STATUS_INVALID_ARGUMENT : PTE_STATUS_INTERNAL_ERROR;
        assert(pte_engine_render(engine.value, &frame, &options, &destination) == expected);
        const std::string error(pte_engine_last_error(engine.value));
        assert(!error.empty());
        if (failure == CallbackFailure::Runtime) assert(error.find("runtime error") != std::string::npos);
        if (failure == CallbackFailure::Unknown) assert(error == "unknown render error");
        assert(std::all_of(output.begin(), output.end(), [](float value) { return value == -77.0F; }));
        pte_runtime_stats_t stats{};
        stats.abi_version = PTE_ABI_VERSION;
        assert(pte_engine_get_runtime_stats(engine.value, &stats) == PTE_STATUS_OK);
        assert(stats.rendered_frames == 0U);
    }
    assert(pte_engine_render(engine.value, &frame, nullptr, &destination) == PTE_STATUS_OK);
    assert(std::string(pte_engine_last_error(engine.value)).empty());

    auto* invalid = reinterpret_cast<pte_engine_t*>(static_cast<uintptr_t>(1U));
    assert(pte_engine_create(nullptr, nullptr, &invalid) == PTE_STATUS_INVALID_ARGUMENT);
    assert(invalid == nullptr);
    assert(std::string(pte_last_error()).find("required") != std::string::npos);
    const auto absent = profile("intentionally_missing_profile.ptp");
    assert(pte_engine_create(absent.c_str(), absent.c_str(), &invalid) != PTE_STATUS_OK);
    assert(invalid == nullptr);
    assert(!std::string(pte_last_error()).empty());
    pte_engine_destroy(nullptr);
}

void test_product_toy_profiles_open() {
    for (const auto& [filename, expected_id] : std::array{
             std::pair{"toy_phytoy_plastic_82_v1.ptp", "phytoy.toy.plastic_82"},
             std::pair{"toy_phytoy_street_84_v1.ptp", "phytoy.toy.street_84"},
             std::pair{"toy_phytoy_fisheye_05_v1.ptp", "phytoy.toy.fisheye_05"},
         }) {
        Engine engine(profile("host_generic_srgb.ptp"), profile(filename));
        pte_engine_profile_info_t info{};
        info.abi_version = PTE_ABI_VERSION;
        assert(pte_engine_get_profile_info(engine.value, &info) == PTE_STATUS_OK);
        assert(std::string(info.toy_profile_id) == expected_id);
        assert(std::string(info.toy_profile_version) == "1.0.0");
        assert(std::string(info.toy_profile_type) == "designed");
    }
}

}  // namespace

int main() {
    std::cout << pte_version_string() << '\n';
    test_preview_tile_geometry();
    test_srgb_determinism_and_stages();
    test_yuv_and_raw_execute();
    test_argument_validation();
    test_product_toy_profiles_open();
    test_dimensions_and_padded_stride_validation();
    test_padded_last_row_capacity_and_guards();
    test_yuv_and_raw_stride_budget_validation();
    test_error_snapshot_survives_a_render_on_another_thread();
    test_error_storage_allocation_failure_has_static_fallback();
    test_c_abi_contains_callback_exceptions_and_recovers();
    std::cout << "all native core tests passed\n";
    return 0;
}
