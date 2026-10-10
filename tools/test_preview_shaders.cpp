// Standalone GPU regression check. Run on an isolated Android test device with
// a directory containing optics/isp.spv and optional *-baseline.spv from HEAD.
// This never opens a camera or an application and is not linked into the APK.
#include "../backends/vulkan/preview_geometry.hpp"
#include <vulkan/vulkan.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <map>
#include <stdexcept>
#include <string>
#include <vector>

void check(VkResult result, const char* operation) {
    if (result != VK_SUCCESS) throw std::runtime_error(std::string(operation) + ": " + std::to_string(result));
}

struct Gpu {
    VkInstance instance{};
    VkPhysicalDevice physical{};
    VkDevice device{};
    VkQueue queue{};
    VkDescriptorSetLayout descriptors{};
    VkPipelineLayout layout{};
    VkDescriptorPool descriptor_pool{};
    VkDescriptorSet set{};
    VkCommandPool command_pool{};
    VkCommandBuffer command{};
    VkFence fence{};
    struct Buffer { VkBuffer buffer{}; VkDeviceMemory memory{}; float* data{}; };
    std::array<Buffer, 4> buffers{};
    static constexpr size_t capacity = 8192;
    std::map<std::pair<std::string, std::vector<int32_t>>, VkPipeline> pipelines;

    Gpu() {
        VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
        app.apiVersion = VK_API_VERSION_1_1;
        VkInstanceCreateInfo info{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO}; info.pApplicationInfo = &app;
        check(vkCreateInstance(&info, nullptr, &instance), "create instance");
        uint32_t count = 0; check(vkEnumeratePhysicalDevices(instance, &count, nullptr), "device count");
        if (count == 0) throw std::runtime_error("no Vulkan device");
        std::vector<VkPhysicalDevice> devices(count);
        check(vkEnumeratePhysicalDevices(instance, &count, devices.data()), "devices");
        physical = devices.front();
        VkPhysicalDeviceProperties properties{}; vkGetPhysicalDeviceProperties(physical, &properties);
        std::cout << "GPU: " << properties.deviceName << '\n';
        vkGetPhysicalDeviceQueueFamilyProperties(physical, &count, nullptr);
        std::vector<VkQueueFamilyProperties> families(count);
        vkGetPhysicalDeviceQueueFamilyProperties(physical, &count, families.data());
        uint32_t family = 0;
        while (family < count && !(families[family].queueFlags & VK_QUEUE_COMPUTE_BIT)) ++family;
        if (family == count) throw std::runtime_error("no compute queue");
        const float priority = 1;
        VkDeviceQueueCreateInfo qi{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
        qi.queueFamilyIndex = family; qi.queueCount = 1; qi.pQueuePriorities = &priority;
        VkDeviceCreateInfo di{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO}; di.queueCreateInfoCount = 1; di.pQueueCreateInfos = &qi;
        check(vkCreateDevice(physical, &di, nullptr, &device), "create device"); vkGetDeviceQueue(device, family, 0, &queue);
        std::array<VkDescriptorSetLayoutBinding, 4> bindings{};
        for (uint32_t i = 0; i < 4; ++i) bindings[i] = {i, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
        VkDescriptorSetLayoutCreateInfo li{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
        li.bindingCount = 4; li.pBindings = bindings.data();
        check(vkCreateDescriptorSetLayout(device, &li, nullptr, &descriptors), "descriptor layout");
        VkPipelineLayoutCreateInfo pi{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO}; pi.setLayoutCount = 1; pi.pSetLayouts = &descriptors;
        check(vkCreatePipelineLayout(device, &pi, nullptr, &layout), "pipeline layout");
        VkDescriptorPoolSize size{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 4};
        VkDescriptorPoolCreateInfo pool{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO}; pool.maxSets = 1; pool.poolSizeCount = 1; pool.pPoolSizes = &size;
        check(vkCreateDescriptorPool(device, &pool, nullptr, &descriptor_pool), "descriptor pool");
        VkDescriptorSetAllocateInfo ai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO}; ai.descriptorPool = descriptor_pool; ai.descriptorSetCount = 1; ai.pSetLayouts = &descriptors;
        check(vkAllocateDescriptorSets(device, &ai, &set), "descriptor set");
        VkPhysicalDeviceMemoryProperties memory{}; vkGetPhysicalDeviceMemoryProperties(physical, &memory);
        std::array<VkDescriptorBufferInfo, 4> buffer_info{};
        std::array<VkWriteDescriptorSet, 4> writes{};
        for (uint32_t i = 0; i < 4; ++i) {
            auto& buffer = buffers[i];
            VkBufferCreateInfo bi{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO}; bi.size = capacity * sizeof(float); bi.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
            check(vkCreateBuffer(device, &bi, nullptr, &buffer.buffer), "buffer");
            VkMemoryRequirements requirements{}; vkGetBufferMemoryRequirements(device, buffer.buffer, &requirements);
            uint32_t type = 0;
            const auto flags = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
            while (type < memory.memoryTypeCount && (!(requirements.memoryTypeBits & (1U << type)) || (memory.memoryTypes[type].propertyFlags & flags) != flags)) ++type;
            if (type == memory.memoryTypeCount) throw std::runtime_error("no coherent memory");
            VkMemoryAllocateInfo mi{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO}; mi.allocationSize = requirements.size; mi.memoryTypeIndex = type;
            check(vkAllocateMemory(device, &mi, nullptr, &buffer.memory), "allocate");
            check(vkBindBufferMemory(device, buffer.buffer, buffer.memory, 0), "bind memory");
            void* mapped = nullptr; check(vkMapMemory(device, buffer.memory, 0, VK_WHOLE_SIZE, 0, &mapped), "map"); buffer.data = static_cast<float*>(mapped);
            buffer_info[i] = {buffer.buffer, 0, capacity * sizeof(float)};
            writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET; writes[i].dstSet = set; writes[i].dstBinding = i;
            writes[i].descriptorCount = 1; writes[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER; writes[i].pBufferInfo = &buffer_info[i];
        }
        vkUpdateDescriptorSets(device, 4, writes.data(), 0, nullptr);
        VkCommandPoolCreateInfo ci{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO}; ci.queueFamilyIndex = family; ci.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        check(vkCreateCommandPool(device, &ci, nullptr, &command_pool), "command pool");
        VkCommandBufferAllocateInfo ca{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO}; ca.commandPool = command_pool; ca.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY; ca.commandBufferCount = 1;
        check(vkAllocateCommandBuffers(device, &ca, &command), "command buffer");
        VkFenceCreateInfo fi{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO}; check(vkCreateFence(device, &fi, nullptr, &fence), "fence");
    }
    ~Gpu() {
        if (!device) return;
        vkDeviceWaitIdle(device);
        for (const auto& [key, pipeline] : pipelines) vkDestroyPipeline(device, pipeline, nullptr);
        for (const auto& b : buffers) { vkUnmapMemory(device, b.memory); vkDestroyBuffer(device, b.buffer, nullptr); vkFreeMemory(device, b.memory, nullptr); }
        vkDestroyFence(device, fence, nullptr); vkDestroyCommandPool(device, command_pool, nullptr);
        vkDestroyDescriptorPool(device, descriptor_pool, nullptr); vkDestroyPipelineLayout(device, layout, nullptr);
        vkDestroyDescriptorSetLayout(device, descriptors, nullptr); vkDestroyDevice(device, nullptr); vkDestroyInstance(instance, nullptr);
    }
    VkPipeline pipeline(const std::filesystem::path& path, std::vector<int32_t> values = {}) {
        const auto key = std::pair{path.string(), values};
        if (pipelines.contains(key)) return pipelines.at(key);
        // The harness covers hundreds of profiles on one synthetic device.
        // Its cache is larger than the bounded per-engine production cache.
        if (pipelines.size() >= 256U) {
            vkDestroyPipeline(device, pipelines.begin()->second, nullptr);
            pipelines.erase(pipelines.begin());
        }
        std::ifstream file(path, std::ios::binary | std::ios::ate);
        if (!file) throw std::runtime_error("cannot read " + path.string());
        const auto size = static_cast<size_t>(file.tellg());
        if (size % 4 != 0) throw std::runtime_error("invalid SPIR-V size");
        std::vector<uint32_t> words(size / 4); file.seekg(0); file.read(reinterpret_cast<char*>(words.data()), static_cast<std::streamsize>(size));
        VkShaderModuleCreateInfo mi{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO}; mi.codeSize = size; mi.pCode = words.data();
        VkShaderModule module{}; check(vkCreateShaderModule(device, &mi, nullptr, &module), "shader module");
        std::vector<VkSpecializationMapEntry> entries;
        for (size_t index = 0; index < values.size(); ++index)
            entries.push_back({static_cast<uint32_t>(index),
                static_cast<uint32_t>(index * sizeof(int32_t)), sizeof(int32_t)});
        VkSpecializationInfo spec{static_cast<uint32_t>(entries.size()), entries.data(),
            values.size() * sizeof(int32_t), values.data()};
        VkComputePipelineCreateInfo pi{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO}; pi.layout = layout;
        pi.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO; pi.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        pi.stage.module = module; pi.stage.pName = "main"; pi.stage.pSpecializationInfo = values.empty() ? nullptr : &spec;
        VkPipeline result{}; const auto status = vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &pi, nullptr, &result);
        vkDestroyShaderModule(device, module, nullptr); check(status, "compute pipeline");
        pipelines[key] = result; return result;
    }
    std::vector<float> run(VkPipeline pipeline, const std::vector<float>& input, const std::vector<float>& params, uint32_t width, uint32_t height, bool isp) {
        const size_t n = static_cast<size_t>(width) * height * 3;
        if (n + 16 > capacity || params.size() > capacity) throw std::runtime_error("test buffer too small");
        for (auto& b : buffers) std::fill(b.data, b.data + capacity, -31337.0F);
        std::copy(input.begin(), input.end(), buffers[0].data); std::copy(params.begin(), params.end(), buffers[3].data);
        check(vkResetCommandBuffer(command, 0), "reset command");
        VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO}; begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        check(vkBeginCommandBuffer(command, &begin), "begin command");
        VkMemoryBarrier upload{VK_STRUCTURE_TYPE_MEMORY_BARRIER}; upload.srcAccessMask = VK_ACCESS_HOST_WRITE_BIT; upload.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_HOST_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &upload, 0, nullptr, 0, nullptr);
        vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
        vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_COMPUTE, layout, 0, 1, &set, 0, nullptr);
        vkCmdDispatch(command, (width + 15) / 16, (height + 15) / 16, 1);
        VkMemoryBarrier download{VK_STRUCTURE_TYPE_MEMORY_BARRIER}; download.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT; download.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
        vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &download, 0, nullptr, 0, nullptr);
        check(vkEndCommandBuffer(command), "end command"); check(vkResetFences(device, 1, &fence), "reset fence");
        VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO}; submit.commandBufferCount = 1; submit.pCommandBuffers = &command;
        check(vkQueueSubmit(queue, 1, &submit, fence), "submit"); check(vkWaitForFences(device, 1, &fence, VK_TRUE, 10'000'000'000ULL), "wait");
        std::vector<float> output;
        for (size_t binding = 1; binding <= (isp ? 2U : 1U); ++binding) {
            const auto data = buffers[binding].data;
            for (size_t i = 0; i < n; ++i) if (!std::isfinite(data[i]) || data[i] == -31337.0F) throw std::runtime_error("nonfinite or unwritten output");
            for (size_t i = n; i < capacity; ++i) if (data[i] != -31337.0F) throw std::runtime_error("output guard overwritten");
            output.insert(output.end(), data, data + n);
        }
        return output;
    }
};

int main(int argc, char** argv) {
    try {
        if (argc != 2) throw std::runtime_error("Usage: test_preview_shaders SPIRV_DIRECTORY");
        Gpu gpu; const std::filesystem::path dir(argv[1]);
        size_t cases = 0, compared = 0; float maximum_error = 0; size_t different_bits = 0;
        const auto compare = [&](const char* name, uint32_t halo, const std::vector<float>& input, const std::vector<float>& params, uint32_t width, uint32_t height) {
            const bool isp = std::string(name) == "isp";
            const auto baseline = dir / (std::string(name) + "-baseline.spv");
            const auto current = dir / (std::string(name) + ".spv");
            const auto expected = gpu.run(gpu.pipeline(std::filesystem::exists(baseline) ? baseline : current), input, params, width, height, isp);
            std::vector<int32_t> settings;
            if (isp) {
                const auto values = phytoy::vulkan_detail::preview_isp_specialization(params);
                settings.assign(values.begin(), values.end());
            } else {
                const auto values = phytoy::vulkan_detail::preview_optics_specialization(params);
                settings.assign(values.begin(), values.end());
            }
            if (settings[0] != static_cast<int32_t>(halo)) throw std::runtime_error("specialization halo mismatch");
            for (const bool preview : {false, true}) {
                const auto shader = preview ? dir / (std::string(name) + "_preview.spv") : current;
                const auto actual = gpu.run(gpu.pipeline(shader, preview ? settings : std::vector<int32_t>{}), input, params, width, height, isp);
                for (size_t i = 0; i < expected.size(); ++i) {
                    const float error = std::abs(actual[i] - expected[i]); maximum_error = std::max(maximum_error, error);
                    if (std::memcmp(&actual[i], &expected[i], sizeof(float)) != 0) ++different_bits;
                    if (error > 2e-6F) throw std::runtime_error(std::string(name) + " output mismatch halo=" + std::to_string(halo) + " error=" + std::to_string(error));
                }
                compared += actual.size();
            }
            ++cases;
        };
        for (const auto& [width, height] : std::array{std::pair{1U, 1U}, std::pair{17U, 1U}, std::pair{16U, 16U}, std::pair{37U, 29U}}) {
            std::cout << "Checking " << width << 'x' << height << " shader output" << std::endl;
            const size_t n = static_cast<size_t>(width) * height;
            std::vector<float> rgb(n * 3), raw(n);
            for (size_t i = 0; i < rgb.size(); ++i) rgb[i] = 0.04F + 0.9F * static_cast<float>((i * 37 + i / 3) % 101) / 101.0F;
            for (size_t i = 0; i < n; ++i) raw[i] = 32.0F + 800.0F * static_cast<float>((i * 17 + i / width) % 103) / 103.0F;
            for (uint32_t kw = 1; kw <= 9; kw += 2) for (uint32_t kh = 1; kh <= 9; kh += 2) {
                std::vector<float> p{float(width), float(height), .12F, -.015F, .002F, -.003F, 1.02F, 1.0F, .985F, .2F, .1F, .15F, .03F, .1F, .08F, 2, float(kw), float(kh), 2, 2, .88F};
                for (uint32_t basis = 0; basis < 2; ++basis) {
                    float sum = 0; std::vector<float> kernel(kw * kh);
                    for (size_t i = 0; i < kernel.size(); ++i) sum += kernel[i] = 1.0F + static_cast<float>((i * 7 + basis) % 13);
                    for (float weight : kernel) p.push_back(weight / sum);
                }
                for (size_t i = 0; i < 24; ++i) p.push_back(.3F + static_cast<float>(i % 7) / 10.0F);
                compare("optics", phytoy::vulkan_detail::preview_optics_radius(kw, kh), rgb, p, width, height);
            }
            for (uint32_t bases : {1U, 8U}) for (uint32_t grid : {1U, 3U}) {
                const uint32_t kw = 3, kh = 9;
                std::vector<float> p{float(width), float(height), .12F, -.015F, .002F, -.003F,
                    1.02F, 1.0F, .985F, .2F, .1F, .15F, .03F, .1F, .08F,
                    float(bases), float(kw), float(kh), float(grid), float(grid), .88F};
                for (uint32_t basis = 0; basis < bases; ++basis) {
                    std::vector<float> kernel(kw * kh, 0.0F);
                    kernel[(basis * 7U) % kernel.size()] = 1.0F;
                    p.insert(p.end(), kernel.begin(), kernel.end());
                }
                for (size_t i = 0; i < bases * 3U * grid * grid; ++i)
                    p.push_back(.3F + static_cast<float>(i % 7) / 10.0F);
                compare("optics", 4, rgb, p, width, height);
            }
            for (float denoise : {0.0F, .125F, .375F, .625F, .875F, 1.0F})
                for (float sharpen : {0.0F, .125F, .375F, .625F, .875F, 1.0F})
                    for (int cfa = 0; cfa < 4; ++cfa) for (int mono = 0; mono < 2; ++mono) {
                        const float amount = sharpen == 0 ? 0 : (cfa == 1 ? -.2F : .45F);
                        std::vector<float> p{float(width), float(height), 32, 1023, float(cfa), 1.1F, 1, .9F, .85F, .1F, .05F, .03F, .92F, .05F, .07F, .08F, .85F, 3, 0, 0, .4F, .5F, 1, 1, denoise, amount, sharpen == 0 ? 4.0F : sharpen, float(mono)};
                        compare("isp", phytoy::vulkan_detail::preview_isp_halo(denoise, amount, sharpen), raw, p, width, height);
                    }
            for (uint32_t tones : {2U, 6U}) {
                std::vector<float> p{float(width), float(height), 32, 1023, 2, 1.1F, 1, .9F,
                    .85F, .1F, .05F, .03F, .92F, .05F, .07F, .08F, .85F, float(tones)};
                for (uint32_t point = 0; point < tones; ++point) {
                    const float x = float(point) / float(tones - 1U);
                    p.push_back(x); p.push_back(std::sqrt(x));
                }
                p.insert(p.end(), {1, -.2F, 1, 0});
                compare("isp", 8, raw, p, width, height);
            }
        }
        std::cout << "{\"passed\":true,\"cases\":" << cases << ",\"compared_floats\":" << compared << ",\"different_bits\":" << different_bits << ",\"maximum_abs_error\":" << maximum_error << "}\n";
        return 0;
    } catch (const std::exception& e) { std::cerr << e.what() << '\n'; return 1; }
}
