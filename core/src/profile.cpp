#include "profile.hpp"

#include "sha256.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <fstream>
#include <iterator>
#include <limits>
#include <span>
#include <stdexcept>
#include <string_view>
#include <utility>

namespace phytoy {
namespace {

constexpr size_t PROFILE_HEADER_SIZE = 8U + 4U + 4U + 4U + 32U;
constexpr std::array<uint8_t, 8> PROFILE_MAGIC{'P', 'T', 'E', 'P', 'R', 'O', 'F', '1'};

uint32_t load_le32(const uint8_t* bytes) {
    return static_cast<uint32_t>(bytes[0]) |
           (static_cast<uint32_t>(bytes[1]) << 8U) |
           (static_cast<uint32_t>(bytes[2]) << 16U) |
           (static_cast<uint32_t>(bytes[3]) << 24U);
}

json::Value load_package(const std::filesystem::path& path, uint32_t expected_kind) {
    std::ifstream input(path, std::ios::binary);
    if (!input) throw std::runtime_error("cannot open profile package: " + path.string());
    const std::vector<uint8_t> bytes(
        (std::istreambuf_iterator<char>(input)), std::istreambuf_iterator<char>());
    if (bytes.size() < PROFILE_HEADER_SIZE) throw std::runtime_error("profile package is truncated");
    if (!std::equal(PROFILE_MAGIC.begin(), PROFILE_MAGIC.end(), bytes.begin())) {
        throw std::runtime_error("invalid profile package magic");
    }
    const uint32_t schema_version = load_le32(bytes.data() + 8U);
    const uint32_t kind = load_le32(bytes.data() + 12U);
    const uint32_t payload_size = load_le32(bytes.data() + 16U);
    if (schema_version != 1U) throw std::runtime_error("unsupported profile schema version");
    if (kind != expected_kind) throw std::runtime_error("profile package kind mismatch");
    if (bytes.size() != PROFILE_HEADER_SIZE + static_cast<size_t>(payload_size)) {
        throw std::runtime_error("profile package payload size mismatch");
    }
    const std::span<const uint8_t> payload(bytes.data() + PROFILE_HEADER_SIZE, payload_size);
    const auto actual_digest = sha256(payload);
    if (!std::equal(actual_digest.begin(), actual_digest.end(), bytes.begin() + 20U)) {
        throw std::runtime_error("profile package checksum mismatch");
    }
    const std::string payload_string(reinterpret_cast<const char*>(payload.data()), payload.size());
    return json::parse(payload_string);
}

float number(const json::Value& value, const char* field) {
    const double result = value.as_number();
    if (!std::isfinite(result) || result < -static_cast<double>(std::numeric_limits<float>::max()) ||
        result > static_cast<double>(std::numeric_limits<float>::max())) {
        throw std::runtime_error(std::string(field) + " must be a finite float");
    }
    return static_cast<float>(result);
}

float optional_number(const json::Value& object, const char* field, float fallback) {
    const json::Value* value = object.find(field);
    return value == nullptr ? fallback : number(*value, field);
}

std::string optional_string(const json::Value& object, const char* field, std::string fallback = {}) {
    const json::Value* value = object.find(field);
    return value == nullptr ? std::move(fallback) : value->as_string();
}

bool optional_bool(const json::Value& object, const char* field, bool fallback) {
    const json::Value* value = object.find(field);
    return value == nullptr ? fallback : value->as_bool();
}

uint64_t optional_uint64(const json::Value& object, const char* field, uint64_t fallback) {
    const json::Value* value = object.find(field);
    if (value == nullptr) return fallback;
    const double parsed = value->as_number();
    if (parsed < 0.0 || parsed > 9007199254740991.0 || std::floor(parsed) != parsed) {
        throw std::runtime_error(std::string(field) + " must be a nonnegative exact integer");
    }
    return static_cast<uint64_t>(parsed);
}

template <size_t N>
std::array<float, N> fixed_array(const json::Value& value, const char* field) {
    const auto& array = value.as_array();
    if (array.size() != N) throw std::runtime_error(std::string(field) + " has wrong length");
    std::array<float, N> result{};
    for (size_t index = 0; index < N; ++index) result[index] = number(array[index], field);
    return result;
}

std::array<float, 9> matrix3(const json::Value& value, const char* field) {
    const auto& rows = value.as_array();
    if (rows.size() != 3U) throw std::runtime_error(std::string(field) + " must have three rows");
    std::array<float, 9> result{};
    for (size_t row = 0; row < 3U; ++row) {
        const auto parsed = fixed_array<3>(rows[row], field);
        std::copy(parsed.begin(), parsed.end(), result.begin() + static_cast<std::ptrdiff_t>(row * 3U));
    }
    const float determinant =
        result[0] * (result[4] * result[8] - result[5] * result[7]) -
        result[1] * (result[3] * result[8] - result[5] * result[6]) +
        result[2] * (result[3] * result[7] - result[4] * result[6]);
    if (std::abs(determinant) < 1.0e-8F) throw std::runtime_error(std::string(field) + " is singular");
    return result;
}

Kernel parse_kernel(const json::Value& value) {
    const auto& rows = value.as_array();
    if (rows.empty()) throw std::runtime_error("PSF kernel cannot be empty");
    const size_t width = rows.front().as_array().size();
    if (width == 0U || width % 2U == 0U || rows.size() % 2U == 0U) {
        throw std::runtime_error("PSF kernel dimensions must be nonzero and odd");
    }
    Kernel result{static_cast<uint32_t>(width), static_cast<uint32_t>(rows.size()), {}};
    result.values.reserve(width * rows.size());
    float sum = 0.0F;
    for (const auto& row : rows) {
        const auto& columns = row.as_array();
        if (columns.size() != width) throw std::runtime_error("PSF kernel rows differ in length");
        for (const auto& entry : columns) {
            const float parsed = number(entry, "PSF kernel");
            if (parsed < 0.0F) throw std::runtime_error("PSF kernel cannot contain negative values");
            result.values.push_back(parsed);
            sum += parsed;
        }
    }
    if (sum <= 1.0e-12F) throw std::runtime_error("PSF kernel has zero energy");
    for (float& entry : result.values) entry /= sum;
    return result;
}

CoefficientMap parse_coefficient_map(const json::Value& value) {
    const auto& rows = value.as_array();
    if (rows.empty()) throw std::runtime_error("PSF coefficient map cannot be empty");
    const size_t width = rows.front().as_array().size();
    if (width == 0U) throw std::runtime_error("PSF coefficient map cannot be empty");
    CoefficientMap result{static_cast<uint32_t>(width), static_cast<uint32_t>(rows.size()), {}};
    result.values.reserve(width * rows.size());
    for (const auto& row : rows) {
        const auto& columns = row.as_array();
        if (columns.size() != width) throw std::runtime_error("PSF coefficient rows differ in length");
        for (const auto& entry : columns) {
            const float parsed = number(entry, "PSF coefficient");
            if (parsed < 0.0F) throw std::runtime_error("PSF coefficient cannot be negative");
            result.values.push_back(parsed);
        }
    }
    return result;
}

void require_profile_header(const json::Value& root, const char* kind) {
    if (root.at("kind").as_string() != kind) throw std::runtime_error("profile JSON kind mismatch");
    if (root.at("schema_version").as_number() != 1.0) throw std::runtime_error("profile JSON schema mismatch");
    if (root.at("id").as_string().empty()) throw std::runtime_error("profile id cannot be empty");
}

void require_positive(float value, const char* field) {
    if (!(value > 0.0F)) throw std::runtime_error(std::string(field) + " must be positive");
}

void validate_cfa(const std::string& cfa) {
    if (cfa != "RGGB" && cfa != "BGGR" && cfa != "GRBG" && cfa != "GBRG") {
        throw std::runtime_error("unsupported CFA pattern: " + cfa);
    }
}

bool is_semver_triplet(std::string_view version) {
    size_t component_start = 0U;
    for (uint32_t component = 0U; component < 3U; ++component) {
        const size_t separator = version.find('.', component_start);
        const size_t component_end = component == 2U ? version.size() : separator;
        if ((component < 2U && separator == std::string_view::npos) ||
            component_end == component_start) return false;
        for (size_t index = component_start; index < component_end; ++index) {
            if (version[index] < '0' || version[index] > '9') return false;
        }
        component_start = component_end + 1U;
    }
    return component_start == version.size() + 1U;
}

bool is_sha256_hex(std::string_view digest) {
    if (digest.size() != 64U) return false;
    return std::all_of(digest.begin(), digest.end(), [](char character) {
        return (character >= '0' && character <= '9') ||
               (character >= 'a' && character <= 'f');
    });
}

}  // namespace

HostProfile load_host_profile_package(const std::filesystem::path& path) {
    const json::Value root = load_package(path, 1U);
    require_profile_header(root, "host");
    HostProfile result;
    result.id = root.at("id").as_string();

    const json::Value& input = root.at("input");
    const std::string& mode = input.at("mode").as_string();
    if (mode == "srgb") result.input_mode = HostInputMode::Srgb;
    else if (mode == "yuv420") result.input_mode = HostInputMode::Yuv420;
    else if (mode == "raw") result.input_mode = HostInputMode::Raw;
    else throw std::runtime_error("unsupported host input mode: " + mode);

    result.residual_matrix = matrix3(root.at("normalization").at("rgb_residual_matrix"), "rgb_residual_matrix");
    if (result.input_mode == HostInputMode::Raw) {
        const json::Value& raw = root.at("raw");
        result.raw.cfa = raw.at("cfa").as_string();
        validate_cfa(result.raw.cfa);
        const auto& black = raw.at("black_level").as_array();
        if (black.size() != 1U && black.size() != 4U) throw std::runtime_error("RAW black level must have one or four values");
        result.raw.black_level_count = static_cast<uint32_t>(black.size());
        for (size_t i = 0; i < black.size(); ++i) result.raw.black_level[i] = number(black[i], "black_level");
        result.raw.white_level = number(raw.at("white_level"), "white_level");
        result.raw.white_balance = fixed_array<3>(raw.at("white_balance"), "white_balance");
        result.raw.camera_to_xyz = matrix3(raw.at("camera_to_xyz_d65"), "camera_to_xyz_d65");
        result.raw.radiometric_scale = optional_number(raw, "radiometric_scale", 1.0F);
        require_positive(result.raw.white_level, "white_level");
    }
    return result;
}

ToyProfile load_toy_profile_package(const std::filesystem::path& path) {
    const json::Value root = load_package(path, 2U);
    require_profile_header(root, "toy");
    ToyProfile result;
    result.id = root.at("id").as_string();
    result.version = root.at("version").as_string();
    if (!is_semver_triplet(result.version)) {
        throw std::runtime_error("toy profile version must use MAJOR.MINOR.PATCH");
    }
    if (const json::Value* provenance = root.find("provenance")) {
        result.provenance.profile_type = optional_string(*provenance, "profile_type", "unspecified");
        result.provenance.calibration_status = optional_string(*provenance, "calibration_status", "unspecified");
        result.provenance.target_name = optional_string(*provenance, "target_name");
        result.provenance.source = optional_string(*provenance, "source");
        result.provenance.dataset_id = optional_string(*provenance, "dataset_id");
        result.provenance.dataset_sha256 = optional_string(*provenance, "dataset_sha256");
        result.provenance.license = optional_string(*provenance, "license");
        if (result.provenance.profile_type != "designed" &&
            result.provenance.profile_type != "measured") {
            throw std::runtime_error("toy profile_type must be designed or measured");
        }
        if (result.provenance.calibration_status.empty() ||
            result.provenance.target_name.empty() || result.provenance.source.empty()) {
            throw std::runtime_error("toy profile provenance is incomplete");
        }
        if (result.provenance.profile_type == "measured" &&
            (result.provenance.dataset_id.empty() ||
             !is_sha256_hex(result.provenance.dataset_sha256))) {
            throw std::runtime_error("measured toy profile requires dataset id and SHA-256");
        }
    }

    const json::Value& optics = root.at("optics");
    result.optics.distortion = fixed_array<4>(optics.at("distortion"), "distortion");
    result.optics.ca_scale = fixed_array<3>(optics.at("ca_scale"), "ca_scale");
    const auto& vignette = optics.at("vignette").as_array();
    if (vignette.size() == 2U && vignette.front().is_number()) {
        const auto pair = fixed_array<2>(optics.at("vignette"), "vignette");
        result.optics.vignette = {pair, pair, pair};
    } else if (vignette.size() == 3U) {
        for (size_t channel = 0; channel < 3U; ++channel) {
            result.optics.vignette[channel] = fixed_array<2>(vignette[channel], "vignette");
        }
    } else {
        throw std::runtime_error("vignette must be one pair or three channel pairs");
    }

    const json::Value& psf = optics.at("psf");
    for (const auto& basis : psf.at("bases").as_array()) result.optics.psf_bases.push_back(parse_kernel(basis));
    if (result.optics.psf_bases.size() > 8U) {
        throw std::runtime_error("PSF supports at most 8 bases");
    }
    const auto& coefficient_bases = psf.at("coefficients").as_array();
    if (coefficient_bases.size() != result.optics.psf_bases.size()) {
        throw std::runtime_error("PSF basis and coefficient counts differ");
    }
    for (const auto& basis : coefficient_bases) {
        const auto& channels = basis.as_array();
        if (channels.size() != 3U) throw std::runtime_error("each PSF basis needs three coefficient maps");
        for (const auto& channel : channels) result.optics.psf_coefficients.push_back(parse_coefficient_map(channel));
    }

    const json::Value& sensor = root.at("sensor");
    result.sensor.cfa = sensor.at("cfa").as_string();
    validate_cfa(result.sensor.cfa);
    result.sensor.scene_to_sensor = matrix3(sensor.at("scene_to_sensor"), "scene_to_sensor");
    result.sensor.exposure_scale_e = number(sensor.at("exposure_scale_e"), "exposure_scale_e");
    result.sensor.full_well_e = number(sensor.at("full_well_e"), "full_well_e");
    result.sensor.conversion_gain_e_per_dn = number(sensor.at("conversion_gain_e_per_dn"), "conversion_gain_e_per_dn");
    result.sensor.black_level_dn = number(sensor.at("black_level_dn"), "black_level_dn");
    result.sensor.white_level_dn = number(sensor.at("white_level_dn"), "white_level_dn");
    result.sensor.adc_bits = static_cast<uint32_t>(optional_uint64(sensor, "adc_bits", 10U));
    result.sensor.read_noise_e = optional_number(sensor, "read_noise_e", 0.0F);
    result.sensor.row_noise_e = optional_number(sensor, "row_noise_e", 0.0F);
    result.sensor.column_noise_e = optional_number(sensor, "column_noise_e", 0.0F);
    result.sensor.prnu_sigma = optional_number(sensor, "prnu_sigma", 0.0F);
    result.sensor.dsnu_e = optional_number(sensor, "dsnu_e", 0.0F);
    result.sensor.profile_seed = optional_uint64(sensor, "profile_seed", 1U);
    result.sensor.noise_enabled = optional_bool(sensor, "noise_enabled", true);
    require_positive(result.sensor.exposure_scale_e, "exposure_scale_e");
    require_positive(result.sensor.full_well_e, "full_well_e");
    require_positive(result.sensor.conversion_gain_e_per_dn, "conversion_gain_e_per_dn");
    if (result.sensor.white_level_dn <= result.sensor.black_level_dn) {
        throw std::runtime_error("white_level_dn must exceed black_level_dn");
    }

    const json::Value& isp = root.at("isp");
    result.isp.white_balance = fixed_array<3>(isp.at("white_balance"), "white_balance");
    result.isp.sensor_to_rec2020 = matrix3(isp.at("sensor_to_rec2020"), "sensor_to_rec2020");
    float previous_x = -std::numeric_limits<float>::infinity();
    float previous_y = -std::numeric_limits<float>::infinity();
    for (const auto& point_value : isp.at("tone_curve").as_array()) {
        const auto point = fixed_array<2>(point_value, "tone_curve");
        if (point[0] <= previous_x || point[1] < previous_y) {
            throw std::runtime_error("tone curve must have increasing x and nondecreasing y");
        }
        result.isp.tone_curve.emplace_back(point[0], point[1]);
        previous_x = point[0];
        previous_y = point[1];
    }
    if (result.isp.tone_curve.size() < 2U) throw std::runtime_error("tone curve needs at least two points");
    result.isp.denoise_sigma = optional_number(isp, "denoise_sigma", 0.0F);
    result.isp.sharpen_amount = optional_number(isp, "sharpen_amount", 0.0F);
    result.isp.sharpen_radius = optional_number(isp, "sharpen_radius", 1.0F);
    return result;
}

}  // namespace phytoy
