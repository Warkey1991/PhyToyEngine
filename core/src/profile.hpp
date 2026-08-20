#pragma once

#include "json.hpp"

#include <array>
#include <cstdint>
#include <filesystem>
#include <string>
#include <utility>
#include <vector>

namespace phytoy {

enum class HostInputMode { Srgb, Yuv420, Raw };

struct HostRawProfile {
    std::string cfa;
    std::array<float, 4> black_level{};
    uint32_t black_level_count{};
    float white_level{};
    std::array<float, 3> white_balance{};
    std::array<float, 9> camera_to_xyz{};
    float radiometric_scale{1.0F};
};

struct HostProfile {
    std::string id;
    HostInputMode input_mode{HostInputMode::Srgb};
    std::array<float, 9> residual_matrix{};
    HostRawProfile raw;
};

struct Kernel {
    uint32_t width{};
    uint32_t height{};
    std::vector<float> values;
};

struct CoefficientMap {
    uint32_t width{};
    uint32_t height{};
    std::vector<float> values;
};

struct OpticsProfile {
    std::array<float, 4> distortion{};
    std::array<float, 3> ca_scale{};
    std::array<std::array<float, 2>, 3> vignette{};
    std::vector<Kernel> psf_bases;
    /* Flattened as basis * 3 + channel. */
    std::vector<CoefficientMap> psf_coefficients;
};

struct SensorProfile {
    std::string cfa;
    std::array<float, 9> scene_to_sensor{};
    float exposure_scale_e{};
    float full_well_e{};
    float conversion_gain_e_per_dn{};
    float black_level_dn{};
    float white_level_dn{};
    uint32_t adc_bits{};
    float read_noise_e{};
    float row_noise_e{};
    float column_noise_e{};
    float prnu_sigma{};
    float dsnu_e{};
    uint64_t profile_seed{};
    bool noise_enabled{true};
};

struct IspProfile {
    std::array<float, 3> white_balance{};
    std::array<float, 9> sensor_to_rec2020{};
    std::vector<std::pair<float, float>> tone_curve;
    float denoise_sigma{};
    float sharpen_amount{};
    float sharpen_radius{};
};

struct ToyProfile {
    std::string id;
    OpticsProfile optics;
    SensorProfile sensor;
    IspProfile isp;
};

HostProfile load_host_profile_package(const std::filesystem::path& path);
ToyProfile load_toy_profile_package(const std::filesystem::path& path);

}  // namespace phytoy

