#include "cpu_backend.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <numbers>
#include <stdexcept>
#include <vector>

namespace phytoy {
namespace {

constexpr std::array<float, 9> SRGB_TO_REC2020{
    0.62750375F, 0.32927542F, 0.04330266F,
    0.06910822F, 0.91951916F, 0.01135960F,
    0.01639406F, 0.08801125F, 0.89538035F,
};
constexpr std::array<float, 9> REC2020_TO_SRGB{
    1.66024000F, -0.58754781F, -0.07284033F,
    -0.12455047F, 1.13292614F, -0.00834956F,
    -0.01815076F, -0.10060300F, 1.11899818F,
};
constexpr std::array<float, 9> XYZ_D65_TO_REC2020{
    1.71665119F, -0.35567078F, -0.25336628F,
    -0.66668435F, 1.61648124F, 0.01576855F,
    0.01763986F, -0.04277061F, 0.94210312F,
};
constexpr std::array<float, 9> BILINEAR_KERNEL{
    1.0F, 2.0F, 1.0F,
    2.0F, 4.0F, 2.0F,
    1.0F, 2.0F, 1.0F,
};

int mirror_index(int coordinate, int size) {
    if (size <= 1) return 0;
    while (coordinate < 0 || coordinate >= size) {
        if (coordinate < 0) coordinate = -coordinate;
        if (coordinate >= size) coordinate = 2 * size - 2 - coordinate;
    }
    return coordinate;
}

int reflect_index(int coordinate, int size) {
    if (size <= 1) return 0;
    while (coordinate < 0 || coordinate >= size) {
        if (coordinate < 0) coordinate = -coordinate - 1;
        if (coordinate >= size) coordinate = 2 * size - 1 - coordinate;
    }
    return coordinate;
}

std::array<float, 3> multiply(
    const std::array<float, 9>& matrix,
    const std::array<float, 3>& value) {
    return {
        matrix[0] * value[0] + matrix[1] * value[1] + matrix[2] * value[2],
        matrix[3] * value[0] + matrix[4] * value[1] + matrix[5] * value[2],
        matrix[6] * value[0] + matrix[7] * value[1] + matrix[8] * value[2],
    };
}

Image apply_matrix(const Image& input, const std::array<float, 9>& matrix) {
    if (input.channels != 3U) throw std::runtime_error("matrix input must have three channels");
    Image output(input.width, input.height, 3U);
    for (uint32_t y = 0; y < input.height; ++y) {
        for (uint32_t x = 0; x < input.width; ++x) {
            const auto transformed = multiply(matrix, {input.at(x, y, 0), input.at(x, y, 1), input.at(x, y, 2)});
            for (uint32_t c = 0; c < 3U; ++c) output.at(x, y, c) = transformed[c];
        }
    }
    return output;
}

float srgb_decode(float encoded) {
    encoded = clamp01(encoded);
    return encoded <= 0.04045F
        ? encoded / 12.92F
        : std::pow((encoded + 0.055F) / 1.055F, 2.4F);
}

float srgb_encode(float linear) {
    linear = std::max(linear, 0.0F);
    return linear <= 0.0031308F
        ? linear * 12.92F
        : 1.055F * std::pow(linear, 1.0F / 2.4F) - 0.055F;
}

void emit_stage(
    const Image& image,
    pte_stage_t stage,
    pte_stage_callback_f32 callback,
    void* user_data) {
    if (callback != nullptr) {
        callback(user_data, stage, image.pixels.data(), image.width, image.height, image.channels);
    }
}

int cfa_channel(const std::string& pattern, uint32_t x, uint32_t y) {
    const char entry = pattern[(y % 2U) * 2U + (x % 2U)];
    if (entry == 'R') return 0;
    if (entry == 'G') return 1;
    return 2;
}

Image demosaic_bilinear(const Image& raw, const std::string& cfa) {
    if (raw.channels != 1U) throw std::runtime_error("demosaic input must have one channel");
    Image output(raw.width, raw.height, 3U);
    for (uint32_t y = 0; y < raw.height; ++y) {
        for (uint32_t x = 0; x < raw.width; ++x) {
            for (int channel = 0; channel < 3; ++channel) {
                float numerator = 0.0F;
                float denominator = 0.0F;
                for (int ky = -1; ky <= 1; ++ky) {
                    for (int kx = -1; kx <= 1; ++kx) {
                        const int sx = mirror_index(static_cast<int>(x) + kx, static_cast<int>(raw.width));
                        const int sy = mirror_index(static_cast<int>(y) + ky, static_cast<int>(raw.height));
                        if (cfa_channel(cfa, static_cast<uint32_t>(sx), static_cast<uint32_t>(sy)) != channel) continue;
                        const float weight = BILINEAR_KERNEL[static_cast<size_t>(ky + 1) * 3U + static_cast<size_t>(kx + 1)];
                        numerator += raw.at(static_cast<uint32_t>(sx), static_cast<uint32_t>(sy)) * weight;
                        denominator += weight;
                    }
                }
                output.at(x, y, static_cast<uint32_t>(channel)) = numerator / std::max(denominator, 1.0e-12F);
            }
        }
    }
    return output;
}

Image copy_srgb(const pte_frame_f32_t& frame) {
    Image encoded(frame.width, frame.height, 3U);
    for (uint32_t y = 0; y < frame.height; ++y) {
        const float* row = frame.plane[0].data + static_cast<size_t>(y) * frame.plane[0].row_stride_floats;
        std::copy_n(row, static_cast<size_t>(frame.width) * 3U,
                    encoded.pixels.begin() + static_cast<std::ptrdiff_t>(static_cast<size_t>(y) * frame.width * 3U));
    }
    for (float& value : encoded.pixels) value = srgb_decode(value);
    return encoded;
}

Image normalize_srgb(const pte_frame_f32_t& frame, const HostProfile& host) {
    return apply_matrix(apply_matrix(copy_srgb(frame), SRGB_TO_REC2020), host.residual_matrix);
}

Image normalize_yuv(const pte_frame_f32_t& frame, const HostProfile& host) {
    Image encoded(frame.width, frame.height, 3U);
    const uint32_t uv_width = (frame.width + 1U) / 2U;
    for (uint32_t y = 0; y < frame.height; ++y) {
        const float* y_row = frame.plane[0].data + static_cast<size_t>(y) * frame.plane[0].row_stride_floats;
        const float* uv_row = frame.plane[1].data + static_cast<size_t>(y / 2U) * frame.plane[1].row_stride_floats;
        for (uint32_t x = 0; x < frame.width; ++x) {
            const uint32_t uv_x = std::min(x / 2U, uv_width - 1U);
            const float source_y = y_row[x];
            const float source_u = uv_row[static_cast<size_t>(uv_x) * 2U];
            const float source_v = uv_row[static_cast<size_t>(uv_x) * 2U + 1U];
            float yy;
            float cb;
            float cr;
            if (frame.yuv_full_range != 0U) {
                yy = source_y;
                cb = source_u - 0.5F;
                cr = source_v - 0.5F;
            } else {
                yy = (source_y - 16.0F / 255.0F) * (255.0F / 219.0F);
                cb = (source_u - 128.0F / 255.0F) * (255.0F / 224.0F);
                cr = (source_v - 128.0F / 255.0F) * (255.0F / 224.0F);
            }
            encoded.at(x, y, 0) = srgb_decode(yy + 1.5748F * cr);
            encoded.at(x, y, 1) = srgb_decode(yy - 0.187324F * cb - 0.468124F * cr);
            encoded.at(x, y, 2) = srgb_decode(yy + 1.8556F * cb);
        }
    }
    return apply_matrix(apply_matrix(encoded, SRGB_TO_REC2020), host.residual_matrix);
}

Image normalize_raw(const pte_frame_f32_t& frame, const HostProfile& host) {
    Image normalized(frame.width, frame.height, 1U);
    for (uint32_t y = 0; y < frame.height; ++y) {
        const float* row = frame.plane[0].data + static_cast<size_t>(y) * frame.plane[0].row_stride_floats;
        for (uint32_t x = 0; x < frame.width; ++x) {
            const uint32_t black_index = host.raw.black_level_count == 1U ? 0U : (y % 2U) * 2U + (x % 2U);
            const float black = host.raw.black_level[black_index];
            normalized.at(x, y) = std::max((row[x] - black) / std::max(host.raw.white_level - black, 1.0e-12F), 0.0F);
        }
    }
    Image rgb = demosaic_bilinear(normalized, host.raw.cfa);
    for (uint32_t y = 0; y < rgb.height; ++y) {
        for (uint32_t x = 0; x < rgb.width; ++x) {
            for (uint32_t c = 0; c < 3U; ++c) rgb.at(x, y, c) *= host.raw.white_balance[c];
        }
    }
    Image scene = apply_matrix(apply_matrix(rgb, host.raw.camera_to_xyz), XYZ_D65_TO_REC2020);
    for (float& value : scene.pixels) value *= host.raw.radiometric_scale;
    return apply_matrix(scene, host.residual_matrix);
}

float sample_bilinear_reflect(const Image& image, float x, float y, uint32_t channel) {
    const int x0 = static_cast<int>(std::floor(x));
    const int y0 = static_cast<int>(std::floor(y));
    const float tx = x - static_cast<float>(x0);
    const float ty = y - static_cast<float>(y0);
    const int xa = reflect_index(x0, static_cast<int>(image.width));
    const int xb = reflect_index(x0 + 1, static_cast<int>(image.width));
    const int ya = reflect_index(y0, static_cast<int>(image.height));
    const int yb = reflect_index(y0 + 1, static_cast<int>(image.height));
    const float top = image.at(static_cast<uint32_t>(xa), static_cast<uint32_t>(ya), channel) * (1.0F - tx) +
                      image.at(static_cast<uint32_t>(xb), static_cast<uint32_t>(ya), channel) * tx;
    const float bottom = image.at(static_cast<uint32_t>(xa), static_cast<uint32_t>(yb), channel) * (1.0F - tx) +
                         image.at(static_cast<uint32_t>(xb), static_cast<uint32_t>(yb), channel) * tx;
    return top * (1.0F - ty) + bottom * ty;
}

float distortion_safe_scale(const OpticsProfile& optics) {
    const float k1 = optics.distortion[0];
    const float k2 = optics.distortion[1];
    const auto radial_at = [k1, k2](float radius2) {
        return std::abs(1.0F + k1 * radius2 + k2 * radius2 * radius2);
    };
    float maximum_radial = std::max({radial_at(0.0F), radial_at(1.0F), radial_at(2.0F)});
    if (std::abs(k2) > std::numeric_limits<float>::epsilon()) {
        const float stationary_radius2 = -k1 / (2.0F * k2);
        if (stationary_radius2 > 0.0F && stationary_radius2 < 2.0F) {
            maximum_radial = std::max(maximum_radial, radial_at(stationary_radius2));
        }
    }
    const float tangential_bound = 4.0F * (
        std::abs(optics.distortion[2]) + std::abs(optics.distortion[3]));
    const float maximum_ca = *std::max_element(optics.ca_scale.begin(), optics.ca_scale.end());
    const float boundary_scale = maximum_ca * (maximum_radial + tangential_bound);
    return 1.0F / std::max(boundary_scale, 1.0F);
}

Image warp_optics(const Image& source, const OpticsProfile& optics) {
    Image output(source.width, source.height, 3U);
    const float width = static_cast<float>(source.width);
    const float height = static_cast<float>(source.height);
    const float safe_scale = distortion_safe_scale(optics);
    for (uint32_t y = 0; y < source.height; ++y) {
        for (uint32_t x = 0; x < source.width; ++x) {
            const float xn = (((static_cast<float>(x) + 0.5F) / width) * 2.0F - 1.0F) * safe_scale;
            const float yn = (((static_cast<float>(y) + 0.5F) / height) * 2.0F - 1.0F) * safe_scale;
            const float r2 = xn * xn + yn * yn;
            const float radial = 1.0F + optics.distortion[0] * r2 + optics.distortion[1] * r2 * r2;
            const float xb = xn * radial + 2.0F * optics.distortion[2] * xn * yn +
                             optics.distortion[3] * (r2 + 2.0F * xn * xn);
            const float yb = yn * radial + optics.distortion[2] * (r2 + 2.0F * yn * yn) +
                             2.0F * optics.distortion[3] * xn * yn;
            for (uint32_t c = 0; c < 3U; ++c) {
                const float sx = ((xb * optics.ca_scale[c] + 1.0F) * 0.5F * width) - 0.5F;
                const float sy = ((yb * optics.ca_scale[c] + 1.0F) * 0.5F * height) - 0.5F;
                output.at(x, y, c) = sample_bilinear_reflect(source, sx, sy, c);
            }
        }
    }
    return output;
}

float sample_map(const CoefficientMap& map, uint32_t x, uint32_t y, uint32_t width, uint32_t height) {
    const float gx = width <= 1U ? 0.0F : static_cast<float>(x) * static_cast<float>(map.width - 1U) / static_cast<float>(width - 1U);
    const float gy = height <= 1U ? 0.0F : static_cast<float>(y) * static_cast<float>(map.height - 1U) / static_cast<float>(height - 1U);
    const uint32_t x0 = static_cast<uint32_t>(std::floor(gx));
    const uint32_t y0 = static_cast<uint32_t>(std::floor(gy));
    const uint32_t x1 = std::min(x0 + 1U, map.width - 1U);
    const uint32_t y1 = std::min(y0 + 1U, map.height - 1U);
    const float tx = gx - static_cast<float>(x0);
    const float ty = gy - static_cast<float>(y0);
    const auto at = [&map](uint32_t px, uint32_t py) { return map.values[static_cast<size_t>(py) * map.width + px]; };
    return (at(x0, y0) * (1.0F - tx) + at(x1, y0) * tx) * (1.0F - ty) +
           (at(x0, y1) * (1.0F - tx) + at(x1, y1) * tx) * ty;
}

float convolve_kernel(const Image& source, uint32_t x, uint32_t y, uint32_t channel, const Kernel& kernel) {
    float sum = 0.0F;
    const int radius_x = static_cast<int>(kernel.width / 2U);
    const int radius_y = static_cast<int>(kernel.height / 2U);
    for (uint32_t ky = 0; ky < kernel.height; ++ky) {
        for (uint32_t kx = 0; kx < kernel.width; ++kx) {
            /* scipy.signal.convolve2d(boundary="symm") repeats edge samples. */
            const int sx = reflect_index(static_cast<int>(x) + static_cast<int>(kx) - radius_x, static_cast<int>(source.width));
            const int sy = reflect_index(static_cast<int>(y) + static_cast<int>(ky) - radius_y, static_cast<int>(source.height));
            sum += source.at(static_cast<uint32_t>(sx), static_cast<uint32_t>(sy), channel) *
                   kernel.values[static_cast<size_t>(ky) * kernel.width + kx];
        }
    }
    return sum;
}

Image apply_spatial_psf(const Image& source, const OpticsProfile& optics) {
    Image output(source.width, source.height, 3U);
    for (uint32_t y = 0; y < source.height; ++y) {
        for (uint32_t x = 0; x < source.width; ++x) {
            for (uint32_t c = 0; c < 3U; ++c) {
                float weighted = 0.0F;
                float weight_sum = 0.0F;
                for (size_t basis = 0; basis < optics.psf_bases.size(); ++basis) {
                    const float weight = sample_map(optics.psf_coefficients[basis * 3U + c], x, y, source.width, source.height);
                    weighted += convolve_kernel(source, x, y, c, optics.psf_bases[basis]) * weight;
                    weight_sum += weight;
                }
                output.at(x, y, c) = weighted / std::max(weight_sum, 1.0e-12F);
            }
        }
    }
    return output;
}

Image apply_optics(const Image& scene, const OpticsProfile& optics) {
    Image output = apply_spatial_psf(warp_optics(scene, optics), optics);
    const float width = static_cast<float>(scene.width);
    const float height = static_cast<float>(scene.height);
    for (uint32_t y = 0; y < output.height; ++y) {
        for (uint32_t x = 0; x < output.width; ++x) {
            const float xn = ((static_cast<float>(x) + 0.5F) / width) * 2.0F - 1.0F;
            const float yn = ((static_cast<float>(y) + 0.5F) / height) * 2.0F - 1.0F;
            const float r2 = xn * xn + yn * yn;
            for (uint32_t c = 0; c < 3U; ++c) {
                const float gain = std::max(1.0F - optics.vignette[c][0] * r2 - optics.vignette[c][1] * r2 * r2, 0.0F);
                output.at(x, y, c) *= gain;
            }
        }
    }
    return output;
}

uint64_t mix64(uint64_t value) {
    value += 0x9e3779b97f4a7c15ULL;
    value = (value ^ (value >> 30U)) * 0xbf58476d1ce4e5b9ULL;
    value = (value ^ (value >> 27U)) * 0x94d049bb133111ebULL;
    return value ^ (value >> 31U);
}

double uniform01(uint64_t seed, uint64_t counter) {
    const uint64_t bits = mix64(seed ^ (counter * 0xd2b74407b1ce6e93ULL));
    return static_cast<double>((bits >> 11U) + 1U) * (1.0 / 9007199254740993.0);
}

float normal_sample(uint64_t seed, uint64_t counter) {
    const double u1 = uniform01(seed, counter * 2U);
    const double u2 = uniform01(seed, counter * 2U + 1U);
    return static_cast<float>(std::sqrt(-2.0 * std::log(u1)) * std::cos(2.0 * std::numbers::pi * u2));
}

float poisson_sample(float lambda, uint64_t seed, uint64_t counter) {
    if (lambda <= 0.0F) return 0.0F;
    if (lambda < 48.0F) {
        const double limit = std::exp(-static_cast<double>(lambda));
        double product = 1.0;
        uint32_t count = 0;
        do {
            ++count;
            product *= uniform01(seed ^ counter, count);
        } while (product > limit);
        return static_cast<float>(count - 1U);
    }
    const float approximation = lambda + std::sqrt(lambda) * normal_sample(seed, counter);
    return std::max(std::floor(approximation + 0.5F), 0.0F);
}

Image simulate_sensor(const Image& optical, const SensorProfile& sensor, uint64_t seed) {
    Image sensor_rgb = apply_matrix(optical, sensor.scene_to_sensor);
    const size_t count = sensor_rgb.pixels.size();
    for (size_t index = 0; index < count; ++index) {
        float expected = std::max(sensor_rgb.pixels[index], 0.0F) * sensor.exposure_scale_e;
        if (sensor.noise_enabled && sensor.prnu_sigma > 0.0F) {
            expected *= std::max(1.0F + sensor.prnu_sigma * normal_sample(sensor.profile_seed, index + 11U), 0.0F);
        }
        sensor_rgb.pixels[index] = sensor.noise_enabled ? poisson_sample(expected, seed, index + 101U) : expected;
    }

    if (sensor.noise_enabled) {
        for (uint32_t y = 0; y < sensor_rgb.height; ++y) {
            const float row_noise = sensor.row_noise_e * normal_sample(seed ^ 0x524f57ULL, y + 1U);
            for (uint32_t x = 0; x < sensor_rgb.width; ++x) {
                const float column_noise = sensor.column_noise_e * normal_sample(sensor.profile_seed ^ 0x434f4cULL, x + 1U);
                for (uint32_t c = 0; c < 3U; ++c) {
                    const size_t index = (static_cast<size_t>(y) * sensor_rgb.width + x) * 3U + c;
                    const float read_noise = sensor.read_noise_e * normal_sample(seed ^ 0x52454144ULL, index + 1U);
                    const float dsnu = sensor.dsnu_e * normal_sample(sensor.profile_seed ^ 0x44534e55ULL, index + 1U);
                    sensor_rgb.pixels[index] += row_noise + column_noise + read_noise + dsnu;
                }
            }
        }
    }

    Image dn(optical.width, optical.height, 1U);
    for (uint32_t y = 0; y < optical.height; ++y) {
        for (uint32_t x = 0; x < optical.width; ++x) {
            const uint32_t channel = static_cast<uint32_t>(cfa_channel(sensor.cfa, x, y));
            const float electrons = std::clamp(sensor_rgb.at(x, y, channel), 0.0F, sensor.full_well_e);
            const float adc = electrons / sensor.conversion_gain_e_per_dn + sensor.black_level_dn;
            // Nonnegative ADC values use half-up, including exact .5 ties.
            // Split the fractional part: adc + .5 can round a value immediately
            // below a boundary onto it, or change large integral float values.
            const float integral = std::floor(adc);
            const float value = integral + (adc - integral >= 0.5F ? 1.0F : 0.0F);
            dn.at(x, y) = std::clamp(value, 0.0F, sensor.white_level_dn);
        }
    }
    return dn;
}

std::vector<float> gaussian_kernel(float sigma) {
    if (sigma <= 0.0F) return {1.0F};
    if (!std::isfinite(sigma) || sigma > static_cast<float>(std::numeric_limits<int>::max() / 8)) {
        throw std::invalid_argument("CPU Gaussian sigma exceeds safe kernel indexing");
    }
    // scipy gaussian_filter uses round(truncate * sigma), not ceil. Matching
    // the reference also makes very small sigma an exact identity filter.
    const int radius = static_cast<int>(std::floor(4.0F * sigma + 0.5F));
    std::vector<float> kernel(static_cast<size_t>(radius * 2 + 1));
    float sum = 0.0F;
    for (int i = -radius; i <= radius; ++i) {
        const float value = std::exp(-0.5F * static_cast<float>(i * i) / (sigma * sigma));
        kernel[static_cast<size_t>(i + radius)] = value;
        sum += value;
    }
    for (float& value : kernel) value /= sum;
    return kernel;
}

Image gaussian_blur(const Image& source, float sigma) {
    const std::vector<float> kernel = gaussian_kernel(sigma);
    if (kernel.size() == 1U) return source;
    const int radius = static_cast<int>(kernel.size() / 2U);
    Image horizontal(source.width, source.height, source.channels);
    Image output(source.width, source.height, source.channels);
    for (uint32_t y = 0; y < source.height; ++y) {
        for (uint32_t x = 0; x < source.width; ++x) {
            for (uint32_t c = 0; c < source.channels; ++c) {
                float sum = 0.0F;
                for (int k = -radius; k <= radius; ++k) {
                    const int sx = mirror_index(static_cast<int>(x) + k, static_cast<int>(source.width));
                    sum += source.at(static_cast<uint32_t>(sx), y, c) * kernel[static_cast<size_t>(k + radius)];
                }
                horizontal.at(x, y, c) = sum;
            }
        }
    }
    for (uint32_t y = 0; y < source.height; ++y) {
        for (uint32_t x = 0; x < source.width; ++x) {
            for (uint32_t c = 0; c < source.channels; ++c) {
                float sum = 0.0F;
                for (int k = -radius; k <= radius; ++k) {
                    const int sy = mirror_index(static_cast<int>(y) + k, static_cast<int>(source.height));
                    sum += horizontal.at(x, static_cast<uint32_t>(sy), c) * kernel[static_cast<size_t>(k + radius)];
                }
                output.at(x, y, c) = sum;
            }
        }
    }
    return output;
}

float tone_value(float value, const std::vector<std::pair<float, float>>& curve) {
    if (value <= curve.front().first) return curve.front().second;
    if (value >= curve.back().first) return curve.back().second;
    const auto upper = std::upper_bound(curve.begin(), curve.end(), value,
        [](float needle, const std::pair<float, float>& point) { return needle < point.first; });
    const auto lower = upper - 1;
    const float t = (value - lower->first) / (upper->first - lower->first);
    return lower->second * (1.0F - t) + upper->second * t;
}

std::pair<Image, Image> run_isp(const Image& sensor_dn, const SensorProfile& sensor, const IspProfile& isp) {
    Image normalized(sensor_dn.width, sensor_dn.height, 1U);
    const float range = std::max(sensor.white_level_dn - sensor.black_level_dn, 1.0e-12F);
    for (size_t i = 0; i < sensor_dn.pixels.size(); ++i) {
        normalized.pixels[i] = std::max((sensor_dn.pixels[i] - sensor.black_level_dn) / range, 0.0F);
    }
    Image scene = demosaic_bilinear(normalized, sensor.cfa);
    for (uint32_t y = 0; y < scene.height; ++y) {
        for (uint32_t x = 0; x < scene.width; ++x) {
            for (uint32_t c = 0; c < 3U; ++c) scene.at(x, y, c) *= isp.white_balance[c];
        }
    }
    scene = apply_matrix(scene, isp.sensor_to_rec2020);
    if (isp.denoise_sigma > 0.0F) scene = gaussian_blur(scene, isp.denoise_sigma);
    if (isp.sharpen_amount != 0.0F) {
        const Image low_pass = gaussian_blur(scene, isp.sharpen_radius);
        for (size_t i = 0; i < scene.pixels.size(); ++i) {
            scene.pixels[i] += isp.sharpen_amount * (scene.pixels[i] - low_pass.pixels[i]);
        }
    }
    if (isp.monochrome) {
        for (size_t i = 0; i < scene.pixels.size(); i += 3U) {
            const float y = 0.2627F * scene.pixels[i] + 0.6780F * scene.pixels[i + 1U] + 0.0593F * scene.pixels[i + 2U];
            scene.pixels[i] = scene.pixels[i + 1U] = scene.pixels[i + 2U] = y;
        }
    }
    Image toned(scene.width, scene.height, 3U);
    for (size_t i = 0; i < toned.pixels.size(); ++i) toned.pixels[i] = tone_value(std::max(scene.pixels[i], 0.0F), isp.tone_curve);
    Image encoded = isp.monochrome ? toned : apply_matrix(toned, REC2020_TO_SRGB);
    for (float& value : encoded.pixels) value = clamp01(srgb_encode(value));
    return {std::move(toned), std::move(encoded)};
}

}  // namespace

Image normalize_cpu(const pte_frame_f32_t& frame, const HostProfile& host) {
    if (frame.format == PTE_PIXEL_FORMAT_SRGB_F32) return normalize_srgb(frame, host);
    if (frame.format == PTE_PIXEL_FORMAT_YUV420_BT709_F32) return normalize_yuv(frame, host);
    if (frame.format == PTE_PIXEL_FORMAT_RAW_BAYER_F32) return normalize_raw(frame, host);
    throw std::runtime_error("unsupported input pixel format");
}

Image render_cpu(
    const pte_frame_f32_t& frame,
    const HostProfile& host,
    const ToyProfile& toy,
    uint64_t seed,
    pte_stage_callback_f32 stage_callback,
    void* stage_user_data) {
    Image scene = normalize_cpu(frame, host);
    emit_stage(scene, PTE_STAGE_SCENE_LINEAR, stage_callback, stage_user_data);

    const ToyProfile resolved = profile_for_resolution(toy, frame.width, frame.height);
    Image optical = apply_optics(scene, resolved.optics);
    emit_stage(optical, PTE_STAGE_TARGET_OPTICS, stage_callback, stage_user_data);
    Image sensor_dn = simulate_sensor(optical, resolved.sensor, seed);
    emit_stage(sensor_dn, PTE_STAGE_TARGET_SENSOR_DN, stage_callback, stage_user_data);
    auto [toned, encoded] = run_isp(sensor_dn, resolved.sensor, resolved.isp);
    emit_stage(toned, PTE_STAGE_TARGET_ISP_LINEAR, stage_callback, stage_user_data);
    emit_stage(encoded, PTE_STAGE_OUTPUT_SRGB, stage_callback, stage_user_data);
    return encoded;
}

}  // namespace phytoy
