#include "phytoy/phytoy.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <limits>
#include <numeric>
#include <optional>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

#if defined(__ANDROID__)
#include <android/hardware_buffer.h>
#include <dlfcn.h>
#include <unistd.h>
#elif defined(__APPLE__)
#include <mach/mach.h>
#include <sys/resource.h>
#else
#include <sys/resource.h>
#endif

namespace {

struct Arguments {
    std::string host;
    std::string toy;
    std::string backend{"cpu"};
    std::string input{"f32"};
    uint32_t width{1280U};
    uint32_t height{720U};
    uint32_t warmup{3U};
    uint32_t frames{30U};
    uint64_t seed{20260821U};
    std::filesystem::path dump_stages;
    std::optional<uint32_t> constant_input_u8;
    bool sensor_statistics{};
    bool fixed_seed{};
};

uint64_t parse_unsigned(const char* text, const char* option) {
    char* end = nullptr;
    const unsigned long long value = std::strtoull(text, &end, 10);
    if (text[0] == '\0' || end == nullptr || *end != '\0') {
        throw std::invalid_argument(std::string("invalid value for ") + option);
    }
    return static_cast<uint64_t>(value);
}

Arguments parse_arguments(int argc, char** argv) {
    Arguments result;
    for (int index = 1; index < argc; ++index) {
        const std::string_view option(argv[index]);
        if (option == "--help") {
            std::cout << "Usage: phytoy_benchmark --host host.ptp --toy toy.ptp "
                         "[--backend cpu|vulkan|auto] [--input f32|ahb] "
                         "[--width N] [--height N] [--warmup N] [--frames N] "
                         "[--seed N] [--constant-input-u8 0..255] "
                         "[--dump-stages DIR] [--sensor-statistics] [--fixed-seed]\n";
            std::exit(0);
        }
        if (option == "--sensor-statistics") {
            result.sensor_statistics = true;
            continue;
        }
        if (option == "--fixed-seed") {
            result.fixed_seed = true;
            continue;
        }
        if (index + 1 >= argc) throw std::invalid_argument("option needs a value: " + std::string(option));
        const char* value = argv[++index];
        if (option == "--host") result.host = value;
        else if (option == "--toy") result.toy = value;
        else if (option == "--backend") result.backend = value;
        else if (option == "--input") result.input = value;
        else if (option == "--width") result.width = static_cast<uint32_t>(parse_unsigned(value, "--width"));
        else if (option == "--height") result.height = static_cast<uint32_t>(parse_unsigned(value, "--height"));
        else if (option == "--warmup") result.warmup = static_cast<uint32_t>(parse_unsigned(value, "--warmup"));
        else if (option == "--frames") result.frames = static_cast<uint32_t>(parse_unsigned(value, "--frames"));
        else if (option == "--seed") result.seed = parse_unsigned(value, "--seed");
        else if (option == "--dump-stages") result.dump_stages = value;
        else if (option == "--constant-input-u8") {
            result.constant_input_u8 = static_cast<uint32_t>(parse_unsigned(value, "--constant-input-u8"));
        }
        else throw std::invalid_argument("unknown option: " + std::string(option));
    }
    if (result.host.empty() || result.toy.empty()) {
        throw std::invalid_argument("--host and --toy are required");
    }
    if (result.width == 0U || result.height == 0U || result.frames == 0U) {
        throw std::invalid_argument("width, height and frames must be nonzero");
    }
    if (result.input != "f32" && result.input != "ahb") {
        throw std::invalid_argument("--input must be f32 or ahb");
    }
    if (result.constant_input_u8.has_value() && *result.constant_input_u8 > 255U) {
        throw std::invalid_argument("--constant-input-u8 must be between 0 and 255");
    }
    if (result.sensor_statistics && result.warmup == 0U) {
        throw std::invalid_argument("--sensor-statistics requires at least one warmup frame");
    }
    if (result.sensor_statistics && !result.constant_input_u8.has_value()) {
        throw std::invalid_argument("--sensor-statistics requires --constant-input-u8");
    }
    return result;
}

pte_backend_t backend_from(const std::string& backend) {
    if (backend == "cpu") return PTE_BACKEND_CPU;
    if (backend == "vulkan") return PTE_BACKEND_VULKAN;
    if (backend == "auto") return PTE_BACKEND_AUTO;
    throw std::invalid_argument("--backend must be cpu, vulkan or auto");
}

double percentile(std::vector<double> values, double fraction) {
    std::sort(values.begin(), values.end());
    const size_t index = static_cast<size_t>(
        std::round(fraction * static_cast<double>(values.size() - 1U)));
    return values[index];
}

uint64_t proc_status_kib(const char* key) {
#if defined(__ANDROID__) || defined(__linux__)
    std::ifstream input("/proc/self/status");
    std::string name;
    while (input >> name) {
        if (name == key) {
            uint64_t value = 0U;
            input >> value;
            return value;
        }
        input.ignore(std::numeric_limits<std::streamsize>::max(), '\n');
    }
    return 0U;
#elif defined(__APPLE__)
    if (std::string_view(key) == "VmRSS:") {
        mach_task_basic_info_data_t info{};
        mach_msg_type_number_t count = MACH_TASK_BASIC_INFO_COUNT;
        return task_info(mach_task_self(), MACH_TASK_BASIC_INFO,
                         reinterpret_cast<task_info_t>(&info), &count) == KERN_SUCCESS
            ? static_cast<uint64_t>(info.resident_size / 1024U)
            : 0U;
    }
    if (std::string_view(key) == "VmHWM:") {
        rusage usage{};
        return getrusage(RUSAGE_SELF, &usage) == 0
            ? static_cast<uint64_t>(usage.ru_maxrss) / 1024U
            : 0U;
    }
    return 0U;
#else
    return 0U;
#endif
}

class ThermalReader {
public:
    ThermalReader() {
#if defined(__ANDROID__)
        acquire_ = reinterpret_cast<Acquire>(dlsym(RTLD_DEFAULT, "AThermal_acquireManager"));
        release_ = reinterpret_cast<Release>(dlsym(RTLD_DEFAULT, "AThermal_releaseManager"));
        status_ = reinterpret_cast<Status>(dlsym(RTLD_DEFAULT, "AThermal_getCurrentThermalStatus"));
        if (acquire_ != nullptr && release_ != nullptr && status_ != nullptr) manager_ = acquire_();
#endif
    }
    ~ThermalReader() {
#if defined(__ANDROID__)
        if (manager_ != nullptr) release_(manager_);
#endif
    }
    int read() const {
#if defined(__ANDROID__)
        return manager_ == nullptr ? -1 : status_(manager_);
#else
        return -1;
#endif
    }

private:
#if defined(__ANDROID__)
    struct AThermalManager;
    using Acquire = AThermalManager* (*)();
    using Release = void (*)(AThermalManager*);
    using Status = int (*)(AThermalManager*);
    Acquire acquire_{};
    Release release_{};
    Status status_{};
    AThermalManager* manager_{};
#endif
};

struct Engine {
    pte_engine_t* value{};
    Engine(const Arguments& arguments) {
        const pte_status_t created = pte_engine_create(
            arguments.host.c_str(), arguments.toy.c_str(), &value);
        if (created != PTE_STATUS_OK) throw std::runtime_error(pte_last_error());
        const pte_status_t selected = pte_engine_set_backend(value, backend_from(arguments.backend));
        if (selected != PTE_STATUS_OK) throw std::runtime_error(pte_engine_last_error(value));
    }
    ~Engine() { pte_engine_destroy(value); }
    Engine(const Engine&) = delete;
    Engine& operator=(const Engine&) = delete;
};

pte_runtime_stats_t stats(pte_engine_t* engine) {
    pte_runtime_stats_t result{};
    result.abi_version = PTE_ABI_VERSION;
    if (pte_engine_get_runtime_stats(engine, &result) != PTE_STATUS_OK) {
        throw std::runtime_error(pte_engine_last_error(engine));
    }
    return result;
}

void validate_sampled_output(const std::vector<float>& output) {
    const size_t step = std::max<size_t>(output.size() / 1024U, 1U);
    for (size_t index = 0U; index < output.size(); index += step) {
        if (!std::isfinite(output[index]) || output[index] < 0.0F || output[index] > 1.0F) {
            throw std::runtime_error("render produced an invalid sampled output value");
        }
    }
}

void write_pfm(const std::filesystem::path& path, const float* data,
               uint32_t width, uint32_t height, uint32_t channels) {
    std::ofstream output(path, std::ios::binary);
    if (!output) throw std::runtime_error("cannot write stage: " + path.string());
    if (channels == 3U) output << "PF\n";
    else if (channels == 1U) output << "Pf\n";
    else throw std::runtime_error("PFM stage must have one or three channels");
    output << width << ' ' << height << "\n-1.0\n";
    const size_t row_samples = static_cast<size_t>(width) * channels;
    for (uint32_t y = height; y-- > 0U;) {
        output.write(reinterpret_cast<const char*>(data + static_cast<size_t>(y) * row_samples),
                     static_cast<std::streamsize>(row_samples * sizeof(float)));
    }
}

struct StageObserver {
    std::filesystem::path directory;
    bool collect_sensor{};
    bool measurement_active{};
    std::vector<double> sensor_samples;
};

void stage_callback(void* user, pte_stage_t stage, const float* data,
                    uint32_t width, uint32_t height, uint32_t channels) {
    auto& observer = *static_cast<StageObserver*>(user);
    constexpr const char* names[] = {
        "unknown", "01_scene_linear", "02_target_optics",
        "03_target_sensor_dn", "04_target_isp_linear", "05_output_srgb",
    };
    const auto index = static_cast<size_t>(stage);
    if (index == 0U || index >= std::size(names)) {
        throw std::runtime_error("unknown stage callback value");
    }
    if (!observer.directory.empty()) {
        write_pfm(observer.directory / (std::string(names[index]) + ".pfm"),
                  data, width, height, channels);
    }
    if (observer.collect_sensor && observer.measurement_active &&
        stage == PTE_STAGE_TARGET_SENSOR_DN) {
        if (channels != 1U || width < 2U || height < 2U) {
            throw std::runtime_error("sensor statistics require a sensor plane of at least 2x2");
        }
        const uint32_t x0 = (width - 2U) / 2U;
        const uint32_t y0 = (height - 2U) / 2U;
        for (uint32_t y = y0; y < y0 + 2U; ++y) {
            for (uint32_t x = x0; x < x0 + 2U; ++x) {
                observer.sensor_samples.push_back(data[static_cast<size_t>(y) * width + x]);
            }
        }
    }
}

}  // namespace

int main(int argc, char** argv) {
    try {
        const Arguments arguments = parse_arguments(argc, argv);
        Engine engine(arguments);
        const size_t pixel_count = static_cast<size_t>(arguments.width) * arguments.height;
        std::vector<float> input(pixel_count * 3U);
        for (uint32_t y = 0U; y < arguments.height; ++y) {
            for (uint32_t x = 0U; x < arguments.width; ++x) {
                const size_t offset = (static_cast<size_t>(y) * arguments.width + x) * 3U;
                if (arguments.constant_input_u8.has_value()) {
                    const float value = static_cast<float>(*arguments.constant_input_u8) / 255.0F;
                    input[offset] = value;
                    input[offset + 1U] = value;
                    input[offset + 2U] = value;
                } else {
                    input[offset] = static_cast<float>(x % 256U) / 255.0F;
                    input[offset + 1U] = static_cast<float>(y % 256U) / 255.0F;
                    input[offset + 2U] = static_cast<float>((x + y) % 256U) / 255.0F;
                }
            }
        }
        std::vector<float> output(pixel_count * 3U);
        pte_output_f32_t destination{output.data(), output.size(), arguments.width * 3U};
        pte_frame_f32_t frame{};
        frame.abi_version = PTE_ABI_VERSION;
        frame.format = PTE_PIXEL_FORMAT_SRGB_F32;
        frame.width = arguments.width;
        frame.height = arguments.height;
        frame.plane[0] = {input.data(), arguments.width * 3U};

        StageObserver stage_observer{arguments.dump_stages, arguments.sensor_statistics};
        if (!arguments.dump_stages.empty()) {
            std::filesystem::create_directories(arguments.dump_stages);
        }

        pte_ahardware_buffer_frame_t ahb_frame{};
#if defined(__ANDROID__)
        AHardwareBuffer* hardware_buffer = nullptr;
        int acquire_fence_fd = -1;
        if (arguments.input == "ahb") {
            AHardwareBuffer_Desc description{};
            description.width = arguments.width;
            description.height = arguments.height;
            description.layers = 1U;
            description.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
            description.usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
                AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN;
            if (AHardwareBuffer_allocate(&description, &hardware_buffer) != 0) {
                throw std::runtime_error("AHardwareBuffer_allocate failed");
            }
            void* address = nullptr;
            if (AHardwareBuffer_lock(hardware_buffer, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN,
                                     -1, nullptr, &address) != 0) {
                AHardwareBuffer_release(hardware_buffer);
                throw std::runtime_error("AHardwareBuffer_lock failed");
            }
            AHardwareBuffer_Desc actual{};
            AHardwareBuffer_describe(hardware_buffer, &actual);
            auto* bytes = static_cast<uint8_t*>(address);
            for (uint32_t y = 0U; y < arguments.height; ++y) {
                for (uint32_t x = 0U; x < arguments.width; ++x) {
                    const size_t source = (static_cast<size_t>(y) * arguments.width + x) * 3U;
                    const size_t target = (static_cast<size_t>(y) * actual.stride + x) * 4U;
                    bytes[target] = static_cast<uint8_t>(std::round(input[source] * 255.0F));
                    bytes[target + 1U] = static_cast<uint8_t>(std::round(input[source + 1U] * 255.0F));
                    bytes[target + 2U] = static_cast<uint8_t>(std::round(input[source + 2U] * 255.0F));
                    bytes[target + 3U] = 255U;
                }
            }
            if (AHardwareBuffer_unlock(hardware_buffer, &acquire_fence_fd) != 0) {
                AHardwareBuffer_release(hardware_buffer);
                throw std::runtime_error("AHardwareBuffer_unlock failed");
            }
            ahb_frame = {PTE_ABI_VERSION, hardware_buffer, arguments.width,
                         arguments.height, acquire_fence_fd};
            if (pte_engine_supports_ahardware_buffer_input(engine.value) == 0U) {
                if (acquire_fence_fd >= 0) close(acquire_fence_fd);
                AHardwareBuffer_release(hardware_buffer);
                std::cerr << "AHardwareBuffer Vulkan input is unsupported\n";
                return 77;
            }
        }
#else
        if (arguments.input == "ahb") throw std::runtime_error("AHardwareBuffer input is Android-only");
#endif

        const auto render_once = [&](uint64_t seed) {
            pte_render_options_t options{};
            options.abi_version = PTE_ABI_VERSION;
            options.seed = seed;
            if (!arguments.dump_stages.empty() || arguments.sensor_statistics) {
                options.stage_callback = stage_callback;
                options.stage_user_data = &stage_observer;
            }
            const pte_status_t status = arguments.input == "ahb"
                ? pte_engine_render_ahardware_buffer(engine.value, &ahb_frame, &options, &destination)
                : pte_engine_render(engine.value, &frame, &options, &destination);
            if (status != PTE_STATUS_OK) throw std::runtime_error(pte_engine_last_error(engine.value));
#if defined(__ANDROID__)
            if (ahb_frame.acquire_fence_fd >= 0) {
                close(ahb_frame.acquire_fence_fd);
                acquire_fence_fd = -1;
                ahb_frame.acquire_fence_fd = -1;
            }
#endif
        };

        for (uint32_t index = 0U; index < arguments.warmup; ++index) {
            render_once(arguments.fixed_seed ? arguments.seed : arguments.seed + index);
        }
        const pte_runtime_stats_t before = stats(engine.value);
        const uint64_t rss_before = proc_status_kib("VmRSS:");
        ThermalReader thermal;
        const int thermal_before = thermal.read();
        int thermal_max = thermal_before;
        stage_observer.measurement_active = true;
        std::vector<double> latencies;
        latencies.reserve(arguments.frames);
        for (uint32_t index = 0U; index < arguments.frames; ++index) {
            const auto start = std::chrono::steady_clock::now();
            render_once(arguments.fixed_seed
                ? arguments.seed
                : arguments.seed + arguments.warmup + index);
            const auto end = std::chrono::steady_clock::now();
            latencies.push_back(std::chrono::duration<double, std::milli>(end - start).count());
            validate_sampled_output(output);
            thermal_max = std::max(thermal_max, thermal.read());
        }
        const int thermal_after = thermal.read();
        const uint64_t rss_after = proc_status_kib("VmRSS:");
        const uint64_t peak_rss = proc_status_kib("VmHWM:");
        const pte_runtime_stats_t after = stats(engine.value);

#if defined(__ANDROID__)
        if (acquire_fence_fd >= 0) close(acquire_fence_fd);
        if (hardware_buffer != nullptr) AHardwareBuffer_release(hardware_buffer);
#endif

        const uint64_t submit_delta = after.vulkan_queue_submissions - before.vulkan_queue_submissions;
        const uint64_t allocation_delta = after.vulkan_resource_allocations - before.vulkan_resource_allocations;
        const uint64_t zero_copy_delta = after.zero_copy_input_frames - before.zero_copy_input_frames;
        const uint64_t import_delta = after.ahardware_buffer_imports - before.ahardware_buffer_imports;
        const double sum = std::accumulate(latencies.begin(), latencies.end(), 0.0);
        double sensor_mean = 0.0;
        double sensor_variance = 0.0;
        bool sensor_fixed_seed_repeatable = true;
        if (arguments.sensor_statistics) {
            if (stage_observer.sensor_samples.empty()) {
                throw std::runtime_error("sensor statistics callback collected no samples");
            }
            sensor_mean = std::accumulate(
                stage_observer.sensor_samples.begin(), stage_observer.sensor_samples.end(), 0.0) /
                static_cast<double>(stage_observer.sensor_samples.size());
            for (const double sample : stage_observer.sensor_samples) {
                const double difference = sample - sensor_mean;
                sensor_variance += difference * difference;
            }
            sensor_variance /= static_cast<double>(stage_observer.sensor_samples.size());
            if (arguments.fixed_seed) {
                constexpr size_t samples_per_frame = 4U;
                if (stage_observer.sensor_samples.size() !=
                    static_cast<size_t>(arguments.frames) * samples_per_frame) {
                    throw std::runtime_error("unexpected fixed-seed sensor sample count");
                }
                for (size_t offset = samples_per_frame;
                     offset < stage_observer.sensor_samples.size(); offset += samples_per_frame) {
                    sensor_fixed_seed_repeatable = sensor_fixed_seed_repeatable && std::equal(
                        stage_observer.sensor_samples.begin(),
                        stage_observer.sensor_samples.begin() + samples_per_frame,
                        stage_observer.sensor_samples.begin() + static_cast<std::ptrdiff_t>(offset));
                }
            }
        }

        std::cout << "{\n"
                  << "  \"engine\": \"" << pte_version_string() << "\",\n"
                  << "  \"backend\": \"" << arguments.backend << "\",\n"
                  << "  \"input\": \"" << arguments.input << "\",\n"
                  << "  \"width\": " << arguments.width << ",\n"
                  << "  \"height\": " << arguments.height << ",\n"
                  << "  \"warmup_frames\": " << arguments.warmup << ",\n"
                  << "  \"measured_frames\": " << arguments.frames << ",\n"
                  << "  \"measured_seed_start\": "
                  << (arguments.fixed_seed ? arguments.seed : arguments.seed + arguments.warmup) << ",\n"
                  << "  \"fixed_seed\": " << (arguments.fixed_seed ? "true" : "false") << ",\n"
                  << "  \"latency_ms_mean\": " << sum / static_cast<double>(latencies.size()) << ",\n"
                  << "  \"latency_ms_p50\": " << percentile(latencies, 0.50) << ",\n"
                  << "  \"latency_ms_p95\": " << percentile(latencies, 0.95) << ",\n"
                  << "  \"latency_ms_p99\": " << percentile(latencies, 0.99) << ",\n"
                  << "  \"rss_kib_before\": " << rss_before << ",\n"
                  << "  \"rss_kib_after\": " << rss_after << ",\n"
                  << "  \"peak_rss_kib\": " << peak_rss << ",\n"
                  << "  \"vulkan_allocated_bytes\": " << after.vulkan_allocated_bytes << ",\n"
                  << "  \"queue_submission_delta\": " << submit_delta << ",\n"
                  << "  \"resource_allocation_delta_after_warmup\": " << allocation_delta << ",\n"
                  << "  \"ahardware_buffer_import_delta\": " << import_delta << ",\n"
                  << "  \"zero_copy_frame_delta\": " << zero_copy_delta << ",\n"
                  << "  \"thermal_status_before\": " << thermal_before << ",\n"
                  << "  \"thermal_status_after\": " << thermal_after << ",\n"
                  << "  \"thermal_status_max\": " << thermal_max;
        if (arguments.sensor_statistics) {
            std::cout << ",\n"
                      << "  \"constant_input_u8\": " << *arguments.constant_input_u8 << ",\n"
                      << "  \"sensor_sample_count\": " << stage_observer.sensor_samples.size() << ",\n"
                      << "  \"sensor_sample_mean\": " << sensor_mean << ",\n"
                      << "  \"sensor_sample_variance\": " << sensor_variance << ",\n"
                      << "  \"sensor_fixed_seed_repeatable\": "
                      << (sensor_fixed_seed_repeatable ? "true" : "false");
        }
        std::cout << "\n}\n";

        if (allocation_delta != 0U) return 2;
        if (arguments.backend == "vulkan" && submit_delta != arguments.frames) return 3;
        if (arguments.input == "ahb" &&
            (zero_copy_delta != arguments.frames || import_delta != 0U)) return 4;
        if (arguments.sensor_statistics && arguments.fixed_seed &&
            !sensor_fixed_seed_repeatable) return 5;
        return 0;
    } catch (const std::exception& exception) {
        std::cerr << "phytoy_benchmark: " << exception.what() << '\n';
        return 1;
    }
}
