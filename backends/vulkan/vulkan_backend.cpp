#include "vulkan_backend.hpp"

#include "cpu_backend.hpp"

#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <cstring>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

#if defined(__ANDROID__) && defined(PHYTOY_HAS_EMBEDDED_SPIRV)
#include "phytoy_spirv.hpp"
#include <sys/mman.h>
#include <unistd.h>
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

}  // namespace

struct VulkanBackend::Impl {
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
            buffer = VK_NULL_HANDLE;
            memory = VK_NULL_HANDLE;
            mapped = nullptr;
        }
    };

    VkInstance instance{VK_NULL_HANDLE};
    VkPhysicalDevice physical_device{VK_NULL_HANDLE};
    VkDevice device{VK_NULL_HANDLE};
    VkQueue queue{VK_NULL_HANDLE};
    uint32_t queue_family{};
    VkDescriptorSetLayout descriptor_layout{VK_NULL_HANDLE};
    VkPipelineLayout pipeline_layout{VK_NULL_HANDLE};
    VkPipeline optics_pipeline{VK_NULL_HANDLE};
    VkPipeline sensor_pipeline{VK_NULL_HANDLE};
    VkPipeline isp_pipeline{VK_NULL_HANDLE};
    VkDescriptorPool descriptor_pool{VK_NULL_HANDLE};
    VkDescriptorSet descriptor_set{VK_NULL_HANDLE};
    VkCommandPool command_pool{VK_NULL_HANDLE};
    VkCommandBuffer command_buffer{VK_NULL_HANDLE};
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
        VkApplicationInfo application{};
        application.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
        application.pApplicationName = "PhyToyEngine";
        application.applicationVersion = VK_MAKE_VERSION(0, 1, 0);
        application.pEngineName = "PhyToyEngine";
        application.engineVersion = VK_MAKE_VERSION(0, 1, 0);
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
        require_vk(vkCreateDevice(physical_device, &device_info, nullptr, &device), "vkCreateDevice");
        vkGetDeviceQueue(device, queue_family, 0U, &queue);

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

        optics_pipeline = create_pipeline(spirv::optics, spirv::optics_bytes);
        sensor_pipeline = create_pipeline(spirv::sensor, spirv::sensor_bytes);
        isp_pipeline = create_pipeline(spirv::isp, spirv::isp_bytes);

        VkDescriptorPoolSize pool_size{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 4U};
        VkDescriptorPoolCreateInfo pool_info{};
        pool_info.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
        pool_info.maxSets = 1U;
        pool_info.poolSizeCount = 1U;
        pool_info.pPoolSizes = &pool_size;
        require_vk(vkCreateDescriptorPool(device, &pool_info, nullptr, &descriptor_pool), "vkCreateDescriptorPool");
        VkDescriptorSetAllocateInfo set_info{};
        set_info.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        set_info.descriptorPool = descriptor_pool;
        set_info.descriptorSetCount = 1U;
        set_info.pSetLayouts = &descriptor_layout;
        require_vk(vkAllocateDescriptorSets(device, &set_info, &descriptor_set), "vkAllocateDescriptorSets");

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
    }

    VkPipeline create_pipeline(const uint32_t* words, size_t byte_count) {
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
        pipeline_info.layout = pipeline_layout;
        VkPipeline pipeline = VK_NULL_HANDLE;
        const VkResult result = vkCreateComputePipelines(device, VK_NULL_HANDLE, 1U, &pipeline_info, nullptr, &pipeline);
        vkDestroyShaderModule(device, module, nullptr);
        require_vk(result, "vkCreateComputePipelines");
        return pipeline;
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

    void dispatch(VkPipeline pipeline, Buffer& input, Buffer& output, Buffer& aux,
                  Buffer& parameter_buffer, const std::vector<float>& parameters,
                  uint32_t width, uint32_t height) {
        const size_t parameter_bytes = parameters.size() * sizeof(float);
        if (parameter_bytes > parameter_buffer.size) throw std::runtime_error("Vulkan parameter buffer overflow");
        std::memcpy(parameter_buffer.mapped, parameters.data(), parameter_bytes);
        std::array<VkDescriptorBufferInfo, 4> infos{
            VkDescriptorBufferInfo{input.buffer, 0U, input.size},
            VkDescriptorBufferInfo{output.buffer, 0U, output.size},
            VkDescriptorBufferInfo{aux.buffer, 0U, aux.size},
            VkDescriptorBufferInfo{parameter_buffer.buffer, 0U, parameter_buffer.size},
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
        require_vk(vkResetCommandBuffer(command_buffer, 0U), "vkResetCommandBuffer");
        VkCommandBufferBeginInfo begin{};
        begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        require_vk(vkBeginCommandBuffer(command_buffer, &begin), "vkBeginCommandBuffer");
        vkCmdBindPipeline(command_buffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
        vkCmdBindDescriptorSets(command_buffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline_layout,
                                0U, 1U, &descriptor_set, 0U, nullptr);
        vkCmdDispatch(command_buffer, (width + 15U) / 16U, (height + 15U) / 16U, 1U);
        require_vk(vkEndCommandBuffer(command_buffer), "vkEndCommandBuffer");
        VkSubmitInfo submit{};
        submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        submit.commandBufferCount = 1U;
        submit.pCommandBuffers = &command_buffer;
        require_vk(vkQueueSubmit(queue, 1U, &submit, VK_NULL_HANDLE), "vkQueueSubmit");
        require_vk(vkQueueWaitIdle(queue), "vkQueueWaitIdle");
    }

    Image image_from(const Buffer& buffer, uint32_t width, uint32_t height, uint32_t channels) const {
        Image image(width, height, channels);
        std::memcpy(image.pixels.data(), buffer.mapped, image.pixels.size() * sizeof(float));
        return image;
    }

    Image render(const Image& scene, const ToyProfile& toy, uint64_t seed,
                 pte_stage_callback_f32 callback, void* user_data) {
        const VkDeviceSize rgb_bytes = static_cast<VkDeviceSize>(scene.pixels.size() * sizeof(float));
        const VkDeviceSize raw_bytes = static_cast<VkDeviceSize>(scene.width) * scene.height * sizeof(float);
        Buffer a = create_buffer(rgb_bytes);
        Buffer b = create_buffer(rgb_bytes);
        Buffer c = create_buffer(std::max(rgb_bytes, raw_bytes));
        Buffer d = create_buffer(rgb_bytes);
        Buffer params = create_buffer(16U * 1024U);
        std::memcpy(a.mapped, scene.pixels.data(), static_cast<size_t>(rgb_bytes));

        dispatch(optics_pipeline, a, b, c, params,
                 optics_parameters(scene.width, scene.height, toy.optics), scene.width, scene.height);
        if (callback != nullptr) emit_stage(image_from(b, scene.width, scene.height, 3U),
                                            PTE_STAGE_TARGET_OPTICS, callback, user_data);
        dispatch(sensor_pipeline, b, c, a, params,
                 sensor_parameters(scene.width, scene.height, toy.sensor, seed), scene.width, scene.height);
        if (callback != nullptr) emit_stage(image_from(c, scene.width, scene.height, 1U),
                                            PTE_STAGE_TARGET_SENSOR_DN, callback, user_data);
        dispatch(isp_pipeline, c, d, b, params,
                 isp_parameters(scene.width, scene.height, toy.sensor, toy.isp), scene.width, scene.height);
        if (callback != nullptr) {
            emit_stage(image_from(b, scene.width, scene.height, 3U),
                       PTE_STAGE_TARGET_ISP_LINEAR, callback, user_data);
        }
        Image output = image_from(d, scene.width, scene.height, 3U);
        emit_stage(output, PTE_STAGE_OUTPUT_SRGB, callback, user_data);
        return output;
    }

    void destroy() noexcept {
        if (device != VK_NULL_HANDLE) vkDeviceWaitIdle(device);
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
        ready = false;
    }
};

#else

struct VulkanBackend::Impl {
    bool ready{false};
    std::string error{"Vulkan backend is available only in an Android build with embedded SPIR-V"};
};

#endif

VulkanBackend::VulkanBackend() : impl_(std::make_unique<Impl>()) {}
VulkanBackend::~VulkanBackend() = default;

bool VulkanBackend::available() const noexcept {
    return impl_ != nullptr && impl_->ready;
}

Image VulkanBackend::render(
    const pte_frame_f32_t& frame,
    const HostProfile& host,
    const ToyProfile& toy,
    uint64_t seed,
    pte_stage_callback_f32 stage_callback,
    void* stage_user_data) {
    if (!available()) throw std::runtime_error(impl_->error);
    Image scene = normalize_cpu(frame, host);
#if defined(__ANDROID__) && defined(PHYTOY_HAS_EMBEDDED_SPIRV)
    emit_stage(scene, PTE_STAGE_SCENE_LINEAR, stage_callback, stage_user_data);
    return impl_->render(scene, toy, seed, stage_callback, stage_user_data);
#else
    (void)toy;
    (void)seed;
    (void)stage_callback;
    (void)stage_user_data;
    throw std::runtime_error(impl_->error);
#endif
}

bool vulkan_backend_available() noexcept {
    VulkanBackend backend;
    return backend.available();
}

}  // namespace phytoy
