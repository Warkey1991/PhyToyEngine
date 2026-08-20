#include "phytoy/phytoy.h"

#include "cpu_backend.hpp"
#include "profile.hpp"
#include "vulkan_backend.hpp"

#include <algorithm>
#include <cstddef>
#include <exception>
#include <filesystem>
#include <memory>
#include <mutex>
#include <new>
#include <string>

struct pte_engine {
    phytoy::HostProfile host;
    phytoy::ToyProfile toy;
    pte_backend_t backend{PTE_BACKEND_CPU};
    std::unique_ptr<phytoy::VulkanBackend> vulkan;
    std::string last_error;
    std::mutex mutex;
};

namespace {

thread_local std::string global_last_error;

pte_status_t validate_frame(const pte_engine& engine, const pte_frame_f32_t& frame) {
    if (frame.abi_version != PTE_ABI_VERSION) {
        throw std::invalid_argument("input frame ABI version mismatch");
    }
    if (frame.width == 0U || frame.height == 0U) {
        throw std::invalid_argument("input dimensions must be nonzero");
    }
    if (frame.plane[0].data == nullptr) throw std::invalid_argument("input plane 0 is null");
    if (frame.format == PTE_PIXEL_FORMAT_SRGB_F32) {
        if (engine.host.input_mode != phytoy::HostInputMode::Srgb) {
            throw std::invalid_argument("sRGB frame requires an sRGB host profile");
        }
        if (frame.plane[0].row_stride_floats < frame.width * 3U) {
            throw std::invalid_argument("sRGB row stride is too small");
        }
    } else if (frame.format == PTE_PIXEL_FORMAT_YUV420_BT709_F32) {
        if (engine.host.input_mode != phytoy::HostInputMode::Yuv420) {
            throw std::invalid_argument("YUV frame requires a YUV host profile");
        }
        if (frame.plane[1].data == nullptr) throw std::invalid_argument("YUV UV plane is null");
        if (frame.plane[0].row_stride_floats < frame.width ||
            frame.plane[1].row_stride_floats < ((frame.width + 1U) / 2U) * 2U) {
            throw std::invalid_argument("YUV row stride is too small");
        }
    } else if (frame.format == PTE_PIXEL_FORMAT_RAW_BAYER_F32) {
        if (engine.host.input_mode != phytoy::HostInputMode::Raw) {
            throw std::invalid_argument("RAW frame requires a RAW host profile");
        }
        if (frame.plane[0].row_stride_floats < frame.width) {
            throw std::invalid_argument("RAW row stride is too small");
        }
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

}  // namespace

extern "C" {

const char* pte_version_string(void) {
    return "PhyToyEngine/0.1.0 ABI/1";
}

const char* pte_last_error(void) {
    return global_last_error.c_str();
}

pte_status_t pte_engine_create(
    const char* host_profile_package_path,
    const char* toy_profile_package_path,
    pte_engine_t** out_engine) {
    global_last_error.clear();
    if (host_profile_package_path == nullptr || toy_profile_package_path == nullptr || out_engine == nullptr) {
        global_last_error = "profile paths and out_engine are required";
        return PTE_STATUS_INVALID_ARGUMENT;
    }
    *out_engine = nullptr;
    try {
        auto engine = std::make_unique<pte_engine>();
        engine->host = phytoy::load_host_profile_package(std::filesystem::path(host_profile_package_path));
        engine->toy = phytoy::load_toy_profile_package(std::filesystem::path(toy_profile_package_path));
        *out_engine = engine.release();
        return PTE_STATUS_OK;
    } catch (const std::filesystem::filesystem_error& exception) {
        global_last_error = exception.what();
        return PTE_STATUS_IO_ERROR;
    } catch (const std::exception& exception) {
        global_last_error = exception.what();
        return PTE_STATUS_PROFILE_ERROR;
    } catch (...) {
        global_last_error = "unknown error while creating engine";
        return PTE_STATUS_INTERNAL_ERROR;
    }
}

void pte_engine_destroy(pte_engine_t* engine) {
    delete engine;
}

uint32_t pte_backend_is_available(pte_backend_t backend) {
    if (backend == PTE_BACKEND_CPU || backend == PTE_BACKEND_AUTO) return 1U;
    if (backend == PTE_BACKEND_VULKAN) return phytoy::vulkan_backend_available() ? 1U : 0U;
    return 0U;
}

pte_status_t pte_engine_set_backend(pte_engine_t* engine, pte_backend_t backend) {
    if (engine == nullptr) {
        global_last_error = "engine is required";
        return PTE_STATUS_INVALID_ARGUMENT;
    }
    std::lock_guard lock(engine->mutex);
    engine->last_error.clear();
    if (backend != PTE_BACKEND_CPU && backend != PTE_BACKEND_VULKAN && backend != PTE_BACKEND_AUTO) {
        engine->last_error = "unsupported backend";
        return PTE_STATUS_INVALID_ARGUMENT;
    }
    if (backend == PTE_BACKEND_VULKAN || backend == PTE_BACKEND_AUTO) {
        if (!engine->vulkan) engine->vulkan = std::make_unique<phytoy::VulkanBackend>();
        if (backend == PTE_BACKEND_VULKAN && !engine->vulkan->available()) {
            engine->last_error = "Vulkan backend is unavailable";
            return PTE_STATUS_INTERNAL_ERROR;
        }
    }
    engine->backend = backend;
    return PTE_STATUS_OK;
}

pte_status_t pte_engine_render(
    pte_engine_t* engine,
    const pte_frame_f32_t* input,
    const pte_render_options_t* options,
    pte_output_f32_t* output) {
    if (engine == nullptr || input == nullptr || output == nullptr) {
        global_last_error = "engine, input and output are required";
        return PTE_STATUS_INVALID_ARGUMENT;
    }
    std::lock_guard lock(engine->mutex);
    engine->last_error.clear();
    try {
        validate_frame(*engine, *input);
        if (options != nullptr && options->abi_version != PTE_ABI_VERSION) {
            throw std::invalid_argument("render options ABI version mismatch");
        }
        if (output->data == nullptr) throw std::invalid_argument("output data is null");
        const uint32_t output_stride = output->row_stride_floats == 0U
            ? input->width * 3U
            : output->row_stride_floats;
        if (output_stride < input->width * 3U) throw std::invalid_argument("output row stride is too small");
        const size_t required = static_cast<size_t>(output_stride) * input->height;
        if (output->capacity_floats < required) {
            engine->last_error = "output capacity is too small; required floats: " + std::to_string(required);
            return PTE_STATUS_BUFFER_TOO_SMALL;
        }

        const uint64_t seed = options == nullptr ? 1U : options->seed;
        const pte_stage_callback_f32 callback = options == nullptr ? nullptr : options->stage_callback;
        void* callback_data = options == nullptr ? nullptr : options->stage_user_data;
        const bool use_vulkan = engine->backend == PTE_BACKEND_VULKAN ||
            (engine->backend == PTE_BACKEND_AUTO && engine->vulkan && engine->vulkan->available());
        const phytoy::Image rendered = use_vulkan
            ? engine->vulkan->render(*input, engine->host, engine->toy, seed, callback, callback_data)
            : phytoy::render_cpu(*input, engine->host, engine->toy, seed, callback, callback_data);
        for (uint32_t y = 0; y < rendered.height; ++y) {
            std::copy_n(rendered.pixels.data() + static_cast<size_t>(y) * rendered.width * 3U,
                        static_cast<size_t>(rendered.width) * 3U,
                        output->data + static_cast<size_t>(y) * output_stride);
        }
        return PTE_STATUS_OK;
    } catch (const std::exception& exception) {
        engine->last_error = exception.what();
        return classify_exception(exception);
    } catch (...) {
        engine->last_error = "unknown render error";
        return PTE_STATUS_INTERNAL_ERROR;
    }
}

const char* pte_engine_last_error(const pte_engine_t* engine) {
    return engine == nullptr ? global_last_error.c_str() : engine->last_error.c_str();
}

}  // extern "C"
