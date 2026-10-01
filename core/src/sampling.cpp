#include "profile.hpp"

#include <algorithm>
#include <cmath>
#include <stdexcept>
#include <map>

namespace phytoy {
namespace {
double bayer_axis_energy(double bin_width) {
    if (bin_width <= 1.0) return .75;
    if (bin_width > 64.0) return 2.0 / bin_width;
    double energy = 0.0;
    for (int phase = 0; phase < 2; ++phase) {
        const double left = phase-.5, right = phase+bin_width-.5;
        std::map<int, double> weights;
        for (int pixel = phase; pixel < static_cast<int>(std::ceil(right+.5)); ++pixel) {
            const double weight = std::max(0.0, std::min(right, pixel+.5)-std::max(left, pixel-.5)) / bin_width;
            if (pixel % 2 == 0) weights[pixel/2] += weight;
            else {
                weights[pixel/2] += weight*.5;
                weights[pixel/2+1] += weight*.5;
            }
        }
        for (const auto& [index, weight] : weights) {
            (void)index;
            energy += weight*weight;
        }
    }
    return energy*.5;
}
}  // namespace

ToyProfile profile_for_resolution(const ToyProfile& profile, uint32_t width, uint32_t height) {
    if (profile.reference_width == 0U) return profile;
    if (width == 0U || height == 0U) throw std::invalid_argument("render dimensions must be positive");
    const double sx = static_cast<double>(width) / profile.reference_width;
    const double sy = static_cast<double>(height) / profile.reference_height;
    if (std::max(sx, sy) > 2.0) {
        throw std::invalid_argument("reference_sampling supports at most 2x the reference dimensions");
    }
    ToyProfile result = profile;
    result.reference_width = result.reference_height = 0U;
    const double spatial_scale = std::sqrt(sx*sy);
    bool unsupported = profile.isp.denoise_sigma*spatial_scale > 1.0 ||
        (profile.isp.sharpen_amount != 0.0F && profile.isp.sharpen_radius*spatial_scale > 1.0);
    for (const auto& kernel : profile.optics.psf_bases) {
        unsupported |= std::ceil((kernel.width/2U)*sx) > 4.0 ||
            std::ceil((kernel.height/2U)*sy) > 4.0;
    }
    if (unsupported) {
        throw std::invalid_argument("reference_sampling exceeds shared filter support (9x9 PSF, Gaussian sigma <= 1)");
    }
    if (sx == 1.0 && sy == 1.0) return result;
    for (auto& kernel : result.optics.psf_bases) {
        const int rx = static_cast<int>(kernel.width / 2U);
        const int ry = static_cast<int>(kernel.height / 2U);
        const int out_rx = static_cast<int>(std::ceil(rx * sx));
        const int out_ry = static_cast<int>(std::ceil(ry * sy));
        Kernel scaled;
        scaled.width = static_cast<uint32_t>(2 * out_rx + 1);
        scaled.height = static_cast<uint32_t>(2 * out_ry + 1);
        scaled.values.resize(static_cast<size_t>(scaled.width) * scaled.height);
        for (uint32_t y = 0; y < kernel.height; ++y) {
            for (uint32_t x = 0; x < kernel.width; ++x) {
                const double px = (static_cast<int>(x) - rx) * sx + out_rx;
                const double py = (static_cast<int>(y) - ry) * sy + out_ry;
                const int ix = static_cast<int>(std::floor(px));
                const int iy = static_cast<int>(std::floor(py));
                const double tx = px - ix, ty = py - iy;
                for (int dy = 0; dy <= 1; ++dy) {
                    for (int dx = 0; dx <= 1; ++dx) {
                        const double weight = (dx == 0 ? 1.0-tx : tx) * (dy == 0 ? 1.0-ty : ty);
                        if (weight > 0.0) {
                            scaled.values[static_cast<size_t>(iy+dy) * scaled.width +
                                          static_cast<size_t>(ix+dx)] +=
                                kernel.values[static_cast<size_t>(y) * kernel.width + x] *
                                static_cast<float>(weight);
                        }
                    }
                }
            }
        }
        kernel = std::move(scaled);
    }
    const double noise_gain = std::sqrt(bayer_axis_energy(1.0/sx)*bayer_axis_energy(1.0/sy))/.75;
    const double area = 1.0 / (noise_gain*noise_gain);
    const float scale = static_cast<float>(spatial_scale);
    result.isp.denoise_sigma *= scale;
    result.isp.sharpen_radius *= scale;
    auto& sensor = result.sensor;
    sensor.exposure_scale_e *= static_cast<float>(area);
    sensor.full_well_e *= static_cast<float>(area);
    sensor.conversion_gain_e_per_dn *= static_cast<float>(area);
    sensor.read_noise_e *= static_cast<float>(std::sqrt(area));
    sensor.dsnu_e *= static_cast<float>(std::sqrt(area));
    sensor.prnu_sigma *= static_cast<float>(noise_gain);
    sensor.row_noise_e *= static_cast<float>(area * std::sqrt(std::min(sy, 1.0)));
    sensor.column_noise_e *= static_cast<float>(area * std::sqrt(std::min(sx, 1.0)));
    return result;
}

}  // namespace phytoy
