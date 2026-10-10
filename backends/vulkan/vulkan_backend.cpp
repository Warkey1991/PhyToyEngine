#include "vulkan_backend.hpp"

#include "cpu_backend.hpp"
#include "preview_geometry.hpp"

#include <algorithm>
#include <array>
#include <bit>
#include <chrono>
#include <cmath>
#include <cstring>
#include <dlfcn.h>
#include <map>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

#if defined(__ANDROID__) && defined(PHYTOY_HAS_EMBEDDED_SPIRV)
#include "phytoy_spirv.hpp"
#include <android/hardware_buffer.h>
#include <android/log.h>
#include <android/native_window.h>
#include <sys/mman.h>
#include <unistd.h>
#define VK_USE_PLATFORM_ANDROID_KHR 1
#include <vulkan/vulkan.h>
#endif

namespace phytoy {

#if defined(__ANDROID__) && defined(PHYTOY_HAS_EMBEDDED_SPIRV)
namespace {

void require_vk(VkResult result, const char* operation) {
    if (result != VK_SUCCESS) {
        throw std::runtime_error(std::string(operation) + " failed with VkResult " + std::to_string(result));
    }
}

uint32_t cfa_code(const std::string& cfa) {
    if (cfa == "RGGB") return 0U;
    if (cfa == "BGGR") return 1U;
    if (cfa == "GRBG") return 2U;
    if (cfa == "GBRG") return 3U;
    throw std::runtime_error("unsupported Vulkan CFA: " + cfa);
}

void emit_stage(const Image& image, pte_stage_t stage,
                pte_stage_callback_f32 callback, void* user_data) {
    if (callback != nullptr) {
        callback(user_data, stage, image.pixels.data(), image.width, image.height, image.channels);
    }
}

float distortion_safe_scale(const OpticsProfile& optics) {
    const float k1 = optics.distortion[0];
    const float k2 = optics.distortion[1];
    const auto radial_at = [k1, k2](float radius2) {
        return std::abs(1.0F + k1 * radius2 + k2 * radius2 * radius2);
    };
    float maximum_radial = std::max({radial_at(0.0F), radial_at(1.0F), radial_at(2.0F)});
    if (std::abs(k2) > 1e-7F) {
        const float stationary_radius2 = -k1 / (2.0F * k2);
        if (stationary_radius2 > 0.0F && stationary_radius2 < 2.0F) {
            maximum_radial = std::max(maximum_radial, radial_at(stationary_radius2));
        }
    }
    const float tangential_bound = 4.0F * (
        std::abs(optics.distortion[2]) + std::abs(optics.distortion[3]));
    const float maximum_ca = *std::max_element(optics.ca_scale.begin(), optics.ca_scale.end());
    return 1.0F / std::max(maximum_ca * (maximum_radial + tangential_bound), 1.0F);
}

std::vector<float> optics_parameters(uint32_t width, uint32_t height, const OpticsProfile& optics) {
    if (optics.psf_bases.empty()) throw std::runtime_error("Vulkan optics needs at least one PSF basis");
    const uint32_t kernel_width = optics.psf_bases.front().width;
    const uint32_t kernel_height = optics.psf_bases.front().height;
    const uint32_t grid_width = optics.psf_coefficients.front().width;
    const uint32_t grid_height = optics.psf_coefficients.front().height;
    for (const auto& basis : optics.psf_bases) {
        if (basis.width != kernel_width || basis.height != kernel_height) {
            throw std::runtime_error("Vulkan Alpha requires equal PSF basis dimensions");
        }
    }
    if (kernel_width > 9U || kernel_height > 9U) {
        throw std::runtime_error("Vulkan Alpha supports PSF kernels up to 9x9");
    }
    for (const auto& map : optics.psf_coefficients) {
        if (map.width != grid_width || map.height != grid_height) {
            throw std::runtime_error("Vulkan Alpha requires equal PSF coefficient grid dimensions");
        }
    }
    std::vector<float> result(21U, 0.0F);
    result[0] = static_cast<float>(width);
    result[1] = static_cast<float>(height);
    std::copy(optics.distortion.begin(), optics.distortion.end(), result.begin() + 2);
    std::copy(optics.ca_scale.begin(), optics.ca_scale.end(), result.begin() + 6);
    for (size_t channel = 0; channel < 3U; ++channel) {
        result[9U + channel * 2U] = optics.vignette[channel][0];
        result[10U + channel * 2U] = optics.vignette[channel][1];
    }
    result[15] = static_cast<float>(optics.psf_bases.size());
    result[16] = static_cast<float>(kernel_width);
    result[17] = static_cast<float>(kernel_height);
    result[18] = static_cast<float>(grid_width);
    result[19] = static_cast<float>(grid_height);
    result[20] = distortion_safe_scale(optics);
    for (const auto& basis : optics.psf_bases) {
        result.insert(result.end(), basis.values.begin(), basis.values.end());
    }
    for (const auto& map : optics.psf_coefficients) {
        result.insert(result.end(), map.values.begin(), map.values.end());
    }
    return result;
}

std::vector<float> sensor_parameters(
    uint32_t width, uint32_t height, const SensorProfile& sensor, uint64_t photo_seed) {
    std::vector<float> result(25U, 0.0F);
    result[0] = static_cast<float>(width);
    result[1] = static_cast<float>(height);
    std::copy(sensor.scene_to_sensor.begin(), sensor.scene_to_sensor.end(), result.begin() + 2);
    result[11] = sensor.exposure_scale_e;
    result[12] = sensor.full_well_e;
    result[13] = sensor.conversion_gain_e_per_dn;
    result[14] = sensor.black_level_dn;
    result[15] = sensor.white_level_dn;
    result[16] = sensor.read_noise_e;
    result[17] = sensor.row_noise_e;
    result[18] = sensor.column_noise_e;
    result[19] = sensor.prnu_sigma;
    result[20] = sensor.dsnu_e;
    /* Preserve all 32 seed bits; numeric float conversion would collapse adjacent
       seeds above 2^24 and break the deterministic render contract. */
    result[21] = std::bit_cast<float>(static_cast<uint32_t>(sensor.profile_seed));
    result[22] = std::bit_cast<float>(static_cast<uint32_t>(photo_seed));
    result[23] = static_cast<float>(cfa_code(sensor.cfa));
    result[24] = sensor.noise_enabled ? 1.0F : 0.0F;
    return result;
}

std::vector<float> isp_parameters(
    uint32_t width, uint32_t height, const SensorProfile& sensor, const IspProfile& isp) {
    if (!std::isfinite(isp.denoise_sigma) || isp.denoise_sigma < 0.0F ||
        isp.denoise_sigma > 1.0F || !std::isfinite(isp.sharpen_amount) ||
        !std::isfinite(isp.sharpen_radius) || isp.sharpen_radius <= 0.0F ||
        (isp.sharpen_amount != 0.0F && isp.sharpen_radius > 1.0F)) {
        throw std::invalid_argument("Vulkan ISP requires finite active Gaussian sigma in [0,1]");
    }
    std::vector<float> result(18U, 0.0F);
    result[0] = static_cast<float>(width);
    result[1] = static_cast<float>(height);
    result[2] = sensor.black_level_dn;
    result[3] = sensor.white_level_dn;
    result[4] = static_cast<float>(cfa_code(sensor.cfa));
    std::copy(isp.white_balance.begin(), isp.white_balance.end(), result.begin() + 5);
    std::copy(isp.sensor_to_rec2020.begin(), isp.sensor_to_rec2020.end(), result.begin() + 8);
    result[17] = static_cast<float>(isp.tone_curve.size());
    for (const auto& [x, y] : isp.tone_curve) {
        result.push_back(x);
        result.push_back(y);
    }
    result.push_back(isp.denoise_sigma);
    result.push_back(isp.sharpen_amount);
    result.push_back(isp.sharpen_radius);
    result.push_back(isp.monochrome ? 1.0F : 0.0F);
    return result;
}

std::vector<float> ahb_normalization_parameters(
    uint32_t width, uint32_t height, const HostProfile& host) {
    constexpr std::array<float, 9> srgb_to_rec2020{
        0.62750375F, 0.32927542F, 0.04330266F,
        0.06910822F, 0.91951916F, 0.01135960F,
        0.01639406F, 0.08801125F, 0.89538035F,
    };
    std::vector<float> result(11U, 0.0F);
    result[0] = static_cast<float>(width);
    result[1] = static_cast<float>(height);
    for (size_t row = 0U; row < 3U; ++row) {
        for (size_t column = 0U; column < 3U; ++column) {
            for (size_t inner = 0U; inner < 3U; ++inner) {
                result[2U + row * 3U + column] +=
                    host.residual_matrix[row * 3U + inner] *
                    srgb_to_rec2020[inner * 3U + column];
            }
        }
    }
    return result;
}

}  // namespace

struct VulkanBackend::Impl {
    enum class RenderResourceMode {
        Production,
        PreserveOptics,
        PreserveSceneAndOptics,
    };

    struct Buffer {
        VkDevice device{VK_NULL_HANDLE};
        VkBuffer buffer{VK_NULL_HANDLE};
        VkDeviceMemory memory{VK_NULL_HANDLE};
        VkDeviceSize size{};
        void* mapped{};

        Buffer() = default;
        Buffer(const Buffer&) = delete;
        Buffer& operator=(const Buffer&) = delete;
        Buffer(Buffer&& other) noexcept { *this = std::move(other); }
        Buffer& operator=(Buffer&& other) noexcept {
            if (this != &other) {
                destroy();
                device = other.device;
                buffer = std::exchange(other.buffer, VK_NULL_HANDLE);
                memory = std::exchange(other.memory, VK_NULL_HANDLE);
                size = other.size;
                mapped = std::exchange(other.mapped, nullptr);
            }
            return *this;
        }
        ~Buffer() { destroy(); }
        void destroy() noexcept {
            if (device != VK_NULL_HANDLE && memory != VK_NULL_HANDLE && mapped != nullptr) vkUnmapMemory(device, memory);
            if (device != VK_NULL_HANDLE && buffer != VK_NULL_HANDLE) vkDestroyBuffer(device, buffer, nullptr);
            if (device != VK_NULL_HANDLE && memory != VK_NULL_HANDLE) vkFreeMemory(device, memory, nullptr);
            device = VK_NULL_HANDLE;
            buffer = VK_NULL_HANDLE;
            memory = VK_NULL_HANDLE;
            mapped = nullptr;
            size = 0U;
        }
    };

    struct ImportedAhbImage {
        VkDevice device{VK_NULL_HANDLE};
        VkImage image{VK_NULL_HANDLE};
        VkDeviceMemory memory{VK_NULL_HANDLE};
        VkImageView view{VK_NULL_HANDLE};
        VkDeviceSize allocation_size{};

        ImportedAhbImage() = default;
        ImportedAhbImage(const ImportedAhbImage&) = delete;
        ImportedAhbImage& operator=(const ImportedAhbImage&) = delete;
        ImportedAhbImage(ImportedAhbImage&& other) noexcept { *this = std::move(other); }
        ImportedAhbImage& operator=(ImportedAhbImage&& other) noexcept {
            if (this != &other) {
                destroy();
                device = other.device;
                image = std::exchange(other.image, VK_NULL_HANDLE);
                memory = std::exchange(other.memory, VK_NULL_HANDLE);
                view = std::exchange(other.view, VK_NULL_HANDLE);
                allocation_size = std::exchange(other.allocation_size, 0U);
            }
            return *this;
        }
        ~ImportedAhbImage() { destroy(); }
        void destroy() noexcept {
            if (device != VK_NULL_HANDLE && view != VK_NULL_HANDLE) vkDestroyImageView(device, view, nullptr);
            if (device != VK_NULL_HANDLE && image != VK_NULL_HANDLE) vkDestroyImage(device, image, nullptr);
            if (device != VK_NULL_HANDLE && memory != VK_NULL_HANDLE) vkFreeMemory(device, memory, nullptr);
            device = VK_NULL_HANDLE;
            image = VK_NULL_HANDLE;
            memory = VK_NULL_HANDLE;
            view = VK_NULL_HANDLE;
            allocation_size = 0U;
        }
    };

    struct CachedAhbImage {
        AHardwareBuffer* source{};
        uint64_t identity{};
        ImportedAhbImage imported;
        uint32_t width{};
        uint32_t height{};
        uint64_t last_use{};

        CachedAhbImage(AHardwareBuffer* buffer, uint64_t buffer_identity,
                       ImportedAhbImage&& image,
                       uint32_t image_width, uint32_t image_height, uint64_t use)
            : source(buffer), identity(buffer_identity), imported(std::move(image)), width(image_width),
              height(image_height), last_use(use) {
            AHardwareBuffer_acquire(source);
        }
        CachedAhbImage(const CachedAhbImage&) = delete;
        CachedAhbImage& operator=(const CachedAhbImage&) = delete;
        CachedAhbImage(CachedAhbImage&& other) noexcept
            : source(std::exchange(other.source, nullptr)),
              identity(other.identity),
              imported(std::move(other.imported)), width(other.width),
              height(other.height), last_use(other.last_use) {}
        CachedAhbImage& operator=(CachedAhbImage&& other) noexcept {
            if (this != &other) {
                destroy();
                source = std::exchange(other.source, nullptr);
                identity = other.identity;
                imported = std::move(other.imported);
                width = other.width;
                height = other.height;
                last_use = other.last_use;
            }
            return *this;
        }
        ~CachedAhbImage() { destroy(); }
        void destroy() noexcept {
            imported.destroy();
            if (source != nullptr) AHardwareBuffer_release(source);
            source = nullptr;
        }
    };

    struct PreparedPreview {
        const HostProfile* host{};
        const ToyProfile* toy{};
        uint32_t width{};
        uint32_t height{};
        std::vector<float> normalization;
        std::vector<float> optics;
        std::vector<float> sensor;
        std::vector<float> isp;
        VkPipeline optics_pipeline{VK_NULL_HANDLE};
        VkPipeline isp_pipeline{VK_NULL_HANDLE};
        uint32_t optics_radius{};
        uint32_t isp_halo{};
    };

    using Clock = std::chrono::steady_clock;
    static double elapsed_ms(Clock::time_point start, Clock::time_point end) {
        return std::chrono::duration<double, std::milli>(end - start).count();
    }

    VkInstance instance{VK_NULL_HANDLE};
    VkPhysicalDevice physical_device{VK_NULL_HANDLE};
    VkDevice device{VK_NULL_HANDLE};
    VkQueue queue{VK_NULL_HANDLE};
    uint32_t queue_family{};
    VkQueueFlags queue_flags{};
    uint32_t timestamp_valid_bits{};
    float timestamp_period{};
    VkQueryPool preview_timestamp_pool{VK_NULL_HANDLE};
    PFN_vkGetAndroidHardwareBufferPropertiesANDROID get_ahb_properties{};
    PFN_vkImportSemaphoreFdKHR import_semaphore_fd{};
    PFN_vkCreateSamplerYcbcrConversion create_ycbcr_conversion{};
    PFN_vkDestroySamplerYcbcrConversion destroy_ycbcr_conversion{};
    using GetAhbId = int (*)(const AHardwareBuffer*, uint64_t*);
    GetAhbId get_ahb_id{};
    VkDescriptorSetLayout descriptor_layout{VK_NULL_HANDLE};
    VkPipelineLayout pipeline_layout{VK_NULL_HANDLE};
    VkPipeline optics_pipeline{VK_NULL_HANDLE};
    VkPipeline sensor_pipeline{VK_NULL_HANDLE};
    VkPipeline isp_pipeline{VK_NULL_HANDLE};
    std::map<std::array<int32_t, 6>, VkPipeline> preview_optics_pipelines;
    std::map<std::array<int32_t, 7>, VkPipeline> preview_isp_pipelines;
    PreparedPreview prepared_preview;
    VkDescriptorSetLayout ahb_descriptor_layout{VK_NULL_HANDLE};
    VkPipelineLayout ahb_pipeline_layout{VK_NULL_HANDLE};
    VkPipeline ahb_pipeline{VK_NULL_HANDLE};
    VkDescriptorPool ahb_descriptor_pool{VK_NULL_HANDLE};
    VkDescriptorSet ahb_descriptor_set{VK_NULL_HANDLE};
    VkSamplerYcbcrConversion ahb_conversion{VK_NULL_HANDLE};
    VkSampler ahb_sampler{VK_NULL_HANDLE};
    uint64_t ahb_external_format{};
    VkFormat ahb_format{VK_FORMAT_UNDEFINED};
    VkDescriptorPool descriptor_pool{VK_NULL_HANDLE};
    std::array<VkDescriptorSet, 3> descriptor_sets{};
    VkCommandPool command_pool{VK_NULL_HANDLE};
    VkCommandBuffer command_buffer{VK_NULL_HANDLE};
    VkFence render_fence{VK_NULL_HANDLE};
    Buffer scene_buffer;
    Buffer optics_buffer;
    Buffer sensor_buffer;
    Buffer output_buffer;
    Buffer optics_parameter_buffer;
    Buffer sensor_parameter_buffer;
    Buffer isp_parameter_buffer;
    Buffer ahb_parameter_buffer;
    Buffer stage_buffer;
    ANativeWindow* output_window{};
    uint32_t output_rotation_degrees{};
    VkSurfaceKHR output_surface{VK_NULL_HANDLE};
    VkSwapchainKHR swapchain{VK_NULL_HANDLE};
    VkFormat swapchain_format{VK_FORMAT_UNDEFINED};
    VkExtent2D swapchain_extent{};
    std::vector<VkImage> swapchain_images;
    std::vector<VkImageView> swapchain_views;
    std::vector<VkFramebuffer> swapchain_framebuffers;
    VkRenderPass presentation_render_pass{VK_NULL_HANDLE};
    VkDescriptorSetLayout presentation_descriptor_layout{VK_NULL_HANDLE};
    VkPipelineLayout presentation_pipeline_layout{VK_NULL_HANDLE};
    VkPipeline presentation_pipeline{VK_NULL_HANDLE};
    VkDescriptorPool presentation_descriptor_pool{VK_NULL_HANDLE};
    VkDescriptorSet presentation_descriptor_set{VK_NULL_HANDLE};
    VkSemaphore presentation_image_available{VK_NULL_HANDLE};
    VkSemaphore presentation_render_finished{VK_NULL_HANDLE};
    bool presentation_attachment_is_srgb{};
    bool swapchain_supported{};
    RenderResourceMode descriptor_resource_mode{RenderResourceMode::Production};
    bool ahb_import_supported{};
    bool external_semaphore_fd_supported{};
    uint32_t external_queue_family{VK_QUEUE_FAMILY_EXTERNAL};
    uint64_t resource_allocations{};
    uint64_t queue_submissions{};
    uint64_t ahb_imports{};
    uint64_t presented_frames{};
    uint64_t swapchain_recreates{};
    uint64_t ahb_use_clock{};
    uint64_t ahb_cache_hits{};
    uint64_t ahb_cache_misses{};
    uint64_t ahb_cache_evictions{};
    uint64_t ahb_cache_removals{};
    uint64_t ahb_stable_ids{};
    uint64_t ahb_pointer_ids{};
    uint64_t preview_frames{};
    uint64_t preview_preparations{};
    double sampled_prepare_ms{};
    double sampled_import_ms{};
    double sampled_upload_ms{};
    std::vector<CachedAhbImage> ahb_cache;
    ImportedAhbImage uncached_ahb;
    bool ready{};
    std::string error;

    Impl() noexcept {
        try {
            initialize();
            ready = true;
        } catch (const std::exception& exception) {
            error = exception.what();
            destroy();
        } catch (...) {
            error = "unknown Vulkan initialization failure";
            destroy();
        }
    }

    ~Impl() { destroy(); }

    void initialize() {
        get_ahb_id = reinterpret_cast<GetAhbId>(
            dlsym(RTLD_DEFAULT, "AHardwareBuffer_getId"));
        VkApplicationInfo application{};
        application.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
        application.pApplicationName = "PhyToyEngine";
        application.applicationVersion = VK_MAKE_VERSION(0, 3, 0);
        application.pEngineName = "PhyToyEngine";
        application.engineVersion = VK_MAKE_VERSION(0, 3, 0);
        application.apiVersion = VK_API_VERSION_1_1;
        VkInstanceCreateInfo instance_info{};
        instance_info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
        instance_info.pApplicationInfo = &application;
        const std::array<const char*, 2> instance_extensions{
            VK_KHR_SURFACE_EXTENSION_NAME,
            VK_KHR_ANDROID_SURFACE_EXTENSION_NAME,
        };
        instance_info.enabledExtensionCount = static_cast<uint32_t>(instance_extensions.size());
        instance_info.ppEnabledExtensionNames = instance_extensions.data();
        require_vk(vkCreateInstance(&instance_info, nullptr, &instance), "vkCreateInstance");

        uint32_t device_count = 0U;
        require_vk(vkEnumeratePhysicalDevices(instance, &device_count, nullptr), "vkEnumeratePhysicalDevices(count)");
        if (device_count == 0U) throw std::runtime_error("no Vulkan physical device");
        std::vector<VkPhysicalDevice> devices(device_count);
        require_vk(vkEnumeratePhysicalDevices(instance, &device_count, devices.data()), "vkEnumeratePhysicalDevices");
        for (VkPhysicalDevice candidate : devices) {
            uint32_t family_count = 0U;
            vkGetPhysicalDeviceQueueFamilyProperties(candidate, &family_count, nullptr);
            std::vector<VkQueueFamilyProperties> families(family_count);
            vkGetPhysicalDeviceQueueFamilyProperties(candidate, &family_count, families.data());
            for (uint32_t family = 0U; family < family_count; ++family) {
                if ((families[family].queueFlags &
                     (VK_QUEUE_COMPUTE_BIT | VK_QUEUE_GRAPHICS_BIT)) ==
                    (VK_QUEUE_COMPUTE_BIT | VK_QUEUE_GRAPHICS_BIT)) {
                    physical_device = candidate;
                    queue_family = family;
                    queue_flags = families[family].queueFlags;
                    timestamp_valid_bits = families[family].timestampValidBits;
                    break;
                }
            }
            if (physical_device != VK_NULL_HANDLE) break;
        }
        if (physical_device == VK_NULL_HANDLE) throw std::runtime_error("no Vulkan compute queue");

        uint32_t extension_count = 0U;
        require_vk(vkEnumerateDeviceExtensionProperties(
                       physical_device, nullptr, &extension_count, nullptr),
                   "vkEnumerateDeviceExtensionProperties(count)");
        std::vector<VkExtensionProperties> extensions(extension_count);
        require_vk(vkEnumerateDeviceExtensionProperties(
                       physical_device, nullptr, &extension_count, extensions.data()),
                   "vkEnumerateDeviceExtensionProperties");
        const auto supports_extension = [&extensions](const char* name) {
            return std::any_of(extensions.begin(), extensions.end(), [name](const auto& extension) {
                return std::strcmp(extension.extensionName, name) == 0;
            });
        };

        VkPhysicalDeviceProperties physical_properties{};
        vkGetPhysicalDeviceProperties(physical_device, &physical_properties);
        timestamp_period = physical_properties.limits.timestampPeriod;
        if (!physical_properties.limits.timestampComputeAndGraphics) timestamp_valid_bits = 0U;
        VkPhysicalDeviceSamplerYcbcrConversionFeatures ycbcr_features{};
        ycbcr_features.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SAMPLER_YCBCR_CONVERSION_FEATURES;
        if (VK_VERSION_MAJOR(physical_properties.apiVersion) > 1U ||
            (VK_VERSION_MAJOR(physical_properties.apiVersion) == 1U &&
             VK_VERSION_MINOR(physical_properties.apiVersion) >= 1U)) {
            const auto get_features2 = reinterpret_cast<PFN_vkGetPhysicalDeviceFeatures2>(
                vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceFeatures2"));
            if (get_features2 != nullptr) {
                VkPhysicalDeviceFeatures2 features{};
                features.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
                features.pNext = &ycbcr_features;
                get_features2(physical_device, &features);
            }
        }
        ahb_import_supported =
            supports_extension(VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME) &&
            ycbcr_features.samplerYcbcrConversion == VK_TRUE;
        external_semaphore_fd_supported =
            supports_extension(VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME);
        swapchain_supported = supports_extension(VK_KHR_SWAPCHAIN_EXTENSION_NAME);
        const bool foreign_queue_supported =
            supports_extension(VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME);
        external_queue_family = foreign_queue_supported
            ? VK_QUEUE_FAMILY_FOREIGN_EXT
            : VK_QUEUE_FAMILY_EXTERNAL;

        std::vector<const char*> enabled_extensions;
        if (ahb_import_supported) {
            enabled_extensions.push_back(
                VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME);
        }
        if (external_semaphore_fd_supported) {
            enabled_extensions.push_back(VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME);
        }
        if (foreign_queue_supported) {
            enabled_extensions.push_back(VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME);
        }
        if (swapchain_supported) {
            enabled_extensions.push_back(VK_KHR_SWAPCHAIN_EXTENSION_NAME);
        }

        const float priority = 1.0F;
        VkDeviceQueueCreateInfo queue_info{};
        queue_info.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
        queue_info.queueFamilyIndex = queue_family;
        queue_info.queueCount = 1U;
        queue_info.pQueuePriorities = &priority;
        VkDeviceCreateInfo device_info{};
        device_info.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
        device_info.queueCreateInfoCount = 1U;
        device_info.pQueueCreateInfos = &queue_info;
        device_info.enabledExtensionCount = static_cast<uint32_t>(enabled_extensions.size());
        device_info.ppEnabledExtensionNames = enabled_extensions.data();
        if (ahb_import_supported) {
            ycbcr_features.samplerYcbcrConversion = VK_TRUE;
            device_info.pNext = &ycbcr_features;
        }
        require_vk(vkCreateDevice(physical_device, &device_info, nullptr, &device), "vkCreateDevice");
        vkGetDeviceQueue(device, queue_family, 0U, &queue);
        if (ahb_import_supported) {
            get_ahb_properties =
                reinterpret_cast<PFN_vkGetAndroidHardwareBufferPropertiesANDROID>(
                    vkGetDeviceProcAddr(device, "vkGetAndroidHardwareBufferPropertiesANDROID"));
            create_ycbcr_conversion = reinterpret_cast<PFN_vkCreateSamplerYcbcrConversion>(
                vkGetDeviceProcAddr(device, "vkCreateSamplerYcbcrConversion"));
            destroy_ycbcr_conversion = reinterpret_cast<PFN_vkDestroySamplerYcbcrConversion>(
                vkGetDeviceProcAddr(device, "vkDestroySamplerYcbcrConversion"));
            if (get_ahb_properties == nullptr || create_ycbcr_conversion == nullptr ||
                destroy_ycbcr_conversion == nullptr) {
                ahb_import_supported = false;
            }
        }
        if (external_semaphore_fd_supported) {
            import_semaphore_fd = reinterpret_cast<PFN_vkImportSemaphoreFdKHR>(
                vkGetDeviceProcAddr(device, "vkImportSemaphoreFdKHR"));
            if (import_semaphore_fd == nullptr) external_semaphore_fd_supported = false;
        }

        std::array<VkDescriptorSetLayoutBinding, 4> bindings{};
        for (uint32_t index = 0U; index < bindings.size(); ++index) {
            bindings[index].binding = index;
            bindings[index].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            bindings[index].descriptorCount = 1U;
            bindings[index].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        }
        VkDescriptorSetLayoutCreateInfo descriptor_info{};
        descriptor_info.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
        descriptor_info.bindingCount = static_cast<uint32_t>(bindings.size());
        descriptor_info.pBindings = bindings.data();
        require_vk(vkCreateDescriptorSetLayout(device, &descriptor_info, nullptr, &descriptor_layout),
                   "vkCreateDescriptorSetLayout");
        VkPipelineLayoutCreateInfo layout_info{};
        layout_info.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
        layout_info.setLayoutCount = 1U;
        layout_info.pSetLayouts = &descriptor_layout;
        require_vk(vkCreatePipelineLayout(device, &layout_info, nullptr, &pipeline_layout), "vkCreatePipelineLayout");

        optics_pipeline = create_pipeline(spirv::optics, spirv::optics_bytes, pipeline_layout);
        sensor_pipeline = create_pipeline(spirv::sensor, spirv::sensor_bytes, pipeline_layout);
        isp_pipeline = create_pipeline(spirv::isp, spirv::isp_bytes, pipeline_layout);

        VkDescriptorPoolSize pool_size{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 12U};
        VkDescriptorPoolCreateInfo pool_info{};
        pool_info.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
        pool_info.maxSets = 3U;
        pool_info.poolSizeCount = 1U;
        pool_info.pPoolSizes = &pool_size;
        require_vk(vkCreateDescriptorPool(device, &pool_info, nullptr, &descriptor_pool), "vkCreateDescriptorPool");
        VkDescriptorSetAllocateInfo set_info{};
        set_info.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        set_info.descriptorPool = descriptor_pool;
        const std::array<VkDescriptorSetLayout, 3> set_layouts{
            descriptor_layout, descriptor_layout, descriptor_layout};
        set_info.descriptorSetCount = static_cast<uint32_t>(set_layouts.size());
        set_info.pSetLayouts = set_layouts.data();
        require_vk(vkAllocateDescriptorSets(device, &set_info, descriptor_sets.data()), "vkAllocateDescriptorSets");

        VkCommandPoolCreateInfo command_pool_info{};
        command_pool_info.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
        command_pool_info.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        command_pool_info.queueFamilyIndex = queue_family;
        require_vk(vkCreateCommandPool(device, &command_pool_info, nullptr, &command_pool), "vkCreateCommandPool");
        VkCommandBufferAllocateInfo command_info{};
        command_info.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        command_info.commandPool = command_pool;
        command_info.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        command_info.commandBufferCount = 1U;
        require_vk(vkAllocateCommandBuffers(device, &command_info, &command_buffer), "vkAllocateCommandBuffers");

        VkFenceCreateInfo fence_info{};
        fence_info.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
        require_vk(vkCreateFence(device, &fence_info, nullptr, &render_fence), "vkCreateFence");
        if (timestamp_valid_bits != 0U && timestamp_period > 0.0F) {
            VkQueryPoolCreateInfo query_info{};
            query_info.sType = VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO;
            query_info.queryType = VK_QUERY_TYPE_TIMESTAMP;
            query_info.queryCount = 6U;
            if (vkCreateQueryPool(device, &query_info, nullptr, &preview_timestamp_pool) != VK_SUCCESS) {
                preview_timestamp_pool = VK_NULL_HANDLE;
                __android_log_print(ANDROID_LOG_WARN, "PhyToyCamera2", "Preview GPU timings unavailable; CPU timings only");
            }
        }
    }

    VkPipeline create_pipeline(const uint32_t* words, size_t byte_count, VkPipelineLayout layout,
                               const VkSpecializationInfo* specialization = nullptr) {
        VkShaderModuleCreateInfo module_info{};
        module_info.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
        module_info.codeSize = byte_count;
        module_info.pCode = words;
        VkShaderModule module = VK_NULL_HANDLE;
        require_vk(vkCreateShaderModule(device, &module_info, nullptr, &module), "vkCreateShaderModule");
        VkPipelineShaderStageCreateInfo stage{};
        stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        stage.module = module;
        stage.pName = "main";
        stage.pSpecializationInfo = specialization;
        VkComputePipelineCreateInfo pipeline_info{};
        pipeline_info.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
        pipeline_info.stage = stage;
        pipeline_info.layout = layout;
        VkPipeline pipeline = VK_NULL_HANDLE;
        const VkResult result = vkCreateComputePipelines(device, VK_NULL_HANDLE, 1U, &pipeline_info, nullptr, &pipeline);
        vkDestroyShaderModule(device, module, nullptr);
        require_vk(result, "vkCreateComputePipelines");
        return pipeline;
    }

    template <size_t Count>
    VkPipeline preview_pipeline(std::map<std::array<int32_t, Count>, VkPipeline>& pipelines,
                                const std::array<int32_t, Count>& values,
                                const uint32_t* words, size_t bytes, VkPipeline fallback) {
        if (const auto found = pipelines.find(values); found != pipelines.end()) return found->second;
        // Every completed graph waits for its fence, so old compute pipelines
        // are idle here. Bound the cache across arbitrary preview size changes.
        if (pipelines.size() >= 16U) {
            const auto oldest = pipelines.begin();
            if (oldest->second != fallback) vkDestroyPipeline(device, oldest->second, nullptr);
            pipelines.erase(oldest);
        }
        VkPipeline pipeline = VK_NULL_HANDLE;
        {
            std::array<VkSpecializationMapEntry, Count> entries{};
            for (size_t index = 0U; index < Count; ++index) {
                entries[index] = {static_cast<uint32_t>(index),
                    static_cast<uint32_t>(index * sizeof(int32_t)), sizeof(int32_t)};
            }
            const VkSpecializationInfo specialization{
                static_cast<uint32_t>(Count), entries.data(), sizeof(values), values.data()};
            try {
                pipeline = create_pipeline(words, bytes, pipeline_layout, &specialization);
            } catch (const std::exception& exception) {
                // Drivers that reject a specialization retain the complete generic shader.
                pipeline = fallback;
                __android_log_print(ANDROID_LOG_WARN, "PhyToyCamera2",
                    "Preview control specialization halo=%d unavailable: %s", values[0], exception.what());
            }
        }
        pipelines.emplace(values, pipeline);
        return pipeline;
    }

    PreparedPreview& prepare_preview(const HostProfile& host, const ToyProfile& toy,
                                     uint32_t width, uint32_t height) {
        // Profiles are immutable for the lifetime of their owning engine. Dimension
        // changes rebuild all parameters; capture continues to prepare its own set.
        if (prepared_preview.host == &host && prepared_preview.toy == &toy &&
            prepared_preview.width == width && prepared_preview.height == height) return prepared_preview;
        PreparedPreview fresh;
        fresh.host = &host;
        fresh.toy = &toy;
        fresh.width = width;
        fresh.height = height;
        const ToyProfile resolved = profile_for_resolution(toy, width, height);
        fresh.normalization = ahb_normalization_parameters(width, height, host);
        fresh.optics = optics_parameters(width, height, resolved.optics);
        fresh.sensor = sensor_parameters(width, height, resolved.sensor, 0U);
        fresh.isp = isp_parameters(width, height, resolved.sensor, resolved.isp);
        fresh.optics_radius = vulkan_detail::preview_optics_radius(
            resolved.optics.psf_bases.front().width, resolved.optics.psf_bases.front().height);
        fresh.isp_halo = vulkan_detail::preview_isp_halo(
            resolved.isp.denoise_sigma, resolved.isp.sharpen_amount, resolved.isp.sharpen_radius);
        fresh.optics_pipeline = preview_pipeline(preview_optics_pipelines,
            vulkan_detail::preview_optics_specialization(fresh.optics),
            spirv::optics_preview, spirv::optics_preview_bytes, optics_pipeline);
        fresh.isp_pipeline = preview_pipeline(preview_isp_pipelines,
            vulkan_detail::preview_isp_specialization(fresh.isp),
            spirv::isp_preview, spirv::isp_preview_bytes, isp_pipeline);
        prepared_preview = std::move(fresh);
        ++preview_preparations;
        return prepared_preview;
    }

    static bool is_srgb_format(VkFormat format) noexcept {
        return format == VK_FORMAT_R8G8B8A8_SRGB ||
            format == VK_FORMAT_B8G8R8A8_SRGB ||
            format == VK_FORMAT_A8B8G8R8_SRGB_PACK32;
    }

    VkShaderModule create_shader_module(const uint32_t* words, size_t byte_count) const {
        VkShaderModuleCreateInfo module_info{};
        module_info.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
        module_info.codeSize = byte_count;
        module_info.pCode = words;
        VkShaderModule module = VK_NULL_HANDLE;
        require_vk(vkCreateShaderModule(device, &module_info, nullptr, &module),
                   "vkCreateShaderModule(presentation)");
        return module;
    }

    void destroy_swapchain() noexcept {
        if (device != VK_NULL_HANDLE) {
            for (VkFramebuffer framebuffer : swapchain_framebuffers) {
                if (framebuffer != VK_NULL_HANDLE) vkDestroyFramebuffer(device, framebuffer, nullptr);
            }
            if (presentation_pipeline != VK_NULL_HANDLE) {
                vkDestroyPipeline(device, presentation_pipeline, nullptr);
            }
            if (presentation_render_pass != VK_NULL_HANDLE) {
                vkDestroyRenderPass(device, presentation_render_pass, nullptr);
            }
            for (VkImageView view : swapchain_views) {
                if (view != VK_NULL_HANDLE) vkDestroyImageView(device, view, nullptr);
            }
            if (presentation_descriptor_pool != VK_NULL_HANDLE) {
                vkDestroyDescriptorPool(device, presentation_descriptor_pool, nullptr);
            }
            if (presentation_pipeline_layout != VK_NULL_HANDLE) {
                vkDestroyPipelineLayout(device, presentation_pipeline_layout, nullptr);
            }
            if (presentation_descriptor_layout != VK_NULL_HANDLE) {
                vkDestroyDescriptorSetLayout(device, presentation_descriptor_layout, nullptr);
            }
            if (swapchain != VK_NULL_HANDLE) vkDestroySwapchainKHR(device, swapchain, nullptr);
        }
        swapchain_framebuffers.clear();
        swapchain_views.clear();
        swapchain_images.clear();
        presentation_pipeline = VK_NULL_HANDLE;
        presentation_render_pass = VK_NULL_HANDLE;
        presentation_descriptor_pool = VK_NULL_HANDLE;
        presentation_descriptor_set = VK_NULL_HANDLE;
        presentation_pipeline_layout = VK_NULL_HANDLE;
        presentation_descriptor_layout = VK_NULL_HANDLE;
        swapchain = VK_NULL_HANDLE;
        swapchain_format = VK_FORMAT_UNDEFINED;
        swapchain_extent = {};
    }

    void destroy_presentation() noexcept {
        destroy_swapchain();
        if (device != VK_NULL_HANDLE && presentation_image_available != VK_NULL_HANDLE) {
            vkDestroySemaphore(device, presentation_image_available, nullptr);
        }
        if (device != VK_NULL_HANDLE && presentation_render_finished != VK_NULL_HANDLE) {
            vkDestroySemaphore(device, presentation_render_finished, nullptr);
        }
        if (instance != VK_NULL_HANDLE && output_surface != VK_NULL_HANDLE) {
            vkDestroySurfaceKHR(instance, output_surface, nullptr);
        }
        presentation_image_available = VK_NULL_HANDLE;
        presentation_render_finished = VK_NULL_HANDLE;
        output_surface = VK_NULL_HANDLE;
        output_window = nullptr;
    }

    VkSurfaceFormatKHR choose_surface_format(
        const std::vector<VkSurfaceFormatKHR>& formats) const {
        if (formats.size() == 1U && formats.front().format == VK_FORMAT_UNDEFINED) {
            return VkSurfaceFormatKHR{
                VK_FORMAT_R8G8B8A8_UNORM, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR};
        }
        constexpr std::array<VkFormat, 5> preferred{
            VK_FORMAT_R8G8B8A8_UNORM,
            VK_FORMAT_B8G8R8A8_UNORM,
            VK_FORMAT_A8B8G8R8_UNORM_PACK32,
            VK_FORMAT_R8G8B8A8_SRGB,
            VK_FORMAT_B8G8R8A8_SRGB,
        };
        for (VkFormat candidate : preferred) {
            const auto match = std::find_if(
                formats.begin(), formats.end(), [candidate](const auto& format) {
                    return format.format == candidate &&
                        format.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
                });
            if (match != formats.end()) return *match;
        }
        if (formats.empty()) throw std::runtime_error("Android surface exposes no Vulkan formats");
        return formats.front();
    }

    void create_presentation_pipeline() {
        VkDescriptorSetLayoutBinding binding{};
        binding.binding = 0U;
        binding.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        binding.descriptorCount = 1U;
        binding.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
        VkDescriptorSetLayoutCreateInfo descriptor_info{};
        descriptor_info.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
        descriptor_info.bindingCount = 1U;
        descriptor_info.pBindings = &binding;
        require_vk(vkCreateDescriptorSetLayout(
                       device, &descriptor_info, nullptr, &presentation_descriptor_layout),
                   "vkCreateDescriptorSetLayout(presentation)");

        VkPushConstantRange push_range{};
        push_range.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
        push_range.offset = 0U;
        push_range.size = sizeof(uint32_t) * 6U;
        VkPipelineLayoutCreateInfo layout_info{};
        layout_info.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
        layout_info.setLayoutCount = 1U;
        layout_info.pSetLayouts = &presentation_descriptor_layout;
        layout_info.pushConstantRangeCount = 1U;
        layout_info.pPushConstantRanges = &push_range;
        require_vk(vkCreatePipelineLayout(
                       device, &layout_info, nullptr, &presentation_pipeline_layout),
                   "vkCreatePipelineLayout(presentation)");

        VkDescriptorPoolSize pool_size{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1U};
        VkDescriptorPoolCreateInfo pool_info{};
        pool_info.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
        pool_info.maxSets = 1U;
        pool_info.poolSizeCount = 1U;
        pool_info.pPoolSizes = &pool_size;
        require_vk(vkCreateDescriptorPool(
                       device, &pool_info, nullptr, &presentation_descriptor_pool),
                   "vkCreateDescriptorPool(presentation)");
        VkDescriptorSetAllocateInfo set_info{};
        set_info.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        set_info.descriptorPool = presentation_descriptor_pool;
        set_info.descriptorSetCount = 1U;
        set_info.pSetLayouts = &presentation_descriptor_layout;
        require_vk(vkAllocateDescriptorSets(
                       device, &set_info, &presentation_descriptor_set),
                   "vkAllocateDescriptorSets(presentation)");

        VkAttachmentDescription attachment{};
        attachment.format = swapchain_format;
        attachment.samples = VK_SAMPLE_COUNT_1_BIT;
        attachment.loadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        attachment.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        attachment.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        attachment.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        attachment.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        attachment.finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
        VkAttachmentReference color_reference{0U, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
        VkSubpassDescription subpass{};
        subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
        subpass.colorAttachmentCount = 1U;
        subpass.pColorAttachments = &color_reference;
        VkSubpassDependency dependency{};
        dependency.srcSubpass = VK_SUBPASS_EXTERNAL;
        dependency.dstSubpass = 0U;
        dependency.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dependency.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dependency.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        VkRenderPassCreateInfo render_pass_info{};
        render_pass_info.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
        render_pass_info.attachmentCount = 1U;
        render_pass_info.pAttachments = &attachment;
        render_pass_info.subpassCount = 1U;
        render_pass_info.pSubpasses = &subpass;
        render_pass_info.dependencyCount = 1U;
        render_pass_info.pDependencies = &dependency;
        require_vk(vkCreateRenderPass(
                       device, &render_pass_info, nullptr, &presentation_render_pass),
                   "vkCreateRenderPass(presentation)");

        VkShaderModule vertex = create_shader_module(
            spirv::present_vert, spirv::present_vert_bytes);
        VkShaderModule fragment = VK_NULL_HANDLE;
        try {
            fragment = create_shader_module(spirv::present_frag, spirv::present_frag_bytes);
            const std::array<VkPipelineShaderStageCreateInfo, 2> stages{
                VkPipelineShaderStageCreateInfo{
                    VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, nullptr, 0U,
                    VK_SHADER_STAGE_VERTEX_BIT, vertex, "main", nullptr},
                VkPipelineShaderStageCreateInfo{
                    VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, nullptr, 0U,
                    VK_SHADER_STAGE_FRAGMENT_BIT, fragment, "main", nullptr},
            };
            VkPipelineVertexInputStateCreateInfo vertex_input{};
            vertex_input.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
            VkPipelineInputAssemblyStateCreateInfo assembly{};
            assembly.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
            assembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
            VkPipelineViewportStateCreateInfo viewport{};
            viewport.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
            viewport.viewportCount = 1U;
            viewport.scissorCount = 1U;
            VkPipelineRasterizationStateCreateInfo rasterization{};
            rasterization.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
            rasterization.polygonMode = VK_POLYGON_MODE_FILL;
            rasterization.cullMode = VK_CULL_MODE_NONE;
            rasterization.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
            rasterization.lineWidth = 1.0F;
            VkPipelineMultisampleStateCreateInfo multisample{};
            multisample.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
            multisample.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
            VkPipelineColorBlendAttachmentState blend_attachment{};
            blend_attachment.colorWriteMask =
                VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT |
                VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
            VkPipelineColorBlendStateCreateInfo blend{};
            blend.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
            blend.attachmentCount = 1U;
            blend.pAttachments = &blend_attachment;
            const std::array<VkDynamicState, 2> dynamic_states{
                VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
            VkPipelineDynamicStateCreateInfo dynamic{};
            dynamic.sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO;
            dynamic.dynamicStateCount = static_cast<uint32_t>(dynamic_states.size());
            dynamic.pDynamicStates = dynamic_states.data();
            VkGraphicsPipelineCreateInfo pipeline_info{};
            pipeline_info.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
            pipeline_info.stageCount = static_cast<uint32_t>(stages.size());
            pipeline_info.pStages = stages.data();
            pipeline_info.pVertexInputState = &vertex_input;
            pipeline_info.pInputAssemblyState = &assembly;
            pipeline_info.pViewportState = &viewport;
            pipeline_info.pRasterizationState = &rasterization;
            pipeline_info.pMultisampleState = &multisample;
            pipeline_info.pColorBlendState = &blend;
            pipeline_info.pDynamicState = &dynamic;
            pipeline_info.layout = presentation_pipeline_layout;
            pipeline_info.renderPass = presentation_render_pass;
            pipeline_info.subpass = 0U;
            require_vk(vkCreateGraphicsPipelines(
                           device, VK_NULL_HANDLE, 1U, &pipeline_info, nullptr,
                           &presentation_pipeline),
                       "vkCreateGraphicsPipelines(presentation)");
        } catch (...) {
            if (fragment != VK_NULL_HANDLE) vkDestroyShaderModule(device, fragment, nullptr);
            vkDestroyShaderModule(device, vertex, nullptr);
            throw;
        }
        vkDestroyShaderModule(device, fragment, nullptr);
        vkDestroyShaderModule(device, vertex, nullptr);
    }

    void create_swapchain(bool count_recreation) {
        if (output_surface == VK_NULL_HANDLE || output_window == nullptr) {
            throw std::runtime_error("Android output surface is not configured");
        }
        vkDeviceWaitIdle(device);
        destroy_swapchain();

        VkSurfaceCapabilitiesKHR capabilities{};
        require_vk(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(
                       physical_device, output_surface, &capabilities),
                   "vkGetPhysicalDeviceSurfaceCapabilitiesKHR");
        uint32_t format_count = 0U;
        require_vk(vkGetPhysicalDeviceSurfaceFormatsKHR(
                       physical_device, output_surface, &format_count, nullptr),
                   "vkGetPhysicalDeviceSurfaceFormatsKHR(count)");
        std::vector<VkSurfaceFormatKHR> formats(format_count);
        require_vk(vkGetPhysicalDeviceSurfaceFormatsKHR(
                       physical_device, output_surface, &format_count, formats.data()),
                   "vkGetPhysicalDeviceSurfaceFormatsKHR");
        const VkSurfaceFormatKHR selected_format = choose_surface_format(formats);
        swapchain_format = selected_format.format;
        presentation_attachment_is_srgb = is_srgb_format(swapchain_format);

        if (capabilities.currentExtent.width != UINT32_MAX) {
            swapchain_extent = capabilities.currentExtent;
        } else {
            const uint32_t window_width = static_cast<uint32_t>(
                std::max(ANativeWindow_getWidth(output_window), 1));
            const uint32_t window_height = static_cast<uint32_t>(
                std::max(ANativeWindow_getHeight(output_window), 1));
            swapchain_extent.width = std::clamp(
                window_width, capabilities.minImageExtent.width,
                capabilities.maxImageExtent.width);
            swapchain_extent.height = std::clamp(
                window_height, capabilities.minImageExtent.height,
                capabilities.maxImageExtent.height);
        }
        uint32_t image_count = capabilities.minImageCount + 1U;
        if (capabilities.maxImageCount > 0U) {
            image_count = std::min(image_count, capabilities.maxImageCount);
        }
        constexpr std::array<VkCompositeAlphaFlagBitsKHR, 4> alpha_modes{
            VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR,
            VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR,
            VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR,
            VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR,
        };
        VkCompositeAlphaFlagBitsKHR composite_alpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
        for (VkCompositeAlphaFlagBitsKHR candidate : alpha_modes) {
            if ((capabilities.supportedCompositeAlpha & candidate) != 0U) {
                composite_alpha = candidate;
                break;
            }
        }
        VkSwapchainCreateInfoKHR swapchain_info{};
        swapchain_info.sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
        swapchain_info.surface = output_surface;
        swapchain_info.minImageCount = image_count;
        swapchain_info.imageFormat = selected_format.format;
        swapchain_info.imageColorSpace = selected_format.colorSpace;
        swapchain_info.imageExtent = swapchain_extent;
        swapchain_info.imageArrayLayers = 1U;
        swapchain_info.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
        swapchain_info.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
        swapchain_info.preTransform = capabilities.currentTransform;
        swapchain_info.compositeAlpha = composite_alpha;
        swapchain_info.presentMode = VK_PRESENT_MODE_FIFO_KHR;
        swapchain_info.clipped = VK_TRUE;
        require_vk(vkCreateSwapchainKHR(device, &swapchain_info, nullptr, &swapchain),
                   "vkCreateSwapchainKHR");
        require_vk(vkGetSwapchainImagesKHR(device, swapchain, &image_count, nullptr),
                   "vkGetSwapchainImagesKHR(count)");
        swapchain_images.resize(image_count);
        require_vk(vkGetSwapchainImagesKHR(
                       device, swapchain, &image_count, swapchain_images.data()),
                   "vkGetSwapchainImagesKHR");
        swapchain_views.reserve(swapchain_images.size());
        for (VkImage image : swapchain_images) {
            VkImageViewCreateInfo view_info{};
            view_info.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
            view_info.image = image;
            view_info.viewType = VK_IMAGE_VIEW_TYPE_2D;
            view_info.format = swapchain_format;
            view_info.subresourceRange = {
                VK_IMAGE_ASPECT_COLOR_BIT, 0U, 1U, 0U, 1U};
            VkImageView view = VK_NULL_HANDLE;
            require_vk(vkCreateImageView(device, &view_info, nullptr, &view),
                       "vkCreateImageView(presentation)");
            swapchain_views.push_back(view);
        }
        create_presentation_pipeline();
        swapchain_framebuffers.reserve(swapchain_views.size());
        for (VkImageView view : swapchain_views) {
            VkFramebufferCreateInfo framebuffer_info{};
            framebuffer_info.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
            framebuffer_info.renderPass = presentation_render_pass;
            framebuffer_info.attachmentCount = 1U;
            framebuffer_info.pAttachments = &view;
            framebuffer_info.width = swapchain_extent.width;
            framebuffer_info.height = swapchain_extent.height;
            framebuffer_info.layers = 1U;
            VkFramebuffer framebuffer = VK_NULL_HANDLE;
            require_vk(vkCreateFramebuffer(
                           device, &framebuffer_info, nullptr, &framebuffer),
                       "vkCreateFramebuffer(presentation)");
            swapchain_framebuffers.push_back(framebuffer);
        }
        if (count_recreation) ++swapchain_recreates;
    }

    void set_output_window(ANativeWindow* window, uint32_t rotation_degrees) {
        if (!ready) throw std::runtime_error(error);
        if (window == nullptr) throw std::invalid_argument("Android output window is null");
        if (rotation_degrees != 0U && rotation_degrees != 90U &&
            rotation_degrees != 180U && rotation_degrees != 270U) {
            throw std::invalid_argument("output rotation must be 0, 90, 180 or 270 degrees");
        }
        if (!swapchain_supported || (queue_flags & VK_QUEUE_GRAPHICS_BIT) == 0U) {
            throw std::runtime_error("Vulkan device cannot present the processed preview");
        }
        vkDeviceWaitIdle(device);
        destroy_presentation();
        output_window = window;
        output_rotation_degrees = rotation_degrees;
        try {
            VkAndroidSurfaceCreateInfoKHR surface_info{};
            surface_info.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
            surface_info.window = window;
            require_vk(vkCreateAndroidSurfaceKHR(
                           instance, &surface_info, nullptr, &output_surface),
                       "vkCreateAndroidSurfaceKHR");
            VkBool32 present_supported = VK_FALSE;
            require_vk(vkGetPhysicalDeviceSurfaceSupportKHR(
                           physical_device, queue_family, output_surface, &present_supported),
                       "vkGetPhysicalDeviceSurfaceSupportKHR");
            if (present_supported != VK_TRUE) {
                throw std::runtime_error("selected Vulkan queue cannot present to Android surface");
            }
            VkSemaphoreCreateInfo semaphore_info{};
            semaphore_info.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
            require_vk(vkCreateSemaphore(
                           device, &semaphore_info, nullptr, &presentation_image_available),
                       "vkCreateSemaphore(presentation image available)");
            require_vk(vkCreateSemaphore(
                           device, &semaphore_info, nullptr, &presentation_render_finished),
                       "vkCreateSemaphore(presentation render finished)");
            create_swapchain(false);
        } catch (...) {
            destroy_presentation();
            throw;
        }
    }

    void destroy_ahb_pipeline() noexcept {
        if (device != VK_NULL_HANDLE && ahb_pipeline != VK_NULL_HANDLE) {
            vkDestroyPipeline(device, ahb_pipeline, nullptr);
        }
        if (device != VK_NULL_HANDLE && ahb_pipeline_layout != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(device, ahb_pipeline_layout, nullptr);
        }
        if (device != VK_NULL_HANDLE && ahb_descriptor_pool != VK_NULL_HANDLE) {
            vkDestroyDescriptorPool(device, ahb_descriptor_pool, nullptr);
        }
        if (device != VK_NULL_HANDLE && ahb_descriptor_layout != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(device, ahb_descriptor_layout, nullptr);
        }
        if (device != VK_NULL_HANDLE && ahb_sampler != VK_NULL_HANDLE) {
            vkDestroySampler(device, ahb_sampler, nullptr);
        }
        if (device != VK_NULL_HANDLE && ahb_conversion != VK_NULL_HANDLE &&
            destroy_ycbcr_conversion != nullptr) {
            destroy_ycbcr_conversion(device, ahb_conversion, nullptr);
        }
        ahb_pipeline = VK_NULL_HANDLE;
        ahb_pipeline_layout = VK_NULL_HANDLE;
        ahb_descriptor_pool = VK_NULL_HANDLE;
        ahb_descriptor_layout = VK_NULL_HANDLE;
        ahb_descriptor_set = VK_NULL_HANDLE;
        ahb_sampler = VK_NULL_HANDLE;
        ahb_conversion = VK_NULL_HANDLE;
        ahb_external_format = 0U;
        ahb_format = VK_FORMAT_UNDEFINED;
    }

    static bool is_ycbcr_format(VkFormat format) noexcept {
        switch (format) {
            case VK_FORMAT_G8_B8_R8_3PLANE_420_UNORM:
            case VK_FORMAT_G8_B8R8_2PLANE_420_UNORM:
            case VK_FORMAT_G10X6_B10X6_R10X6_3PLANE_420_UNORM_3PACK16:
            case VK_FORMAT_G10X6_B10X6R10X6_2PLANE_420_UNORM_3PACK16:
            case VK_FORMAT_G12X4_B12X4_R12X4_3PLANE_420_UNORM_3PACK16:
            case VK_FORMAT_G12X4_B12X4R12X4_2PLANE_420_UNORM_3PACK16:
            case VK_FORMAT_G16_B16_R16_3PLANE_420_UNORM:
            case VK_FORMAT_G16_B16R16_2PLANE_420_UNORM:
                return true;
            default:
                return false;
        }
    }

    void ensure_ahb_pipeline(const VkAndroidHardwareBufferFormatPropertiesANDROID& format_properties) {
        if (ahb_pipeline != VK_NULL_HANDLE && ahb_format == format_properties.format &&
            ahb_external_format == format_properties.externalFormat) return;
        ahb_cache.clear();
        uncached_ahb.destroy();
        destroy_ahb_pipeline();
        try {
            if ((format_properties.formatFeatures & VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT) == 0U) {
                throw std::runtime_error("AHardwareBuffer format is not sampleable by Vulkan");
            }
            const bool external_format = format_properties.format == VK_FORMAT_UNDEFINED;
            const bool needs_conversion = external_format || is_ycbcr_format(format_properties.format);
            VkExternalFormatANDROID external{};
            external.sType = VK_STRUCTURE_TYPE_EXTERNAL_FORMAT_ANDROID;
            external.externalFormat = format_properties.externalFormat;

            VkSamplerYcbcrConversionInfo conversion_info{};
            conversion_info.sType = VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_INFO;
            if (needs_conversion) {
                VkSamplerYcbcrConversionCreateInfo conversion_create{};
                conversion_create.sType = VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_CREATE_INFO;
                conversion_create.pNext = external_format ? &external : nullptr;
                conversion_create.format = external_format ? VK_FORMAT_UNDEFINED : format_properties.format;
                conversion_create.ycbcrModel = format_properties.suggestedYcbcrModel;
                conversion_create.ycbcrRange = format_properties.suggestedYcbcrRange;
                conversion_create.components = format_properties.samplerYcbcrConversionComponents;
                conversion_create.xChromaOffset = format_properties.suggestedXChromaOffset;
                conversion_create.yChromaOffset = format_properties.suggestedYChromaOffset;
                conversion_create.chromaFilter = VK_FILTER_NEAREST;
                conversion_create.forceExplicitReconstruction = VK_FALSE;
                require_vk(create_ycbcr_conversion(
                               device, &conversion_create, nullptr, &ahb_conversion),
                           "vkCreateSamplerYcbcrConversion(AHardwareBuffer)");
                conversion_info.conversion = ahb_conversion;
            }

            VkSamplerCreateInfo sampler_info{};
            sampler_info.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
            sampler_info.pNext = needs_conversion ? &conversion_info : nullptr;
            sampler_info.magFilter = VK_FILTER_NEAREST;
            sampler_info.minFilter = VK_FILTER_NEAREST;
            sampler_info.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
            sampler_info.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
            sampler_info.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
            sampler_info.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
            sampler_info.maxLod = 0.0F;
            require_vk(vkCreateSampler(device, &sampler_info, nullptr, &ahb_sampler),
                       "vkCreateSampler(AHardwareBuffer)");

            std::array<VkDescriptorSetLayoutBinding, 3> bindings{};
            bindings[0].binding = 0U;
            bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            bindings[0].descriptorCount = 1U;
            bindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
            bindings[0].pImmutableSamplers = &ahb_sampler;
            for (uint32_t index = 1U; index < bindings.size(); ++index) {
                bindings[index].binding = index;
                bindings[index].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
                bindings[index].descriptorCount = 1U;
                bindings[index].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
            }
            VkDescriptorSetLayoutCreateInfo descriptor_info{};
            descriptor_info.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
            descriptor_info.bindingCount = static_cast<uint32_t>(bindings.size());
            descriptor_info.pBindings = bindings.data();
            require_vk(vkCreateDescriptorSetLayout(
                           device, &descriptor_info, nullptr, &ahb_descriptor_layout),
                       "vkCreateDescriptorSetLayout(AHardwareBuffer)");

            VkPipelineLayoutCreateInfo layout_info{};
            layout_info.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
            layout_info.setLayoutCount = 1U;
            layout_info.pSetLayouts = &ahb_descriptor_layout;
            require_vk(vkCreatePipelineLayout(device, &layout_info, nullptr, &ahb_pipeline_layout),
                       "vkCreatePipelineLayout(AHardwareBuffer)");
            ahb_pipeline = create_pipeline(
                spirv::normalize_ahb, spirv::normalize_ahb_bytes, ahb_pipeline_layout);

            const std::array<VkDescriptorPoolSize, 2> pool_sizes{
                VkDescriptorPoolSize{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1U},
                VkDescriptorPoolSize{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 2U},
            };
            VkDescriptorPoolCreateInfo pool_info{};
            pool_info.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
            pool_info.maxSets = 1U;
            pool_info.poolSizeCount = static_cast<uint32_t>(pool_sizes.size());
            pool_info.pPoolSizes = pool_sizes.data();
            require_vk(vkCreateDescriptorPool(device, &pool_info, nullptr, &ahb_descriptor_pool),
                       "vkCreateDescriptorPool(AHardwareBuffer)");
            VkDescriptorSetAllocateInfo set_info{};
            set_info.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
            set_info.descriptorPool = ahb_descriptor_pool;
            set_info.descriptorSetCount = 1U;
            set_info.pSetLayouts = &ahb_descriptor_layout;
            require_vk(vkAllocateDescriptorSets(device, &set_info, &ahb_descriptor_set),
                       "vkAllocateDescriptorSets(AHardwareBuffer)");
            ahb_external_format = format_properties.externalFormat;
            ahb_format = format_properties.format;
        } catch (...) {
            destroy_ahb_pipeline();
            throw;
        }
    }

    ImportedAhbImage import_ahardware_buffer(
        AHardwareBuffer* buffer, uint32_t expected_width, uint32_t expected_height) {
        if (!ahb_import_supported) {
            throw std::runtime_error("Vulkan AHardwareBuffer import is unavailable");
        }
        AHardwareBuffer_Desc description{};
        AHardwareBuffer_describe(buffer, &description);
        if (description.width != expected_width || description.height != expected_height ||
            description.layers != 1U) {
            throw std::invalid_argument("AHardwareBuffer dimensions or layer count do not match the frame");
        }
        if ((description.usage & AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE) == 0U) {
            throw std::invalid_argument("AHardwareBuffer lacks GPU_SAMPLED_IMAGE usage");
        }
        if ((description.usage & AHARDWAREBUFFER_USAGE_PROTECTED_CONTENT) != 0U) {
            throw std::invalid_argument("protected AHardwareBuffer input is unsupported");
        }

        VkAndroidHardwareBufferFormatPropertiesANDROID format_properties{};
        format_properties.sType =
            VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID;
        VkAndroidHardwareBufferPropertiesANDROID properties{};
        properties.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
        properties.pNext = &format_properties;
        require_vk(get_ahb_properties(
                       device, buffer, &properties),
                   "vkGetAndroidHardwareBufferPropertiesANDROID");
        ensure_ahb_pipeline(format_properties);

        ImportedAhbImage imported;
        imported.device = device;
        imported.allocation_size = properties.allocationSize;
        const bool external_format = format_properties.format == VK_FORMAT_UNDEFINED;
        VkExternalFormatANDROID external{};
        external.sType = VK_STRUCTURE_TYPE_EXTERNAL_FORMAT_ANDROID;
        external.externalFormat = format_properties.externalFormat;
        VkExternalMemoryImageCreateInfo external_memory{};
        external_memory.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
        external_memory.pNext = external_format ? &external : nullptr;
        external_memory.handleTypes =
            VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
        VkImageCreateInfo image_info{};
        image_info.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
        image_info.pNext = &external_memory;
        image_info.imageType = VK_IMAGE_TYPE_2D;
        image_info.format = external_format ? VK_FORMAT_UNDEFINED : format_properties.format;
        image_info.extent = {description.width, description.height, 1U};
        image_info.mipLevels = 1U;
        image_info.arrayLayers = 1U;
        image_info.samples = VK_SAMPLE_COUNT_1_BIT;
        image_info.tiling = VK_IMAGE_TILING_OPTIMAL;
        image_info.usage = VK_IMAGE_USAGE_SAMPLED_BIT;
        image_info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        image_info.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        require_vk(vkCreateImage(device, &image_info, nullptr, &imported.image),
                   "vkCreateImage(AHardwareBuffer)");

        if (properties.memoryTypeBits == 0U) {
            throw std::runtime_error("AHardwareBuffer has no Vulkan-compatible memory type");
        }
        uint32_t memory_type = 0U;
        while ((properties.memoryTypeBits & (1U << memory_type)) == 0U) ++memory_type;
        VkImportAndroidHardwareBufferInfoANDROID import_info{};
        import_info.sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
        import_info.buffer = buffer;
        VkMemoryDedicatedAllocateInfo dedicated_info{};
        dedicated_info.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
        dedicated_info.pNext = &import_info;
        dedicated_info.image = imported.image;
        VkMemoryAllocateInfo allocation{};
        allocation.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        allocation.pNext = &dedicated_info;
        allocation.allocationSize = properties.allocationSize;
        allocation.memoryTypeIndex = memory_type;
        require_vk(vkAllocateMemory(device, &allocation, nullptr, &imported.memory),
                   "vkAllocateMemory(AHardwareBuffer)");
        require_vk(vkBindImageMemory(device, imported.image, imported.memory, 0U),
                   "vkBindImageMemory(AHardwareBuffer)");

        VkSamplerYcbcrConversionInfo conversion_info{};
        conversion_info.sType = VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_INFO;
        conversion_info.conversion = ahb_conversion;
        VkImageViewCreateInfo view_info{};
        view_info.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        view_info.pNext = ahb_conversion == VK_NULL_HANDLE ? nullptr : &conversion_info;
        view_info.image = imported.image;
        view_info.viewType = VK_IMAGE_VIEW_TYPE_2D;
        view_info.format = external_format ? VK_FORMAT_UNDEFINED : format_properties.format;
        view_info.components = {
            VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY,
            VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY};
        view_info.subresourceRange = {
            VK_IMAGE_ASPECT_COLOR_BIT, 0U, 1U, 0U, 1U};
        require_vk(vkCreateImageView(device, &view_info, nullptr, &imported.view),
                   "vkCreateImageView(AHardwareBuffer)");
        return imported;
    }

    ImportedAhbImage& cached_ahardware_buffer(
        AHardwareBuffer* buffer, uint32_t width, uint32_t height) {
        ++ahb_use_clock;
        const uint64_t identity = ahardware_buffer_identity(buffer);
        for (auto& entry : ahb_cache) {
            if (entry.identity == identity && entry.width == width && entry.height == height) {
                entry.last_use = ahb_use_clock;
                ++ahb_cache_hits;
                return entry.imported;
            }
        }
        ++ahb_cache_misses;
        ImportedAhbImage imported = import_ahardware_buffer(buffer, width, height);
        ++ahb_imports;
        // Accommodate camera pools larger than eight without retaining unlimited
        // gralloc buffers. Count actual Vulkan-reported allocation bytes, not RGB estimates.
        constexpr size_t maximum_entries = 32U;
        constexpr VkDeviceSize maximum_bytes = 128U * 1024U * 1024U;
        if (imported.allocation_size > maximum_bytes) {
            uncached_ahb = std::move(imported);
            return uncached_ahb;
        }
        while (!ahb_cache.empty() && (ahb_cache.size() >= maximum_entries ||
               cached_ahb_bytes() > maximum_bytes - imported.allocation_size)) {
            const auto oldest = std::min_element(
                ahb_cache.begin(), ahb_cache.end(), [](const auto& left, const auto& right) {
                    return left.last_use < right.last_use;
                });
            ahb_cache.erase(oldest);
            ++ahb_cache_evictions;
        }
        ahb_cache.emplace_back(
            buffer, identity, std::move(imported), width, height, ahb_use_clock);
        return ahb_cache.back().imported;
    }

    [[nodiscard]] VkDeviceSize cached_ahb_bytes() const noexcept {
        VkDeviceSize bytes = 0U;
        for (const auto& entry : ahb_cache) bytes += entry.imported.allocation_size;
        return bytes;
    }

    [[nodiscard]] uint64_t ahardware_buffer_identity(AHardwareBuffer* buffer) noexcept {
        uint64_t identity = 0U;
        if (get_ahb_id != nullptr && get_ahb_id(buffer, &identity) == 0) {
            ++ahb_stable_ids;
            return identity;
        }
        ++ahb_pointer_ids;
        return static_cast<uint64_t>(reinterpret_cast<uintptr_t>(buffer));
    }

    void forget_ahardware_buffer(AHardwareBuffer* buffer) noexcept {
        if (buffer == nullptr) {
            ahb_cache_removals += ahb_cache.size();
            ahb_cache.clear();
            uncached_ahb.destroy();
            return;
        }
        const uint64_t identity = ahardware_buffer_identity(buffer);
        ahb_cache_removals += std::erase_if(ahb_cache, [identity](const auto& entry) {
            return entry.identity == identity;
        });
    }

    VkSemaphore import_acquire_fence(int acquire_fence_fd) const {
        if (acquire_fence_fd < 0) return VK_NULL_HANDLE;
        if (!external_semaphore_fd_supported) {
            throw std::runtime_error("Vulkan sync-fd import is unavailable");
        }
        const int duplicated_fd = dup(acquire_fence_fd);
        if (duplicated_fd < 0) throw std::runtime_error("failed to duplicate AHardwareBuffer acquire fence");
        VkSemaphore semaphore = VK_NULL_HANDLE;
        VkSemaphoreCreateInfo semaphore_info{};
        semaphore_info.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
        VkResult result = vkCreateSemaphore(device, &semaphore_info, nullptr, &semaphore);
        if (result != VK_SUCCESS) {
            close(duplicated_fd);
            require_vk(result, "vkCreateSemaphore(AHardwareBuffer acquire fence)");
        }
        VkImportSemaphoreFdInfoKHR import_info{};
        import_info.sType = VK_STRUCTURE_TYPE_IMPORT_SEMAPHORE_FD_INFO_KHR;
        import_info.semaphore = semaphore;
        import_info.flags = VK_SEMAPHORE_IMPORT_TEMPORARY_BIT;
        import_info.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
        import_info.fd = duplicated_fd;
        result = import_semaphore_fd(device, &import_info);
        if (result != VK_SUCCESS) {
            close(duplicated_fd);
            vkDestroySemaphore(device, semaphore, nullptr);
            require_vk(result, "vkImportSemaphoreFdKHR(AHardwareBuffer acquire fence)");
        }
        return semaphore;
    }

    uint32_t host_memory_type(uint32_t bits) const {
        VkPhysicalDeviceMemoryProperties properties{};
        vkGetPhysicalDeviceMemoryProperties(physical_device, &properties);
        const VkMemoryPropertyFlags required = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
        for (uint32_t index = 0U; index < properties.memoryTypeCount; ++index) {
            if ((bits & (1U << index)) != 0U &&
                (properties.memoryTypes[index].propertyFlags & required) == required) return index;
        }
        throw std::runtime_error("Vulkan device lacks host-visible coherent storage memory");
    }

    Buffer create_buffer(VkDeviceSize size) const {
        Buffer result;
        result.device = device;
        result.size = std::max<VkDeviceSize>(size, 4U);
        VkBufferCreateInfo buffer_info{};
        buffer_info.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        buffer_info.size = result.size;
        buffer_info.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        buffer_info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        require_vk(vkCreateBuffer(device, &buffer_info, nullptr, &result.buffer), "vkCreateBuffer");
        VkMemoryRequirements requirements{};
        vkGetBufferMemoryRequirements(device, result.buffer, &requirements);
        VkMemoryAllocateInfo allocation{};
        allocation.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        allocation.allocationSize = requirements.size;
        allocation.memoryTypeIndex = host_memory_type(requirements.memoryTypeBits);
        require_vk(vkAllocateMemory(device, &allocation, nullptr, &result.memory), "vkAllocateMemory");
        require_vk(vkBindBufferMemory(device, result.buffer, result.memory, 0U), "vkBindBufferMemory");
        require_vk(vkMapMemory(device, result.memory, 0U, result.size, 0U, &result.mapped), "vkMapMemory");
        /* Some old Android Emulator gfxstream builds have returned VK_SUCCESS with an
           unmapped guest address. Validate before the first upload so AUTO can report a
           backend failure instead of dereferencing a bad driver pointer. */
        const long page_size = sysconf(_SC_PAGESIZE);
        if (result.mapped == nullptr || page_size <= 0) {
            throw std::runtime_error("Vulkan returned an invalid mapped storage buffer");
        }
        const uintptr_t address = reinterpret_cast<uintptr_t>(result.mapped);
        void* page = reinterpret_cast<void*>(address & ~static_cast<uintptr_t>(page_size - 1));
        unsigned char residency = 0U;
        if (mincore(page, static_cast<size_t>(page_size), &residency) != 0) {
            throw std::runtime_error("Vulkan driver returned a non-resident mapped storage buffer");
        }
        return result;
    }

    bool ensure_buffer(Buffer& buffer, VkDeviceSize required_size) {
        required_size = std::max<VkDeviceSize>(required_size, 4U);
        if (buffer.buffer != VK_NULL_HANDLE && buffer.size >= required_size &&
            buffer.size / required_size <= 4U) return false;
        buffer.destroy();
        buffer = create_buffer(required_size);
        ++resource_allocations;
        return true;
    }

    void update_descriptor_set(VkDescriptorSet descriptor_set, Buffer& input,
                               Buffer& output, Buffer& aux, Buffer& parameters) const {
        std::array<VkDescriptorBufferInfo, 4> infos{
            VkDescriptorBufferInfo{input.buffer, 0U, input.size},
            VkDescriptorBufferInfo{output.buffer, 0U, output.size},
            VkDescriptorBufferInfo{aux.buffer, 0U, aux.size},
            VkDescriptorBufferInfo{parameters.buffer, 0U, parameters.size},
        };
        std::array<VkWriteDescriptorSet, 4> writes{};
        for (uint32_t index = 0U; index < writes.size(); ++index) {
            writes[index].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            writes[index].dstSet = descriptor_set;
            writes[index].dstBinding = index;
            writes[index].descriptorCount = 1U;
            writes[index].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            writes[index].pBufferInfo = &infos[index];
        }
        vkUpdateDescriptorSets(device, static_cast<uint32_t>(writes.size()), writes.data(), 0U, nullptr);
    }

    void ensure_frame_resources(VkDeviceSize rgb_bytes, VkDeviceSize raw_bytes,
                                VkDeviceSize optics_parameter_bytes,
                                VkDeviceSize sensor_parameter_bytes,
                                VkDeviceSize isp_parameter_bytes,
                                RenderResourceMode resource_mode,
                                VkDeviceSize ahb_parameter_bytes = 0U) {
        bool descriptors_changed = false;
        descriptors_changed |= ensure_buffer(scene_buffer, rgb_bytes);
        descriptors_changed |= ensure_buffer(optics_buffer, rgb_bytes);
        descriptors_changed |= ensure_buffer(sensor_buffer, raw_bytes);
        descriptors_changed |= ensure_buffer(optics_parameter_buffer, optics_parameter_bytes);
        descriptors_changed |= ensure_buffer(sensor_parameter_buffer, sensor_parameter_bytes);
        descriptors_changed |= ensure_buffer(isp_parameter_buffer, isp_parameter_bytes);
        if (resource_mode != RenderResourceMode::Production) {
            descriptors_changed |= ensure_buffer(stage_buffer, rgb_bytes);
        } else if (stage_buffer.buffer != VK_NULL_HANDLE) {
            stage_buffer.destroy();
            descriptors_changed = true;
        }
        if (resource_mode == RenderResourceMode::PreserveSceneAndOptics) {
            descriptors_changed |= ensure_buffer(output_buffer, rgb_bytes);
        } else if (output_buffer.buffer != VK_NULL_HANDLE) {
            output_buffer.destroy();
            descriptors_changed = true;
        }
        if (ahb_parameter_bytes > 0U) {
            descriptors_changed |= ensure_buffer(ahb_parameter_buffer, ahb_parameter_bytes);
        }
        if (descriptor_resource_mode != resource_mode) {
            descriptor_resource_mode = resource_mode;
            descriptors_changed = true;
        }
        if (!descriptors_changed) return;

        update_descriptor_set(descriptor_sets[0], scene_buffer, optics_buffer,
                              sensor_buffer, optics_parameter_buffer);
        update_descriptor_set(descriptor_sets[1], optics_buffer, sensor_buffer,
                              scene_buffer, sensor_parameter_buffer);
        /* In production, ISP overwrites the no-longer-needed scene and optics
           buffers. Debug stage capture allocates only the images it must preserve. */
        Buffer& final_output = resource_mode == RenderResourceMode::PreserveSceneAndOptics
            ? output_buffer
            : scene_buffer;
        Buffer& isp_linear = resource_mode == RenderResourceMode::Production
            ? optics_buffer
            : stage_buffer;
        update_descriptor_set(descriptor_sets[2], sensor_buffer, final_output,
                              isp_linear, isp_parameter_buffer);
    }

    static void upload(Buffer& buffer, const std::vector<float>& values) {
        const size_t bytes = values.size() * sizeof(float);
        if (bytes > buffer.size) throw std::runtime_error("Vulkan persistent buffer overflow");
        std::memcpy(buffer.mapped, values.data(), bytes);
    }

    static VkBufferMemoryBarrier buffer_barrier(
        const Buffer& buffer, VkAccessFlags source_access, VkAccessFlags destination_access) {
        VkBufferMemoryBarrier barrier{};
        barrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        barrier.srcAccessMask = source_access;
        barrier.dstAccessMask = destination_access;
        barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.buffer = buffer.buffer;
        barrier.offset = 0U;
        barrier.size = buffer.size;
        return barrier;
    }

    void bind_and_dispatch(VkPipeline pipeline, VkDescriptorSet descriptor_set,
                           uint32_t width, uint32_t height) const {
        vkCmdBindPipeline(command_buffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
        vkCmdBindDescriptorSets(command_buffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline_layout,
                                0U, 1U, &descriptor_set, 0U, nullptr);
        vkCmdDispatch(command_buffer, (width + 15U) / 16U, (height + 15U) / 16U, 1U);
    }

    void update_presentation_descriptor(const Buffer& final_output) const {
        const VkDescriptorBufferInfo buffer_info{
            final_output.buffer, 0U, final_output.size};
        VkWriteDescriptorSet write{};
        write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        write.dstSet = presentation_descriptor_set;
        write.dstBinding = 0U;
        write.descriptorCount = 1U;
        write.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        write.pBufferInfo = &buffer_info;
        vkUpdateDescriptorSets(device, 1U, &write, 0U, nullptr);
    }

    void record_presentation(uint32_t image_index, const Buffer& final_output,
                             uint32_t source_width, uint32_t source_height) const {
        const VkBufferMemoryBarrier output_barrier = buffer_barrier(
            final_output, VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT);
        vkCmdPipelineBarrier(command_buffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0U,
                             0U, nullptr, 1U, &output_barrier, 0U, nullptr);

        VkRenderPassBeginInfo render_pass{};
        render_pass.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
        render_pass.renderPass = presentation_render_pass;
        render_pass.framebuffer = swapchain_framebuffers.at(image_index);
        render_pass.renderArea = {{0, 0}, swapchain_extent};
        vkCmdBeginRenderPass(command_buffer, &render_pass, VK_SUBPASS_CONTENTS_INLINE);
        const VkViewport viewport{
            0.0F, 0.0F,
            static_cast<float>(swapchain_extent.width),
            static_cast<float>(swapchain_extent.height),
            0.0F, 1.0F};
        const VkRect2D scissor{{0, 0}, swapchain_extent};
        vkCmdSetViewport(command_buffer, 0U, 1U, &viewport);
        vkCmdSetScissor(command_buffer, 0U, 1U, &scissor);
        vkCmdBindPipeline(
            command_buffer, VK_PIPELINE_BIND_POINT_GRAPHICS, presentation_pipeline);
        vkCmdBindDescriptorSets(
            command_buffer, VK_PIPELINE_BIND_POINT_GRAPHICS,
            presentation_pipeline_layout, 0U, 1U,
            &presentation_descriptor_set, 0U, nullptr);
        const std::array<uint32_t, 6> presentation_parameters{
            source_width,
            source_height,
            swapchain_extent.width,
            swapchain_extent.height,
            output_rotation_degrees,
            presentation_attachment_is_srgb ? 1U : 0U,
        };
        vkCmdPushConstants(
            command_buffer, presentation_pipeline_layout,
            VK_SHADER_STAGE_FRAGMENT_BIT, 0U,
            static_cast<uint32_t>(presentation_parameters.size() * sizeof(uint32_t)),
            presentation_parameters.data());
        vkCmdDraw(command_buffer, 3U, 1U, 0U, 0U);
        vkCmdEndRenderPass(command_buffer);
    }

    void submit_render_graph(uint32_t width, uint32_t height,
                             VkImage ahb_image = VK_NULL_HANDLE,
                             VkSemaphore wait_semaphore = VK_NULL_HANDLE,
                             RenderResourceMode resource_mode = RenderResourceMode::Production,
                             bool host_readback = true,
                             const PreparedPreview* preview = nullptr,
                             bool sample = false) {
        sample = sample && preview != nullptr;
        const auto stamp = [sample] { return sample ? Clock::now() : Clock::time_point{}; };
        const auto acquire_start = stamp();
        const bool gpu_sample = sample && preview_timestamp_pool != VK_NULL_HANDLE;
        const bool has_ahb_input = ahb_image != VK_NULL_HANDLE;
        const bool has_presentation = swapchain != VK_NULL_HANDLE;
        const Buffer& final_output_buffer =
            resource_mode == RenderResourceMode::PreserveSceneAndOptics
            ? output_buffer
            : scene_buffer;
        uint32_t presentation_image_index = 0U;
        if (has_presentation) {
            VkResult acquire_result = vkAcquireNextImageKHR(
                device, swapchain, UINT64_MAX, presentation_image_available,
                VK_NULL_HANDLE, &presentation_image_index);
            if (acquire_result == VK_ERROR_OUT_OF_DATE_KHR) {
                create_swapchain(true);
                acquire_result = vkAcquireNextImageKHR(
                    device, swapchain, UINT64_MAX, presentation_image_available,
                    VK_NULL_HANDLE, &presentation_image_index);
            }
            if (acquire_result != VK_SUCCESS && acquire_result != VK_SUBOPTIMAL_KHR) {
                require_vk(acquire_result, "vkAcquireNextImageKHR");
            }
            update_presentation_descriptor(final_output_buffer);
        }
        const auto record_start = stamp();
        require_vk(vkResetCommandBuffer(command_buffer, 0U), "vkResetCommandBuffer");
        VkCommandBufferBeginInfo begin{};
        begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        require_vk(vkBeginCommandBuffer(command_buffer, &begin), "vkBeginCommandBuffer");
        const auto timestamp = [this, gpu_sample](uint32_t index, VkPipelineStageFlagBits stage) {
            if (gpu_sample) vkCmdWriteTimestamp(command_buffer, stage, preview_timestamp_pool, index);
        };
        if (gpu_sample) vkCmdResetQueryPool(command_buffer, preview_timestamp_pool, 0U, 6U);
        timestamp(0U, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT);

        if (has_ahb_input) {
            const std::array<VkBufferMemoryBarrier, 4> upload_barriers{
                buffer_barrier(ahb_parameter_buffer, VK_ACCESS_HOST_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT),
                buffer_barrier(optics_parameter_buffer, VK_ACCESS_HOST_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT),
                buffer_barrier(sensor_parameter_buffer, VK_ACCESS_HOST_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT),
                buffer_barrier(isp_parameter_buffer, VK_ACCESS_HOST_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT),
            };
            vkCmdPipelineBarrier(command_buffer, VK_PIPELINE_STAGE_HOST_BIT,
                                 VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0U, 0U, nullptr,
                                 static_cast<uint32_t>(upload_barriers.size()), upload_barriers.data(),
                                 0U, nullptr);

            VkImageMemoryBarrier acquire_barrier{};
            acquire_barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
            acquire_barrier.srcAccessMask = 0U;
            acquire_barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
            acquire_barrier.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
            acquire_barrier.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            acquire_barrier.srcQueueFamilyIndex = external_queue_family;
            acquire_barrier.dstQueueFamilyIndex = queue_family;
            acquire_barrier.image = ahb_image;
            acquire_barrier.subresourceRange = {
                VK_IMAGE_ASPECT_COLOR_BIT, 0U, 1U, 0U, 1U};
            vkCmdPipelineBarrier(command_buffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                                 VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0U, 0U, nullptr,
                                 0U, nullptr, 1U, &acquire_barrier);

            vkCmdBindPipeline(command_buffer, VK_PIPELINE_BIND_POINT_COMPUTE, ahb_pipeline);
            vkCmdBindDescriptorSets(command_buffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                                    ahb_pipeline_layout, 0U, 1U, &ahb_descriptor_set,
                                    0U, nullptr);
            vkCmdDispatch(command_buffer, (width + 15U) / 16U, (height + 15U) / 16U, 1U);
            const VkBufferMemoryBarrier scene_barrier = buffer_barrier(
                scene_buffer, VK_ACCESS_SHADER_WRITE_BIT,
                VK_ACCESS_SHADER_READ_BIT |
                    (host_readback ? VK_ACCESS_HOST_READ_BIT : 0U));
            vkCmdPipelineBarrier(command_buffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                                 VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_HOST_BIT,
                                 0U, 0U, nullptr, 1U, &scene_barrier, 0U, nullptr);

            VkImageMemoryBarrier release_barrier{};
            release_barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
            release_barrier.srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
            release_barrier.dstAccessMask = 0U;
            release_barrier.oldLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            release_barrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
            release_barrier.srcQueueFamilyIndex = queue_family;
            release_barrier.dstQueueFamilyIndex = external_queue_family;
            release_barrier.image = ahb_image;
            release_barrier.subresourceRange = {
                VK_IMAGE_ASPECT_COLOR_BIT, 0U, 1U, 0U, 1U};
            vkCmdPipelineBarrier(command_buffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                                 VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0U, 0U, nullptr,
                                 0U, nullptr, 1U, &release_barrier);
        } else {
            const std::array<VkBufferMemoryBarrier, 4> upload_barriers{
                buffer_barrier(scene_buffer, VK_ACCESS_HOST_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT),
                buffer_barrier(optics_parameter_buffer, VK_ACCESS_HOST_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT),
                buffer_barrier(sensor_parameter_buffer, VK_ACCESS_HOST_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT),
                buffer_barrier(isp_parameter_buffer, VK_ACCESS_HOST_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT),
            };
            vkCmdPipelineBarrier(command_buffer, VK_PIPELINE_STAGE_HOST_BIT,
                                 VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0U, 0U, nullptr,
                                 static_cast<uint32_t>(upload_barriers.size()), upload_barriers.data(),
                                 0U, nullptr);
        }

        timestamp(1U, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
        bind_and_dispatch(preview != nullptr ? preview->optics_pipeline : optics_pipeline,
                          descriptor_sets[0], width, height);
        timestamp(2U, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
        const VkBufferMemoryBarrier optics_barrier = buffer_barrier(
            optics_buffer, VK_ACCESS_SHADER_WRITE_BIT,
            VK_ACCESS_SHADER_READ_BIT |
                (host_readback ? VK_ACCESS_HOST_READ_BIT : 0U));
        vkCmdPipelineBarrier(command_buffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_HOST_BIT,
                             0U, 0U, nullptr, 1U, &optics_barrier, 0U, nullptr);

        bind_and_dispatch(sensor_pipeline, descriptor_sets[1], width, height);
        timestamp(3U, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
        const VkBufferMemoryBarrier sensor_barrier = buffer_barrier(
            sensor_buffer, VK_ACCESS_SHADER_WRITE_BIT,
            VK_ACCESS_SHADER_READ_BIT |
                (host_readback ? VK_ACCESS_HOST_READ_BIT : 0U));
        vkCmdPipelineBarrier(command_buffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_HOST_BIT,
                             0U, 0U, nullptr, 1U, &sensor_barrier, 0U, nullptr);

        bind_and_dispatch(preview != nullptr ? preview->isp_pipeline : isp_pipeline,
                          descriptor_sets[2], width, height);
        timestamp(4U, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
        const Buffer& isp_linear_buffer = resource_mode == RenderResourceMode::Production
            ? optics_buffer
            : stage_buffer;
        if (host_readback) {
            const std::array<VkBufferMemoryBarrier, 2> download_barriers{
                buffer_barrier(isp_linear_buffer, VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_HOST_READ_BIT),
                buffer_barrier(final_output_buffer, VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_HOST_READ_BIT),
            };
            vkCmdPipelineBarrier(command_buffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                                 VK_PIPELINE_STAGE_HOST_BIT, 0U, 0U, nullptr,
                                 static_cast<uint32_t>(download_barriers.size()), download_barriers.data(),
                                 0U, nullptr);
        }
        if (has_presentation) {
            record_presentation(
                presentation_image_index, final_output_buffer, width, height);
        }
        timestamp(5U, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);

        require_vk(vkEndCommandBuffer(command_buffer), "vkEndCommandBuffer");
        require_vk(vkResetFences(device, 1U, &render_fence), "vkResetFences");
        VkSubmitInfo submit{};
        submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        std::array<VkSemaphore, 2> wait_semaphores{};
        std::array<VkPipelineStageFlags, 2> wait_stages{};
        uint32_t wait_count = 0U;
        if (wait_semaphore != VK_NULL_HANDLE) {
            wait_semaphores[wait_count] = wait_semaphore;
            wait_stages[wait_count] = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
            ++wait_count;
        }
        if (has_presentation) {
            wait_semaphores[wait_count] = presentation_image_available;
            wait_stages[wait_count] = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
            ++wait_count;
        }
        submit.waitSemaphoreCount = wait_count;
        submit.pWaitSemaphores = wait_count == 0U ? nullptr : wait_semaphores.data();
        submit.pWaitDstStageMask = wait_count == 0U ? nullptr : wait_stages.data();
        submit.commandBufferCount = 1U;
        submit.pCommandBuffers = &command_buffer;
        submit.signalSemaphoreCount = has_presentation ? 1U : 0U;
        submit.pSignalSemaphores = has_presentation
            ? &presentation_render_finished
            : nullptr;
        const auto submit_start = stamp();
        require_vk(vkQueueSubmit(queue, 1U, &submit, render_fence), "vkQueueSubmit");
        ++queue_submissions;
        const auto fence_start = stamp();
        require_vk(vkWaitForFences(device, 1U, &render_fence, VK_TRUE, UINT64_MAX), "vkWaitForFences");
        const auto present_start = stamp();
        if (has_presentation) {
            VkPresentInfoKHR present{};
            present.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
            present.waitSemaphoreCount = 1U;
            present.pWaitSemaphores = &presentation_render_finished;
            present.swapchainCount = 1U;
            present.pSwapchains = &swapchain;
            present.pImageIndices = &presentation_image_index;
            const VkResult present_result = vkQueuePresentKHR(queue, &present);
            if (present_result == VK_SUCCESS || present_result == VK_SUBOPTIMAL_KHR) {
                ++presented_frames;
                if (present_result == VK_SUBOPTIMAL_KHR) create_swapchain(true);
            } else if (present_result == VK_ERROR_OUT_OF_DATE_KHR) {
                create_swapchain(true);
            } else {
                require_vk(present_result, "vkQueuePresentKHR");
            }
        }
        if (sample) {
            const auto present_end = Clock::now();
            std::array<double, 5> gpu_ms{-1.0, -1.0, -1.0, -1.0, -1.0};
            std::array<uint64_t, 6> ticks{};
            // The existing fence has already completed. Never add a query WAIT or
            // another synchronization point solely for diagnostics.
            if (gpu_sample && vkGetQueryPoolResults(device, preview_timestamp_pool, 0U, 6U,
                    sizeof(ticks), ticks.data(), sizeof(uint64_t), VK_QUERY_RESULT_64_BIT) == VK_SUCCESS) {
                const uint64_t mask = timestamp_valid_bits >= 64U
                    ? UINT64_MAX : (uint64_t{1U} << timestamp_valid_bits) - 1U;
                for (size_t index = 0U; index < gpu_ms.size(); ++index) {
                    gpu_ms[index] = static_cast<double>((ticks[index + 1U] - ticks[index]) & mask) *
                        static_cast<double>(timestamp_period) / 1'000'000.0;
                }
            }
            __android_log_print(ANDROID_LOG_INFO, "PhyToyCamera2",
                "PreviewStages frame=%llu size=%ux%u halo=%u/%u "
                "cpu_ms[prepare=%.3f import=%.3f upload=%.3f acquire=%.3f record=%.3f submit=%.3f fence=%.3f present=%.3f] "
                "gpu_ms[normalize=%.3f optics=%.3f sensor=%.3f isp=%.3f display=%.3f] "
                "cache[entries=%zu bytes=%llu hits=%llu misses=%llu evictions=%llu removals=%llu stable_id=%llu pointer_id=%llu] preparations=%llu",
                static_cast<unsigned long long>(preview_frames), width, height,
                preview->optics_radius, preview->isp_halo,
                sampled_prepare_ms, sampled_import_ms, sampled_upload_ms,
                elapsed_ms(acquire_start, record_start), elapsed_ms(record_start, submit_start),
                elapsed_ms(submit_start, fence_start), elapsed_ms(fence_start, present_start),
                elapsed_ms(present_start, present_end),
                gpu_ms[0], gpu_ms[1], gpu_ms[2], gpu_ms[3], gpu_ms[4],
                ahb_cache.size(), static_cast<unsigned long long>(cached_ahb_bytes()),
                static_cast<unsigned long long>(ahb_cache_hits), static_cast<unsigned long long>(ahb_cache_misses),
                static_cast<unsigned long long>(ahb_cache_evictions), static_cast<unsigned long long>(ahb_cache_removals),
                static_cast<unsigned long long>(ahb_stable_ids), static_cast<unsigned long long>(ahb_pointer_ids),
                static_cast<unsigned long long>(preview_preparations));
        }
    }

    static void emit_buffer(const Buffer& buffer, pte_stage_t stage,
                            uint32_t width, uint32_t height, uint32_t channels,
                            pte_stage_callback_f32 callback, void* user_data) {
        if (callback != nullptr) {
            callback(user_data, stage, static_cast<const float*>(buffer.mapped),
                     width, height, channels);
        }
    }

    static void copy_output(const Buffer& buffer, uint32_t width, uint32_t height,
                            pte_output_f32_t& destination) {
        const auto* source = static_cast<const float*>(buffer.mapped);
        const uint32_t stride = destination.row_stride_floats == 0U
            ? width * 3U
            : destination.row_stride_floats;
        for (uint32_t y = 0U; y < height; ++y) {
            std::copy_n(source + static_cast<size_t>(y) * width * 3U,
                        static_cast<size_t>(width) * 3U,
                        destination.data + static_cast<size_t>(y) * stride);
        }
    }

    [[nodiscard]] VulkanRuntimeStats stats() const noexcept {
        return VulkanRuntimeStats{
            queue_submissions,
            resource_allocations,
            scene_buffer.size + optics_buffer.size + sensor_buffer.size + output_buffer.size +
                optics_parameter_buffer.size + sensor_parameter_buffer.size + isp_parameter_buffer.size +
                ahb_parameter_buffer.size + stage_buffer.size,
            ahb_imports,
            presented_frames,
            swapchain_recreates,
            swapchain_extent.width,
            swapchain_extent.height,
        };
    }

    void update_ahb_descriptor_set(const ImportedAhbImage& imported) const {
        const VkDescriptorImageInfo image_info{
            VK_NULL_HANDLE, imported.view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
        const VkDescriptorBufferInfo scene_info{
            scene_buffer.buffer, 0U, scene_buffer.size};
        const VkDescriptorBufferInfo parameter_info{
            ahb_parameter_buffer.buffer, 0U, ahb_parameter_buffer.size};
        std::array<VkWriteDescriptorSet, 3> writes{};
        writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[0].dstSet = ahb_descriptor_set;
        writes[0].dstBinding = 0U;
        writes[0].descriptorCount = 1U;
        writes[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        writes[0].pImageInfo = &image_info;
        writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[1].dstSet = ahb_descriptor_set;
        writes[1].dstBinding = 1U;
        writes[1].descriptorCount = 1U;
        writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        writes[1].pBufferInfo = &scene_info;
        writes[2].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[2].dstSet = ahb_descriptor_set;
        writes[2].dstBinding = 2U;
        writes[2].descriptorCount = 1U;
        writes[2].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        writes[2].pBufferInfo = &parameter_info;
        vkUpdateDescriptorSets(device, static_cast<uint32_t>(writes.size()), writes.data(), 0U, nullptr);
    }

    void render_ahardware_buffer(AHardwareBuffer* buffer, uint32_t width, uint32_t height,
                                 int acquire_fence_fd, const HostProfile& host,
                                 const ToyProfile& toy, uint64_t seed,
                                 pte_stage_callback_f32 callback, void* user_data,
                                 pte_output_f32_t* destination) {
        if (host.input_mode != HostInputMode::Srgb) {
            throw std::invalid_argument(
                "AHardwareBuffer input requires an encoded-sRGB host profile");
        }
        const bool is_preview = callback == nullptr && destination == nullptr;
        const bool sample = is_preview && ++preview_frames % 30U == 0U;
        const auto prepare_start = sample ? Clock::now() : Clock::time_point{};
        PreparedPreview capture_parameters;
        PreparedPreview* parameters = nullptr;
        if (is_preview) {
            parameters = &prepare_preview(host, toy, width, height);
            parameters->sensor[22] = std::bit_cast<float>(static_cast<uint32_t>(seed));
        } else {
            // Keep capture preparation and generic pipelines independent of preview.
            capture_parameters.normalization = ahb_normalization_parameters(width, height, host);
            const ToyProfile resolved = profile_for_resolution(toy, width, height);
            capture_parameters.optics = optics_parameters(width, height, resolved.optics);
            capture_parameters.sensor = sensor_parameters(width, height, resolved.sensor, seed);
            capture_parameters.isp = isp_parameters(width, height, resolved.sensor, resolved.isp);
            parameters = &capture_parameters;
        }
        const auto import_start = sample ? Clock::now() : Clock::time_point{};
        const auto& ahb_params = parameters->normalization;
        const auto& optics_params = parameters->optics;
        const auto& sensor_params = parameters->sensor;
        const auto& isp_params = parameters->isp;
        const VkDeviceSize pixels = static_cast<VkDeviceSize>(width) * height;
        const VkDeviceSize rgb_bytes = pixels * 3U * sizeof(float);
        const VkDeviceSize raw_bytes = pixels * sizeof(float);
        const RenderResourceMode resource_mode = callback == nullptr
            ? RenderResourceMode::Production
            : RenderResourceMode::PreserveSceneAndOptics;
        ImportedAhbImage& imported = cached_ahardware_buffer(buffer, width, height);
        const auto upload_start = sample ? Clock::now() : Clock::time_point{};
        ensure_frame_resources(
            rgb_bytes, raw_bytes,
            static_cast<VkDeviceSize>(optics_params.size() * sizeof(float)),
            static_cast<VkDeviceSize>(sensor_params.size() * sizeof(float)),
            static_cast<VkDeviceSize>(isp_params.size() * sizeof(float)),
            resource_mode,
            static_cast<VkDeviceSize>(ahb_params.size() * sizeof(float)));
        upload(ahb_parameter_buffer, ahb_params);
        upload(optics_parameter_buffer, optics_params);
        upload(sensor_parameter_buffer, sensor_params);
        upload(isp_parameter_buffer, isp_params);
        update_ahb_descriptor_set(imported);

        VkSemaphore wait_semaphore = import_acquire_fence(acquire_fence_fd);
        try {
            if (sample) {
                sampled_prepare_ms = elapsed_ms(prepare_start, import_start);
                sampled_import_ms = elapsed_ms(import_start, upload_start);
                sampled_upload_ms = elapsed_ms(upload_start, Clock::now());
            }
            submit_render_graph(
                width, height, imported.image, wait_semaphore, resource_mode,
                callback != nullptr || destination != nullptr,
                is_preview ? parameters : nullptr, sample);
        } catch (...) {
            if (device != VK_NULL_HANDLE) vkQueueWaitIdle(queue);
            if (wait_semaphore != VK_NULL_HANDLE) {
                vkDestroySemaphore(device, wait_semaphore, nullptr);
            }
            throw;
        }
        if (wait_semaphore != VK_NULL_HANDLE) {
            vkDestroySemaphore(device, wait_semaphore, nullptr);
        }

        if (callback != nullptr) {
            emit_buffer(scene_buffer, PTE_STAGE_SCENE_LINEAR,
                        width, height, 3U, callback, user_data);
            emit_buffer(optics_buffer, PTE_STAGE_TARGET_OPTICS,
                        width, height, 3U, callback, user_data);
            emit_buffer(sensor_buffer, PTE_STAGE_TARGET_SENSOR_DN,
                        width, height, 1U, callback, user_data);
            emit_buffer(stage_buffer, PTE_STAGE_TARGET_ISP_LINEAR,
                        width, height, 3U, callback, user_data);
        }
        const Buffer& final_output = resource_mode == RenderResourceMode::PreserveSceneAndOptics
            ? output_buffer
            : scene_buffer;
        emit_buffer(final_output, PTE_STAGE_OUTPUT_SRGB,
                    width, height, 3U, callback, user_data);
        if (destination != nullptr) copy_output(final_output, width, height, *destination);
    }

    void render(const Image& scene, const ToyProfile& toy, uint64_t seed,
                pte_stage_callback_f32 callback, void* user_data,
                pte_output_f32_t& destination) {
        const ToyProfile resolved = profile_for_resolution(toy, scene.width, scene.height);
        const std::vector<float> optics_params = optics_parameters(scene.width, scene.height, resolved.optics);
        const std::vector<float> sensor_params = sensor_parameters(scene.width, scene.height, resolved.sensor, seed);
        const std::vector<float> isp_params = isp_parameters(scene.width, scene.height, resolved.sensor, resolved.isp);
        const VkDeviceSize rgb_bytes = static_cast<VkDeviceSize>(scene.pixels.size() * sizeof(float));
        const VkDeviceSize raw_bytes = static_cast<VkDeviceSize>(scene.width) * scene.height * sizeof(float);
        ensure_frame_resources(
            rgb_bytes, raw_bytes,
            static_cast<VkDeviceSize>(optics_params.size() * sizeof(float)),
            static_cast<VkDeviceSize>(sensor_params.size() * sizeof(float)),
            static_cast<VkDeviceSize>(isp_params.size() * sizeof(float)),
            callback == nullptr
                ? RenderResourceMode::Production
                : RenderResourceMode::PreserveOptics);
        std::memcpy(scene_buffer.mapped, scene.pixels.data(), static_cast<size_t>(rgb_bytes));
        upload(optics_parameter_buffer, optics_params);
        upload(sensor_parameter_buffer, sensor_params);
        upload(isp_parameter_buffer, isp_params);

        const RenderResourceMode resource_mode = callback == nullptr
            ? RenderResourceMode::Production
            : RenderResourceMode::PreserveOptics;
        submit_render_graph(scene.width, scene.height, VK_NULL_HANDLE, VK_NULL_HANDLE, resource_mode);
        if (callback != nullptr) {
            emit_buffer(optics_buffer, PTE_STAGE_TARGET_OPTICS,
                        scene.width, scene.height, 3U, callback, user_data);
            emit_buffer(sensor_buffer, PTE_STAGE_TARGET_SENSOR_DN,
                        scene.width, scene.height, 1U, callback, user_data);
            emit_buffer(stage_buffer, PTE_STAGE_TARGET_ISP_LINEAR,
                        scene.width, scene.height, 3U, callback, user_data);
        }
        emit_buffer(scene_buffer, PTE_STAGE_OUTPUT_SRGB,
                    scene.width, scene.height, 3U, callback, user_data);
        copy_output(scene_buffer, scene.width, scene.height, destination);
    }

    void destroy() noexcept {
        if (device != VK_NULL_HANDLE) vkDeviceWaitIdle(device);
        destroy_presentation();
        ahb_cache.clear();
        uncached_ahb.destroy();
        scene_buffer.destroy();
        optics_buffer.destroy();
        sensor_buffer.destroy();
        output_buffer.destroy();
        optics_parameter_buffer.destroy();
        sensor_parameter_buffer.destroy();
        isp_parameter_buffer.destroy();
        ahb_parameter_buffer.destroy();
        stage_buffer.destroy();
        destroy_ahb_pipeline();
        for (const auto& [key, pipeline] : preview_optics_pipelines) {
            if (device != VK_NULL_HANDLE && pipeline != VK_NULL_HANDLE && pipeline != optics_pipeline)
                vkDestroyPipeline(device, pipeline, nullptr);
        }
        for (const auto& [key, pipeline] : preview_isp_pipelines) {
            if (device != VK_NULL_HANDLE && pipeline != VK_NULL_HANDLE && pipeline != isp_pipeline)
                vkDestroyPipeline(device, pipeline, nullptr);
        }
        preview_optics_pipelines.clear();
        preview_isp_pipelines.clear();
        if (device != VK_NULL_HANDLE && preview_timestamp_pool != VK_NULL_HANDLE)
            vkDestroyQueryPool(device, preview_timestamp_pool, nullptr);
        preview_timestamp_pool = VK_NULL_HANDLE;
        if (device != VK_NULL_HANDLE && render_fence != VK_NULL_HANDLE) vkDestroyFence(device, render_fence, nullptr);
        if (device != VK_NULL_HANDLE && command_pool != VK_NULL_HANDLE) vkDestroyCommandPool(device, command_pool, nullptr);
        if (device != VK_NULL_HANDLE && descriptor_pool != VK_NULL_HANDLE) vkDestroyDescriptorPool(device, descriptor_pool, nullptr);
        if (device != VK_NULL_HANDLE && optics_pipeline != VK_NULL_HANDLE) vkDestroyPipeline(device, optics_pipeline, nullptr);
        if (device != VK_NULL_HANDLE && sensor_pipeline != VK_NULL_HANDLE) vkDestroyPipeline(device, sensor_pipeline, nullptr);
        if (device != VK_NULL_HANDLE && isp_pipeline != VK_NULL_HANDLE) vkDestroyPipeline(device, isp_pipeline, nullptr);
        if (device != VK_NULL_HANDLE && pipeline_layout != VK_NULL_HANDLE) vkDestroyPipelineLayout(device, pipeline_layout, nullptr);
        if (device != VK_NULL_HANDLE && descriptor_layout != VK_NULL_HANDLE) vkDestroyDescriptorSetLayout(device, descriptor_layout, nullptr);
        if (device != VK_NULL_HANDLE) vkDestroyDevice(device, nullptr);
        if (instance != VK_NULL_HANDLE) vkDestroyInstance(instance, nullptr);
        instance = VK_NULL_HANDLE;
        device = VK_NULL_HANDLE;
        render_fence = VK_NULL_HANDLE;
        ready = false;
    }
};

#else

struct VulkanBackend::Impl {
    bool ready{false};
    std::string error{"Vulkan backend is available only in an Android build with embedded SPIR-V"};

    [[nodiscard]] VulkanRuntimeStats stats() const noexcept { return {}; }
};

#endif

VulkanBackend::VulkanBackend() : impl_(std::make_unique<Impl>()) {}
VulkanBackend::~VulkanBackend() = default;

bool VulkanBackend::available() const noexcept {
    return impl_ != nullptr && impl_->ready;
}

bool VulkanBackend::ahardware_buffer_input_available() const noexcept {
#if defined(__ANDROID__) && defined(PHYTOY_HAS_EMBEDDED_SPIRV)
    return available() && impl_->ahb_import_supported;
#else
    return false;
#endif
}

VulkanRuntimeStats VulkanBackend::stats() const noexcept {
    return impl_ == nullptr ? VulkanRuntimeStats{} : impl_->stats();
}

void VulkanBackend::set_output_window(
    ANativeWindow* window, uint32_t rotation_degrees) {
#if defined(__ANDROID__) && defined(PHYTOY_HAS_EMBEDDED_SPIRV)
    if (impl_ == nullptr) throw std::runtime_error("Vulkan backend is unavailable");
    impl_->set_output_window(window, rotation_degrees);
#else
    (void)window;
    (void)rotation_degrees;
    throw std::runtime_error("Vulkan Android output surface is unavailable");
#endif
}

void VulkanBackend::forget_ahardware_buffer(AHardwareBuffer* buffer) noexcept {
#if defined(__ANDROID__) && defined(PHYTOY_HAS_EMBEDDED_SPIRV)
    if (impl_ != nullptr) impl_->forget_ahardware_buffer(buffer);
#else
    (void)buffer;
#endif
}

void VulkanBackend::render(
    const pte_frame_f32_t& frame,
    const HostProfile& host,
    const ToyProfile& toy,
    uint64_t seed,
    pte_stage_callback_f32 stage_callback,
    void* stage_user_data,
    pte_output_f32_t& output) {
    if (!available()) throw std::runtime_error(impl_->error);
    Image scene = normalize_cpu(frame, host);
#if defined(__ANDROID__) && defined(PHYTOY_HAS_EMBEDDED_SPIRV)
    emit_stage(scene, PTE_STAGE_SCENE_LINEAR, stage_callback, stage_user_data);
    impl_->render(scene, toy, seed, stage_callback, stage_user_data, output);
#else
    (void)toy;
    (void)seed;
    (void)stage_callback;
    (void)stage_user_data;
    (void)output;
    throw std::runtime_error(impl_->error);
#endif
}

void VulkanBackend::render_ahardware_buffer(
    AHardwareBuffer* buffer,
    uint32_t width,
    uint32_t height,
    int acquire_fence_fd,
    const HostProfile& host,
    const ToyProfile& toy,
    uint64_t seed,
    pte_stage_callback_f32 stage_callback,
    void* stage_user_data,
    pte_output_f32_t* output) {
    if (!ahardware_buffer_input_available()) {
        throw std::runtime_error("Vulkan AHardwareBuffer input is unavailable");
    }
#if defined(__ANDROID__) && defined(PHYTOY_HAS_EMBEDDED_SPIRV)
    impl_->render_ahardware_buffer(
        buffer, width, height, acquire_fence_fd, host, toy, seed,
        stage_callback, stage_user_data, output);
#else
    (void)buffer;
    (void)width;
    (void)height;
    (void)acquire_fence_fd;
    (void)host;
    (void)toy;
    (void)seed;
    (void)stage_callback;
    (void)stage_user_data;
    (void)output;
    throw std::runtime_error("Vulkan AHardwareBuffer input is unavailable");
#endif
}

bool vulkan_backend_available() noexcept {
    try {
        VulkanBackend backend;
        return backend.available();
    } catch (...) {
        return false;
    }
}

}  // namespace phytoy
