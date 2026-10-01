#include "phytoy/phytoy.h"

#include "cpu_backend.hpp"
#include "error_state.hpp"
#include "profile.hpp"
#include "vulkan_backend.hpp"

#include <algorithm>
#include <cstddef>
#include <exception>
#include <filesystem>
#include <memory>
#include <limits>
#include <mutex>
#include <new>
#include <string>

struct pte_engine {
    phytoy::HostProfile host;
    phytoy::ToyProfile toy;
    pte_backend_t backend{PTE_BACKEND_CPU};
    std::unique_ptr<phytoy::VulkanBackend> vulkan;
    std::string last_error;
    const char* last_error_fallback{};
    uint64_t rendered_frames{};
    uint64_t cpu_frames{};
    uint64_t vulkan_frames{};
    uint64_t zero_copy_input_frames{};
    mutable std::mutex mutex;
};

namespace {

thread_local std::string global_last_error;
thread_local const char* global_error_fallback{};
thread_local std::string engine_error_snapshot;

void store_global_error(const char* message) noexcept {
    phytoy::detail::store_error(global_last_error, global_error_fallback, message);
}

void store_engine_error(pte_engine& engine, const char* message) noexcept {
    phytoy::detail::store_error(engine.last_error, engine.last_error_fallback, message);
}

void clear_global_error() noexcept {
    global_last_error.clear();
    global_error_fallback = nullptr;
}

void clear_engine_error(pte_engine& engine) noexcept {
    engine.last_error.clear();
    engine.last_error_fallback = nullptr;
}

const char* global_error_message() noexcept {
    return global_error_fallback == nullptr ? global_last_error.c_str() : global_error_fallback;
}

void validate_dimensions(uint32_t width, uint32_t height) {
    if (width == 0U || height == 0U) {
        throw std::invalid_argument("input dimensions must be nonzero");
    }
    if (width > PTE_MAX_IMAGE_DIMENSION || height > PTE_MAX_IMAGE_DIMENSION ||
        static_cast<uint64_t>(width) * height > PTE_MAX_IMAGE_PIXELS) {
        throw std::invalid_argument(
            "input dimensions exceed the 8192-per-axis / 16777216-pixel frame budget");
    }
}

size_t validate_plane_span(uint32_t stride, uint32_t columns, uint32_t rows,
                           const char* name) {
    if (stride < columns) {
        throw std::invalid_argument(std::string(name) + " row stride is too small");
    }
    // The dimensions have already been bounded. Compute in uint64_t before
    // converting to size_t, including on 32-bit hosts, and bound padded spans.
    const uint64_t span = static_cast<uint64_t>(rows - 1U) * stride + columns;
    constexpr uint64_t maximum_floats = static_cast<uint64_t>(PTE_MAX_IMAGE_PIXELS) * 3U;
    if (span > maximum_floats || span > std::numeric_limits<size_t>::max() / sizeof(float)) {
        throw std::invalid_argument(std::string(name) + " padded plane exceeds the frame memory budget");
    }
    return static_cast<size_t>(span);
}

size_t validate_output(const pte_output_f32_t& output, uint32_t width, uint32_t height) {
    if (output.data == nullptr) throw std::invalid_argument("output data is null");
    const uint32_t columns = width * 3U;
    const uint32_t stride = output.row_stride_floats == 0U ? columns : output.row_stride_floats;
    return validate_plane_span(stride, columns, height, "output");
}

pte_status_t validate_frame(const pte_engine& engine, const pte_frame_f32_t& frame) {
    if (frame.abi_version != PTE_ABI_VERSION) {
        throw std::invalid_argument("input frame ABI version mismatch");
    }
    validate_dimensions(frame.width, frame.height);
    if (frame.plane[0].data == nullptr) throw std::invalid_argument("input plane 0 is null");
    if (frame.format == PTE_PIXEL_FORMAT_SRGB_F32) {
        if (engine.host.input_mode != phytoy::HostInputMode::Srgb) {
            throw std::invalid_argument("sRGB frame requires an sRGB host profile");
        }
        validate_plane_span(frame.plane[0].row_stride_floats, frame.width * 3U,
                            frame.height, "sRGB input");
    } else if (frame.format == PTE_PIXEL_FORMAT_YUV420_BT709_F32) {
        if (engine.host.input_mode != phytoy::HostInputMode::Yuv420) {
            throw std::invalid_argument("YUV frame requires a YUV host profile");
        }
        if (frame.plane[1].data == nullptr) throw std::invalid_argument("YUV UV plane is null");
        validate_plane_span(frame.plane[0].row_stride_floats, frame.width,
                            frame.height, "Y input");
        validate_plane_span(frame.plane[1].row_stride_floats, ((frame.width + 1U) / 2U) * 2U,
                            (frame.height + 1U) / 2U, "UV input");
    } else if (frame.format == PTE_PIXEL_FORMAT_RAW_BAYER_F32) {
        if (engine.host.input_mode != phytoy::HostInputMode::Raw) {
            throw std::invalid_argument("RAW frame requires a RAW host profile");
        }
        validate_plane_span(frame.plane[0].row_stride_floats, frame.width,
                            frame.height, "RAW input");
    } else {
        throw std::invalid_argument("unsupported pixel format");
    }
    return PTE_STATUS_OK;
}

pte_status_t classify_exception(const std::exception& exception) {
    if (dynamic_cast<const std::invalid_argument*>(&exception) != nullptr) {
        return PTE_STATUS_INVALID_ARGUMENT;
    }
    return PTE_STATUS_INTERNAL_ERROR;
}

template <typename Function>
pte_status_t with_locked_engine(pte_engine& engine, const char* unknown_error,
                                Function&& function) noexcept {
    try {
        // Lock acquisition can throw std::system_error, so it belongs inside
        // the outer boundary as well. Never write engine state without a lock.
        std::lock_guard lock(engine.mutex);
        clear_engine_error(engine);
        try {
            return function();
        } catch (const std::exception& exception) {
            store_engine_error(engine, exception.what());
            return classify_exception(exception);
        } catch (...) {
            store_engine_error(engine, unknown_error);
            return PTE_STATUS_INTERNAL_ERROR;
        }
    } catch (const std::exception& exception) {
        store_global_error(exception.what());
        return PTE_STATUS_INTERNAL_ERROR;
    } catch (...) {
        store_global_error("unable to lock engine");
        return PTE_STATUS_INTERNAL_ERROR;
    }
}

pte_status_t process_ahardware_buffer_locked(
    pte_engine& engine,
    const pte_ahardware_buffer_frame_t& input,
    const pte_render_options_t* options,
    pte_output_f32_t* output) {
    if (input.abi_version != PTE_ABI_VERSION) {
        throw std::invalid_argument("AHardwareBuffer frame ABI version mismatch");
    }
    if (input.buffer == nullptr) throw std::invalid_argument("AHardwareBuffer is required");
    validate_dimensions(input.width, input.height);
    if (input.acquire_fence_fd < -1) {
        throw std::invalid_argument("AHardwareBuffer acquire fence must be -1 or a valid fd");
    }
    if (options != nullptr && options->abi_version != PTE_ABI_VERSION) {
        throw std::invalid_argument("render options ABI version mismatch");
    }
    if (output != nullptr) {
        const size_t required = validate_output(*output, input.width, input.height);
        if (output->capacity_floats < required) {
            const auto message = "output capacity is too small; required floats: " +
                std::to_string(required);
            store_engine_error(engine, message.c_str());
            return PTE_STATUS_BUFFER_TOO_SMALL;
        }
    }
    if (engine.backend == PTE_BACKEND_CPU) {
        store_engine_error(engine,
            "AHardwareBuffer zero-copy input requires the Vulkan or AUTO backend");
        return PTE_STATUS_UNSUPPORTED;
    }
    if (!engine.vulkan) engine.vulkan = std::make_unique<phytoy::VulkanBackend>();
    if (!engine.vulkan->ahardware_buffer_input_available()) {
        store_engine_error(engine, "Vulkan AHardwareBuffer input is unavailable on this device");
        return PTE_STATUS_UNSUPPORTED;
    }

    const uint64_t seed = options == nullptr ? 1U : options->seed;
    const pte_stage_callback_f32 callback = options == nullptr ? nullptr : options->stage_callback;
    void* callback_data = options == nullptr ? nullptr : options->stage_user_data;
    engine.vulkan->render_ahardware_buffer(
        input.buffer, input.width, input.height, input.acquire_fence_fd,
        engine.host, engine.toy, seed, callback, callback_data, output);
    ++engine.rendered_frames;
    ++engine.vulkan_frames;
    ++engine.zero_copy_input_frames;
    return PTE_STATUS_OK;
}

}  // namespace

extern "C" {

const char* pte_version_string(void) {
    return "PhyToyEngine/0.3.1 ABI/2";
}

const char* pte_last_error(void) {
    return global_error_message();
}

pte_status_t pte_engine_create(
    const char* host_profile_package_path,
    const char* toy_profile_package_path,
    pte_engine_t** out_engine) {
    clear_global_error();
    if (out_engine != nullptr) *out_engine = nullptr;
    if (host_profile_package_path == nullptr || toy_profile_package_path == nullptr || out_engine == nullptr) {
        store_global_error("profile paths and out_engine are required");
        return PTE_STATUS_INVALID_ARGUMENT;
    }
    try {
        auto engine = std::make_unique<pte_engine>();
        engine->host = phytoy::load_host_profile_package(std::filesystem::path(host_profile_package_path));
        engine->toy = phytoy::load_toy_profile_package(std::filesystem::path(toy_profile_package_path));
        *out_engine = engine.release();
        return PTE_STATUS_OK;
    } catch (const std::filesystem::filesystem_error& exception) {
        store_global_error(exception.what());
        return PTE_STATUS_IO_ERROR;
    } catch (const std::bad_alloc& exception) {
        store_global_error(exception.what());
        return PTE_STATUS_INTERNAL_ERROR;
    } catch (const std::exception& exception) {
        store_global_error(exception.what());
        return PTE_STATUS_PROFILE_ERROR;
    } catch (...) {
        store_global_error("unknown error while creating engine");
        return PTE_STATUS_INTERNAL_ERROR;
    }
}

void pte_engine_destroy(pte_engine_t* engine) {
    // Engine/Vulkan destructors only release owned resources and are noexcept.
    // Null is accepted, as with delete.
    delete engine;
}

uint32_t pte_backend_is_available(pte_backend_t backend) {
    if (backend == PTE_BACKEND_CPU || backend == PTE_BACKEND_AUTO) return 1U;
    if (backend == PTE_BACKEND_VULKAN) return phytoy::vulkan_backend_available() ? 1U : 0U;
    return 0U;
}

pte_status_t pte_engine_set_backend(pte_engine_t* engine, pte_backend_t backend) {
    if (engine == nullptr) {
        store_global_error("engine is required");
        return PTE_STATUS_INVALID_ARGUMENT;
    }
    return with_locked_engine(*engine, "unknown backend initialization error", [&]() {
        if (backend != PTE_BACKEND_CPU && backend != PTE_BACKEND_VULKAN && backend != PTE_BACKEND_AUTO) {
            store_engine_error(*engine, "unsupported backend");
            return PTE_STATUS_INVALID_ARGUMENT;
        }
        if (backend == PTE_BACKEND_VULKAN || backend == PTE_BACKEND_AUTO) {
            if (!engine->vulkan) engine->vulkan = std::make_unique<phytoy::VulkanBackend>();
            if (backend == PTE_BACKEND_VULKAN && !engine->vulkan->available()) {
                store_engine_error(*engine, "Vulkan backend is unavailable");
                return PTE_STATUS_INTERNAL_ERROR;
            }
        }
        engine->backend = backend;
        return PTE_STATUS_OK;
    });
}

pte_status_t pte_engine_get_profile_info(
    pte_engine_t* engine,
    pte_engine_profile_info_t* out_info) {
    if (engine == nullptr || out_info == nullptr) {
        store_global_error("engine and out_info are required");
        return PTE_STATUS_INVALID_ARGUMENT;
    }
    return with_locked_engine(*engine, "unknown profile-info error", [&]() {
        if (out_info->abi_version != PTE_ABI_VERSION) {
            store_engine_error(*engine, "profile info ABI version mismatch");
            return PTE_STATUS_INVALID_ARGUMENT;
        }
        out_info->host_profile_id = engine->host.id.c_str();
        out_info->toy_profile_id = engine->toy.id.c_str();
        out_info->toy_profile_version = engine->toy.version.c_str();
        out_info->toy_profile_type = engine->toy.provenance.profile_type.c_str();
        out_info->toy_calibration_status = engine->toy.provenance.calibration_status.c_str();
        out_info->toy_target_name = engine->toy.provenance.target_name.c_str();
        out_info->toy_dataset_id = engine->toy.provenance.dataset_id.c_str();
        out_info->toy_dataset_sha256 = engine->toy.provenance.dataset_sha256.c_str();
        return PTE_STATUS_OK;
    });
}

pte_status_t pte_engine_get_runtime_stats(
    pte_engine_t* engine,
    pte_runtime_stats_t* out_stats) {
    if (engine == nullptr || out_stats == nullptr) {
        store_global_error("engine and out_stats are required");
        return PTE_STATUS_INVALID_ARGUMENT;
    }
    return with_locked_engine(*engine, "unknown runtime-stats error", [&]() {
        if (out_stats->abi_version != PTE_ABI_VERSION) {
            store_engine_error(*engine, "runtime stats ABI version mismatch");
            return PTE_STATUS_INVALID_ARGUMENT;
        }
        const phytoy::VulkanRuntimeStats vulkan_stats = engine->vulkan
            ? engine->vulkan->stats() : phytoy::VulkanRuntimeStats{};
        out_stats->rendered_frames = engine->rendered_frames;
        out_stats->cpu_frames = engine->cpu_frames;
        out_stats->vulkan_frames = engine->vulkan_frames;
        out_stats->vulkan_queue_submissions = vulkan_stats.queue_submissions;
        out_stats->vulkan_resource_allocations = vulkan_stats.resource_allocations;
        out_stats->vulkan_allocated_bytes = vulkan_stats.allocated_bytes;
        out_stats->ahardware_buffer_imports = vulkan_stats.ahardware_buffer_imports;
        out_stats->zero_copy_input_frames = engine->zero_copy_input_frames;
        out_stats->vulkan_presented_frames = vulkan_stats.presented_frames;
        out_stats->vulkan_swapchain_recreates = vulkan_stats.swapchain_recreates;
        out_stats->vulkan_output_width = vulkan_stats.output_width;
        out_stats->vulkan_output_height = vulkan_stats.output_height;
        return PTE_STATUS_OK;
    });
}

uint32_t pte_engine_supports_ahardware_buffer_input(pte_engine_t* engine) {
    if (engine == nullptr) return 0U;
    const auto status = with_locked_engine(*engine, "unknown buffer-import capability error", [&]() {
        if (!engine->vulkan) engine->vulkan = std::make_unique<phytoy::VulkanBackend>();
        return engine->vulkan->ahardware_buffer_input_available()
            ? PTE_STATUS_OK : PTE_STATUS_UNSUPPORTED;
    });
    return status == PTE_STATUS_OK ? 1U : 0U;
}

pte_status_t pte_engine_forget_ahardware_buffer(
    pte_engine_t* engine,
    AHardwareBuffer* buffer) {
    if (engine == nullptr) {
        store_global_error("engine is required");
        return PTE_STATUS_INVALID_ARGUMENT;
    }
    return with_locked_engine(*engine, "unknown buffer-forget error", [&]() {
        if (engine->vulkan) engine->vulkan->forget_ahardware_buffer(buffer);
        return PTE_STATUS_OK;
    });
}

pte_status_t pte_engine_set_output_surface(
    pte_engine_t* engine,
    ANativeWindow* window,
    uint32_t rotation_degrees) {
    if (engine == nullptr || window == nullptr) {
        store_global_error("engine and Android output window are required");
        return PTE_STATUS_INVALID_ARGUMENT;
    }
    return with_locked_engine(*engine, "unknown Vulkan output-surface error", [&]() {
        if (!engine->vulkan) engine->vulkan = std::make_unique<phytoy::VulkanBackend>();
        engine->vulkan->set_output_window(window, rotation_degrees);
        return PTE_STATUS_OK;
    });
}

pte_status_t pte_engine_render_ahardware_buffer(
    pte_engine_t* engine,
    const pte_ahardware_buffer_frame_t* input,
    const pte_render_options_t* options,
    pte_output_f32_t* output) {
    if (engine == nullptr || input == nullptr || output == nullptr) {
        store_global_error("engine, AHardwareBuffer input and output are required");
        return PTE_STATUS_INVALID_ARGUMENT;
    }
    return with_locked_engine(*engine, "unknown AHardwareBuffer render error", [&]() {
        return process_ahardware_buffer_locked(*engine, *input, options, output);
    });
}

pte_status_t pte_engine_process_ahardware_buffer(
    pte_engine_t* engine,
    const pte_ahardware_buffer_frame_t* input,
    const pte_render_options_t* options) {
    if (engine == nullptr || input == nullptr) {
        store_global_error("engine and AHardwareBuffer input are required");
        return PTE_STATUS_INVALID_ARGUMENT;
    }
    return with_locked_engine(*engine, "unknown AHardwareBuffer render error", [&]() {
        return process_ahardware_buffer_locked(*engine, *input, options, nullptr);
    });
}

pte_status_t pte_engine_render(
    pte_engine_t* engine,
    const pte_frame_f32_t* input,
    const pte_render_options_t* options,
    pte_output_f32_t* output) {
    if (engine == nullptr || input == nullptr || output == nullptr) {
        store_global_error("engine, input and output are required");
        return PTE_STATUS_INVALID_ARGUMENT;
    }
    return with_locked_engine(*engine, "unknown render error", [&]() {
        validate_frame(*engine, *input);
        if (options != nullptr && options->abi_version != PTE_ABI_VERSION) {
            throw std::invalid_argument("render options ABI version mismatch");
        }
        const size_t required = validate_output(*output, input->width, input->height);
        const uint32_t output_stride = output->row_stride_floats == 0U
            ? input->width * 3U : output->row_stride_floats;
        if (output->capacity_floats < required) {
            const auto message = "output capacity is too small; required floats: " + std::to_string(required);
            store_engine_error(*engine, message.c_str());
            return PTE_STATUS_BUFFER_TOO_SMALL;
        }

        const uint64_t seed = options == nullptr ? 1U : options->seed;
        const pte_stage_callback_f32 callback = options == nullptr ? nullptr : options->stage_callback;
        void* callback_data = options == nullptr ? nullptr : options->stage_user_data;
        const bool use_vulkan = engine->backend == PTE_BACKEND_VULKAN ||
            (engine->backend == PTE_BACKEND_AUTO && engine->vulkan && engine->vulkan->available());
        if (use_vulkan) {
            engine->vulkan->render(
                *input, engine->host, engine->toy, seed, callback, callback_data, *output);
        } else {
            const phytoy::Image rendered = phytoy::render_cpu(
                *input, engine->host, engine->toy, seed, callback, callback_data);
            for (uint32_t y = 0; y < rendered.height; ++y) {
                std::copy_n(rendered.pixels.data() + static_cast<size_t>(y) * rendered.width * 3U,
                            static_cast<size_t>(rendered.width) * 3U,
                            output->data + static_cast<size_t>(y) * output_stride);
            }
        }
        ++engine->rendered_frames;
        if (use_vulkan) ++engine->vulkan_frames;
        else ++engine->cpu_frames;
        return PTE_STATUS_OK;
    });
}

const char* pte_engine_last_error(const pte_engine_t* engine) {
    if (engine == nullptr) return global_error_message();
    try {
        std::lock_guard lock(engine->mutex);
        if (engine->last_error_fallback != nullptr) return engine->last_error_fallback;
        engine_error_snapshot = engine->last_error;
        return engine_error_snapshot.c_str();
    } catch (...) {
        return "unable to snapshot engine error";
    }
}

}  // extern "C"
