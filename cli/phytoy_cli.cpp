#include "phytoy/phytoy.h"

#include <algorithm>
#include <cctype>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

namespace {

struct PnmImage {
    uint32_t width{};
    uint32_t height{};
    uint32_t channels{};
    uint32_t maximum{};
    std::vector<float> pixels;
};

std::string token(std::istream& input) {
    std::string result;
    while (input) {
        const int next = input.peek();
        if (next == '#') {
            input.ignore(std::numeric_limits<std::streamsize>::max(), '\n');
        } else if (next != EOF && std::isspace(static_cast<unsigned char>(next)) != 0) {
            input.get();
        } else {
            break;
        }
    }
    while (input) {
        const int next = input.peek();
        if (next == EOF || std::isspace(static_cast<unsigned char>(next)) != 0 || next == '#') break;
        result.push_back(static_cast<char>(input.get()));
    }
    if (result.empty()) throw std::runtime_error("unexpected end of PNM header");
    return result;
}

PnmImage read_pnm(const std::filesystem::path& path, bool normalize) {
    std::ifstream input(path, std::ios::binary);
    if (!input) throw std::runtime_error("cannot open input: " + path.string());
    const std::string magic = token(input);
    if (magic != "P6" && magic != "P5") throw std::runtime_error("CLI accepts binary PPM (P6) or PGM (P5)");
    PnmImage image;
    image.channels = magic == "P6" ? 3U : 1U;
    image.width = static_cast<uint32_t>(std::stoul(token(input)));
    image.height = static_cast<uint32_t>(std::stoul(token(input)));
    image.maximum = static_cast<uint32_t>(std::stoul(token(input)));
    if (image.width == 0U || image.height == 0U || image.maximum == 0U || image.maximum > 65535U) {
        throw std::runtime_error("invalid PNM dimensions or maximum");
    }
    input.get();
    const size_t sample_count = static_cast<size_t>(image.width) * image.height * image.channels;
    image.pixels.resize(sample_count);
    if (image.maximum < 256U) {
        std::vector<uint8_t> bytes(sample_count);
        input.read(reinterpret_cast<char*>(bytes.data()), static_cast<std::streamsize>(bytes.size()));
        if (static_cast<size_t>(input.gcount()) != bytes.size()) throw std::runtime_error("truncated PNM pixels");
        for (size_t i = 0; i < sample_count; ++i) {
            image.pixels[i] = normalize ? static_cast<float>(bytes[i]) / static_cast<float>(image.maximum)
                                        : static_cast<float>(bytes[i]);
        }
    } else {
        std::vector<uint8_t> bytes(sample_count * 2U);
        input.read(reinterpret_cast<char*>(bytes.data()), static_cast<std::streamsize>(bytes.size()));
        if (static_cast<size_t>(input.gcount()) != bytes.size()) throw std::runtime_error("truncated PNM pixels");
        for (size_t i = 0; i < sample_count; ++i) {
            const uint32_t sample = (static_cast<uint32_t>(bytes[i * 2U]) << 8U) | bytes[i * 2U + 1U];
            image.pixels[i] = normalize ? static_cast<float>(sample) / static_cast<float>(image.maximum)
                                        : static_cast<float>(sample);
        }
    }
    return image;
}

void write_ppm(const std::filesystem::path& path, const std::vector<float>& pixels, uint32_t width, uint32_t height) {
    std::ofstream output(path, std::ios::binary);
    if (!output) throw std::runtime_error("cannot open output: " + path.string());
    output << "P6\n" << width << ' ' << height << "\n255\n";
    std::vector<uint8_t> encoded(static_cast<size_t>(width) * height * 3U);
    for (size_t i = 0; i < encoded.size(); ++i) {
        encoded[i] = static_cast<uint8_t>(std::lround(std::clamp(pixels[i], 0.0F, 1.0F) * 255.0F));
    }
    output.write(reinterpret_cast<const char*>(encoded.data()), static_cast<std::streamsize>(encoded.size()));
}

void write_pfm(const std::filesystem::path& path, const float* data, uint32_t width, uint32_t height, uint32_t channels) {
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

struct StageWriter {
    std::filesystem::path directory;
};

void stage_callback(void* user, pte_stage_t stage, const float* data,
                    uint32_t width, uint32_t height, uint32_t channels) {
    auto& writer = *static_cast<StageWriter*>(user);
    const char* names[] = {"unknown", "01_scene_linear", "02_target_optics",
                           "03_target_sensor_dn", "04_target_isp_linear", "05_output_srgb"};
    write_pfm(writer.directory / (std::string(names[static_cast<int>(stage)]) + ".pfm"),
              data, width, height, channels);
}

struct Arguments {
    std::filesystem::path input;
    std::string input_format{"srgb"};
    std::filesystem::path host;
    std::filesystem::path toy;
    std::filesystem::path output;
    std::filesystem::path stages;
    uint64_t seed{1U};
    std::string backend{"cpu"};
};

Arguments parse_arguments(int argc, char** argv) {
    Arguments arguments;
    for (int i = 1; i < argc; ++i) {
        const std::string_view option(argv[i]);
        if (option == "--help") {
            std::cout << "Usage: phytoy_cli --input image.ppm|raw.pgm --input-format srgb|raw "
                         "--host host.ptp --toy toy.ptp --output result.ppm "
                         "[--backend cpu|vulkan|auto] [--seed N] [--dump-stages DIR]\n";
            std::exit(0);
        }
        if (i + 1 >= argc) throw std::runtime_error("missing value after " + std::string(option));
        const std::string value(argv[++i]);
        if (option == "--input") arguments.input = value;
        else if (option == "--input-format") arguments.input_format = value;
        else if (option == "--host") arguments.host = value;
        else if (option == "--toy") arguments.toy = value;
        else if (option == "--output") arguments.output = value;
        else if (option == "--seed") arguments.seed = std::stoull(value);
        else if (option == "--backend") arguments.backend = value;
        else if (option == "--dump-stages") arguments.stages = value;
        else throw std::runtime_error("unknown option: " + std::string(option));
    }
    if (arguments.input.empty() || arguments.host.empty() || arguments.toy.empty() || arguments.output.empty()) {
        throw std::runtime_error("--input, --host, --toy and --output are required");
    }
    if (arguments.input_format != "srgb" && arguments.input_format != "raw") {
        throw std::runtime_error("--input-format must be srgb or raw");
    }
    if (arguments.backend != "cpu" && arguments.backend != "vulkan" && arguments.backend != "auto") {
        throw std::runtime_error("--backend must be cpu, vulkan or auto");
    }
    return arguments;
}

}  // namespace

int main(int argc, char** argv) {
    try {
        const Arguments arguments = parse_arguments(argc, argv);
        const bool srgb = arguments.input_format == "srgb";
        const PnmImage source = read_pnm(arguments.input, srgb);
        if ((srgb && source.channels != 3U) || (!srgb && source.channels != 1U)) {
            throw std::runtime_error("input channel count does not match --input-format");
        }

        pte_engine_t* raw_engine = nullptr;
        const pte_status_t create_status = pte_engine_create(
            arguments.host.c_str(), arguments.toy.c_str(), &raw_engine);
        if (create_status != PTE_STATUS_OK) throw std::runtime_error(pte_last_error());
        struct EngineGuard {
            pte_engine_t* engine;
            ~EngineGuard() { pte_engine_destroy(engine); }
        } guard{raw_engine};
        const pte_backend_t backend = arguments.backend == "vulkan"
            ? PTE_BACKEND_VULKAN
            : (arguments.backend == "auto" ? PTE_BACKEND_AUTO : PTE_BACKEND_CPU);
        const pte_status_t backend_status = pte_engine_set_backend(raw_engine, backend);
        if (backend_status != PTE_STATUS_OK) {
            throw std::runtime_error(pte_engine_last_error(raw_engine));
        }

        pte_frame_f32_t frame{};
        frame.abi_version = PTE_ABI_VERSION;
        frame.format = srgb ? PTE_PIXEL_FORMAT_SRGB_F32 : PTE_PIXEL_FORMAT_RAW_BAYER_F32;
        frame.width = source.width;
        frame.height = source.height;
        frame.plane[0] = {source.pixels.data(), source.width * source.channels};

        StageWriter writer{arguments.stages};
        pte_render_options_t options{};
        options.abi_version = PTE_ABI_VERSION;
        options.seed = arguments.seed;
        if (!arguments.stages.empty()) {
            std::filesystem::create_directories(arguments.stages);
            options.stage_callback = stage_callback;
            options.stage_user_data = &writer;
        }

        std::vector<float> output(static_cast<size_t>(source.width) * source.height * 3U);
        pte_output_f32_t destination{output.data(), output.size(), source.width * 3U};
        const pte_status_t render_status = pte_engine_render(raw_engine, &frame, &options, &destination);
        if (render_status != PTE_STATUS_OK) throw std::runtime_error(pte_engine_last_error(raw_engine));
        std::filesystem::create_directories(arguments.output.parent_path().empty()
            ? std::filesystem::path(".") : arguments.output.parent_path());
        write_ppm(arguments.output, output, source.width, source.height);
        std::cout << arguments.output << '\n';
        return 0;
    } catch (const std::exception& exception) {
        std::cerr << "phytoy_cli: " << exception.what() << '\n';
        return 1;
    }
}
