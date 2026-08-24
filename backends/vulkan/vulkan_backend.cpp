#include "vulkan_backend.hpp"

#include "cpu_backend.hpp"

#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <cstring>
#include <dlfcn.h>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

#if defined(__ANDROID__) && defined(PHYTOY_HAS_EMBEDDED_SPIRV)
#include "phytoy_spirv.hpp"
#include <android/hardware_buffer.h>
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
    std::vector<float> result(20U, 0.0F);
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

    VkInstance instance{VK_NULL_HANDLE};
    VkPhysicalDevice physical_device{VK_NULL_HANDLE};
    VkDevice device{VK_NULL_HANDLE};
    VkQueue queue{VK_NULL_HANDLE};
    uint32_t queue_family{};
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
    RenderResourceMode descriptor_resource_mode{RenderResourceMode::Production};
    bool ahb_import_supported{};
    bool external_semaphore_fd_supported{};
    uint32_t external_queue_family{VK_QUEUE_FAMILY_EXTERNAL};
    uint64_t resource_allocations{};
    uint64_t queue_submissions{};
    uint64_t ahb_imports{};
    uint64_t ahb_use_clock{};
    std::vector<CachedAhbImage> ahb_cache;
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
        application.applicationVersion = VK_MAKE_VERSION(0, 2, 1);
        application.pEngineName = "PhyToyEngine";
        application.engineVersion = VK_MAKE_VERSION(0, 2, 1);
        application.apiVersion = VK_API_VERSION_1_1;
        VkInstanceCreateInfo instance_info{};
        instance_info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
        instance_info.pApplicationInfo = &application;
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
                if ((families[family].queueFlags & VK_QUEUE_COMPUTE_BIT) != 0U) {
                    physical_device = candidate;
                    queue_family = family;
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
    }

    VkPipeline create_pipeline(const uint32_t* words, size_t byte_count, VkPipelineLayout layout) {
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
                return entry.imported;
            }
        }
        if (ahb_cache.size() >= 8U) {
            const auto oldest = std::min_element(
                ahb_cache.begin(), ahb_cache.end(), [](const auto& left, const auto& right) {
                    return left.last_use < right.last_use;
                });
            ahb_cache.erase(oldest);
        }
        ImportedAhbImage imported = import_ahardware_buffer(buffer, width, height);
        ahb_cache.emplace_back(
            buffer, identity, std::move(imported), width, height, ahb_use_clock);
        ++ahb_imports;
        return ahb_cache.back().imported;
    }

    [[nodiscard]] uint64_t ahardware_buffer_identity(AHardwareBuffer* buffer) const noexcept {
        uint64_t identity = 0U;
        if (get_ahb_id != nullptr && get_ahb_id(buffer, &identity) == 0) return identity;
        return static_cast<uint64_t>(reinterpret_cast<uintptr_t>(buffer));
    }

    void forget_ahardware_buffer(AHardwareBuffer* buffer) noexcept {
        if (buffer == nullptr) {
            ahb_cache.clear();
            return;
        }
        const uint64_t identity = ahardware_buffer_identity(buffer);
        std::erase_if(ahb_cache, [identity](const auto& entry) {
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

    void submit_render_graph(uint32_t width, uint32_t height,
                             VkImage ahb_image = VK_NULL_HANDLE,
                             VkSemaphore wait_semaphore = VK_NULL_HANDLE,
                             RenderResourceMode resource_mode = RenderResourceMode::Production,
                             bool host_readback = true) {
        const bool has_ahb_input = ahb_image != VK_NULL_HANDLE;
        require_vk(vkResetCommandBuffer(command_buffer, 0U), "vkResetCommandBuffer");
        VkCommandBufferBeginInfo begin{};
        begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        require_vk(vkBeginCommandBuffer(command_buffer, &begin), "vkBeginCommandBuffer");

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

        bind_and_dispatch(optics_pipeline, descriptor_sets[0], width, height);
        const VkBufferMemoryBarrier optics_barrier = buffer_barrier(
            optics_buffer, VK_ACCESS_SHADER_WRITE_BIT,
            VK_ACCESS_SHADER_READ_BIT |
                (host_readback ? VK_ACCESS_HOST_READ_BIT : 0U));
        vkCmdPipelineBarrier(command_buffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_HOST_BIT,
                             0U, 0U, nullptr, 1U, &optics_barrier, 0U, nullptr);

        bind_and_dispatch(sensor_pipeline, descriptor_sets[1], width, height);
        const VkBufferMemoryBarrier sensor_barrier = buffer_barrier(
            sensor_buffer, VK_ACCESS_SHADER_WRITE_BIT,
            VK_ACCESS_SHADER_READ_BIT |
                (host_readback ? VK_ACCESS_HOST_READ_BIT : 0U));
        vkCmdPipelineBarrier(command_buffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_HOST_BIT,
                             0U, 0U, nullptr, 1U, &sensor_barrier, 0U, nullptr);

        bind_and_dispatch(isp_pipeline, descriptor_sets[2], width, height);
        const Buffer& isp_linear_buffer = resource_mode == RenderResourceMode::Production
            ? optics_buffer
            : stage_buffer;
        const Buffer& final_output_buffer =
            resource_mode == RenderResourceMode::PreserveSceneAndOptics
            ? output_buffer
            : scene_buffer;
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

        require_vk(vkEndCommandBuffer(command_buffer), "vkEndCommandBuffer");
        require_vk(vkResetFences(device, 1U, &render_fence), "vkResetFences");
        VkSubmitInfo submit{};
        submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        const VkPipelineStageFlags wait_stage = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
        submit.waitSemaphoreCount = wait_semaphore == VK_NULL_HANDLE ? 0U : 1U;
        submit.pWaitSemaphores = wait_semaphore == VK_NULL_HANDLE ? nullptr : &wait_semaphore;
        submit.pWaitDstStageMask = wait_semaphore == VK_NULL_HANDLE ? nullptr : &wait_stage;
        submit.commandBufferCount = 1U;
        submit.pCommandBuffers = &command_buffer;
        require_vk(vkQueueSubmit(queue, 1U, &submit, render_fence), "vkQueueSubmit");
        ++queue_submissions;
        require_vk(vkWaitForFences(device, 1U, &render_fence, VK_TRUE, UINT64_MAX), "vkWaitForFences");
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
        const std::vector<float> ahb_params =
            ahb_normalization_parameters(width, height, host);
        const std::vector<float> optics_params = optics_parameters(width, height, toy.optics);
        const std::vector<float> sensor_params = sensor_parameters(width, height, toy.sensor, seed);
        const std::vector<float> isp_params = isp_parameters(width, height, toy.sensor, toy.isp);
        const VkDeviceSize pixels = static_cast<VkDeviceSize>(width) * height;
        const VkDeviceSize rgb_bytes = pixels * 3U * sizeof(float);
        const VkDeviceSize raw_bytes = pixels * sizeof(float);
        const RenderResourceMode resource_mode = callback == nullptr
            ? RenderResourceMode::Production
            : RenderResourceMode::PreserveSceneAndOptics;
        ImportedAhbImage& imported = cached_ahardware_buffer(buffer, width, height);
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
            submit_render_graph(
                width, height, imported.image, wait_semaphore, resource_mode,
                callback != nullptr || destination != nullptr);
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
        const std::vector<float> optics_params = optics_parameters(scene.width, scene.height, toy.optics);
        const std::vector<float> sensor_params = sensor_parameters(scene.width, scene.height, toy.sensor, seed);
        const std::vector<float> isp_params = isp_parameters(scene.width, scene.height, toy.sensor, toy.isp);
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
        ahb_cache.clear();
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
    VulkanBackend backend;
    return backend.available();
}

}  // namespace phytoy
