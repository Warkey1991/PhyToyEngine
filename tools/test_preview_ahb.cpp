// Android-only integration test for the packaged core library, using synthetic
// AHardwareBuffers. Does not open a camera, surface, gallery or application.
#include "phytoy/phytoy.h"
#include <android/hardware_buffer.h>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <filesystem>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <vector>

struct Buffer {
    AHardwareBuffer* value{};
    uint32_t width, height;
    Buffer(uint32_t w, uint32_t h) : width(w), height(h) {
        AHardwareBuffer_Desc d{}; d.width = w; d.height = h; d.layers = 1;
        d.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
        d.usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN;
        if (AHardwareBuffer_allocate(&d, &value)) throw std::runtime_error("AHB allocation");
        void* mapped{};
        if (AHardwareBuffer_lock(value, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, nullptr, &mapped)) throw std::runtime_error("AHB lock");
        AHardwareBuffer_describe(value, &d);
        auto* bytes = static_cast<uint8_t*>(mapped);
        for (uint32_t y = 0; y < h; ++y) for (uint32_t x = 0; x < w; ++x) {
            const size_t offset = (static_cast<size_t>(y) * d.stride + x) * 4;
            bytes[offset] = static_cast<uint8_t>((x * 37 + y * 17) % 251);
            bytes[offset + 1] = static_cast<uint8_t>((x * 11 + y * 23) % 251);
            bytes[offset + 2] = static_cast<uint8_t>((x * 19 + y * 13) % 251); bytes[offset + 3] = 255;
        }
        if (AHardwareBuffer_unlock(value, nullptr)) throw std::runtime_error("AHB unlock");
    }
    ~Buffer() { if (value) AHardwareBuffer_release(value); }
    pte_ahardware_buffer_frame_t frame() const { return {PTE_ABI_VERSION, value, width, height, -1}; }
};
struct Engine {
    pte_engine_t* value{};
    Engine(const std::filesystem::path& host, const std::filesystem::path& toy) {
        if (pte_engine_create(host.c_str(), toy.c_str(), &value) != PTE_STATUS_OK) throw std::runtime_error(pte_last_error());
        check(pte_engine_set_backend(value, PTE_BACKEND_VULKAN));
    }
    ~Engine() { pte_engine_destroy(value); }
    void check(pte_status_t status) const { if (status != PTE_STATUS_OK) throw std::runtime_error(pte_engine_last_error(value)); }
    void process(const Buffer& b, uint64_t seed) const {
        const auto frame = b.frame(); pte_render_options_t options{}; options.abi_version = PTE_ABI_VERSION; options.seed = seed;
        check(pte_engine_process_ahardware_buffer(value, &frame, &options));
    }
    std::vector<float> capture(const Buffer& b, uint64_t seed, bool stages = false) const {
        const auto frame = b.frame(); pte_render_options_t options{}; options.abi_version = PTE_ABI_VERSION; options.seed = seed;
        uint32_t calls = 0;
        if (stages) {
            options.stage_user_data = &calls;
            options.stage_callback = [](void* user, pte_stage_t, const float*, uint32_t, uint32_t, uint32_t) { ++*static_cast<uint32_t*>(user); };
        }
        std::vector<float> output(static_cast<size_t>(b.width) * b.height * 3);
        pte_output_f32_t destination{};
        destination.data = output.data(); destination.row_stride_floats = b.width * 3;
        destination.capacity_floats = output.size();
        check(pte_engine_render_ahardware_buffer(value, &frame, &options, &destination));
        if (stages && calls != 5) throw std::runtime_error("capture stage callbacks");
        for (float f : output) if (!std::isfinite(f)) throw std::runtime_error("nonfinite capture");
        return output;
    }
    uint64_t imports() const {
        pte_runtime_stats_t stats{}; stats.abi_version = PTE_ABI_VERSION;
        check(pte_engine_get_runtime_stats(value, &stats)); return stats.ahardware_buffer_imports;
    }
};
void require(bool pass, const char* message) { if (!pass) throw std::runtime_error(message); }
bool bit_equal(const std::vector<float>& a, const std::vector<float>& b) {
    return a.size() == b.size() && std::memcmp(a.data(), b.data(), a.size() * sizeof(float)) == 0;
}
int main(int argc, char** argv) {
    try {
        if (argc != 2) throw std::runtime_error("Usage: test_preview_ahb PROFILE_DIRECTORY");
        const std::filesystem::path dir(argv[1]);
        std::vector<std::unique_ptr<Buffer>> pool;
        for (uint32_t i = 0; i < 35; ++i) pool.push_back(std::make_unique<Buffer>(37, 29));
        Buffer resized(17, 17);
        Buffer clear_preview(960, 720);
        size_t styles = 0;
        for (const auto* profile : {"toy_phytoy_digital_01_v1_2.ptp", "toy_phytoy_plastic_82_v1.ptp", "toy_phytoy_street_84_v1.ptp", "toy_phytoy_fisheye_05_v1.ptp", "toy_harinezumi_2pp_daylight_v0_4.ptp", "toy_harinezumi_2pp_mono_v0_2.ptp"}) {
            Engine e(dir / "host_generic_srgb.ptp", dir / profile);
            const auto gold = e.capture(*pool[0], 0xFE001234ULL);
            for (uint32_t i = 0; i < 54; ++i) e.process(*pool[i % 18], 0xFE001230ULL + i);
            require(e.imports() == 18, "18-buffer pool should stop importing after warmup");
            require(bit_equal(gold, e.capture(*pool[0], 0xFE001234ULL)), "capture changed after cached preview");
            require(bit_equal(gold, e.capture(*pool[0], 0xFE001234ULL, true)), "capture callbacks changed result");
            e.check(pte_engine_forget_ahardware_buffer(e.value, pool[0]->value));
            e.process(*pool[0], 3); require(e.imports() == 19, "removed buffer should be reimported");
            e.process(resized, 0xFFFF1234ULL); e.process(clear_preview, 0xFFFFFFFFULL);
            e.process(*pool[0], 0xFFFFFFFFULL);
            require(bit_equal(gold, e.capture(*pool[0], 0xFE001234ULL)), "capture changed after preview resize");
            e.check(pte_engine_forget_ahardware_buffer(e.value, nullptr));
            const auto before = e.imports(); e.process(*pool[0], 4);
            require(e.imports() == before + 1, "cache clear should reimport");
            std::cout << "PASS " << profile << '\n'; ++styles;
        }
        Engine lru(dir / "host_generic_srgb.ptp", dir / "toy_phytoy_digital_01_v1_2.ptp");
        for (const auto& buffer : pool) lru.process(*buffer, 1);
        require(lru.imports() == 35, "35 distinct buffer imports");
        lru.process(*pool.back(), 1); require(lru.imports() == 35, "recent buffer should stay cached");
        lru.process(*pool.front(), 1); require(lru.imports() == 36, "oldest buffer should be evicted at 32 entries");
        std::cout << "{\"passed\":true,\"styles\":" << styles << ",\"pool_size\":18,\"preview_frames_per_style\":54,\"lru_eviction_checked\":true,\"capture_bit_equal\":true}\n";
        return 0;
    } catch (const std::exception& e) { std::cerr << e.what() << '\n'; return 1; }
}
