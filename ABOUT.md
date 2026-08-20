# PhyToyEngine — Project About

> English first · [中文说明](#中文说明)

## GitHub About

**Recommended repository description**

> A profile-driven physical toy-camera imaging engine with optics, sensor-noise, and ISP simulation for reproducible mobile-camera rendering.

**Suggested topics**

`camera` · `computational-photography` · `image-simulation` · `image-signal-processing` · `camera-pipeline` · `android` · `vulkan` · `cpp` · `python` · `open-source`

## What is PhyToyEngine?

PhyToyEngine is an open-source, profile-driven imaging engine for building physical toy-camera looks on mobile devices. It takes an image from a host camera, converts it into a common scene-linear representation, and reconstructs the image formation process of a target toy camera through a fixed, inspectable pipeline:

```text
Host input → normalization → target optics → target sensor → target ISP → output image
```

The camera look is described by replaceable `HostCameraProfile` and `ToyCameraProfile` files. This separates camera calibration and creative tuning from engine code, making it possible to compare profiles, reproduce results, and contribute new camera models without changing the core runtime.

## Why does it exist?

Many camera filters only adjust pixels after capture. PhyToyEngine explores a more faithful approach: simulate the stages that create the image, including lens distortion, chromatic aberration, vignetting, sensor color-filter-array sampling, shot/read noise, fixed-pattern noise, ADC quantization, demosaicing, white balance, color conversion, tone mapping, denoising, and sharpening.

The goal is to make a physical toy-camera rendering model that is understandable, measurable, portable, and eventually suitable for Android and iOS commercial camera products.

## Current Alpha capabilities

- Python reference implementation for high-precision experimentation and regression tests.
- C++20 runtime with a stable C ABI for application integration.
- Portable CPU backend and an Android Vulkan compute backend.
- sRGB, BT.709 YUV420, and Bayer RAW host-input normalization.
- Target optics, digital sensor, ADC, and ISP stages with intermediate outputs.
- Validated camera profiles with schema checks and SHA-256 package integrity.
- Native command-line renderer and Python/C++/Vulkan conformance tests.
- Deterministic rendering for the same input, profile, and random seed.

The Alpha currently demonstrates one fixed-focus digital toy-camera model and one RAW-capable Android host profile. It is an engine core and research-quality implementation, not a finished camera application or a laboratory-calibrated commercial camera emulation.

## Who is it for?

PhyToyEngine is intended for:

- mobile-camera developers who want a reusable imaging core;
- computational-photography and image-signal-processing researchers;
- developers studying the relationship between optics, sensors, and image processing;
- contributors who want to add camera profiles, calibration data, backends, or test fixtures;
- product teams evaluating a physically grounded foundation for toy-camera applications.

## Roadmap toward a product engine

The next engineering milestones are real-camera calibration, chart and dark-frame validation, dynamic Camera2 metadata handling, zero-copy Android image buffers, performance and thermal validation at production resolutions, a Metal backend for iOS, and integration into complete Android/iOS applications.

## Contributing

Contributions are welcome. Useful contributions include:

- measured and legally redistributable host-camera profiles;
- measured toy-camera optics, sensor, and ISP parameters;
- reference images, calibration charts, dark frames, and statistical fixtures;
- CPU, Vulkan, and future Metal backend improvements;
- numerical tests, documentation, and reproducibility tooling.

Please do not submit camera samples, calibration data, or third-party assets unless you have the right to redistribute them. When adding a profile, document its measurement source, assumptions, units, and validation status.

## License

PhyToyEngine is released under the [MIT License](LICENSE). See the [README](README.md) for build instructions, the [Android Vulkan integration guide](docs/ANDROID_VULKAN.md), and the [Alpha acceptance report](docs/ALPHA_ACCEPTANCE.md).

---

# 中文说明

## GitHub 项目简介

**推荐的 GitHub About 英文描述**

> A profile-driven physical toy-camera imaging engine with optics, sensor-noise, and ISP simulation for reproducible mobile-camera rendering.

这句话适合直接放入 GitHub 仓库的 About / Description 中，突出项目定位、核心技术和可复现特性。

**推荐的仓库 Topics**

`camera` · `computational-photography` · `image-simulation` · `image-signal-processing` · `camera-pipeline` · `android` · `vulkan` · `cpp` · `python` · `open-source`

## PhyToyEngine 是什么？

PhyToyEngine 是一个开源、由 Profile 驱动的物理玩具相机成像引擎，目标是在移动设备上构建具有真实成像过程特征的玩具相机效果。引擎接收手机或其他宿主相机的图像，将其归一化为统一的场景线性表示，然后按照固定且可检查的流水线重建目标玩具相机的成像过程：

```text
宿主输入 → 输入归一化 → 目标镜头 → 目标传感器 → 目标 ISP → 输出图像
```

相机效果由可替换的 `HostCameraProfile` 和 `ToyCameraProfile` 描述。这样可以把相机标定和创意调参从引擎代码中分离出来，便于比较不同相机模型、复现实验结果，也便于贡献新的相机档案而无需修改核心 Runtime。

## 为什么要做这个项目？

许多相机滤镜只是在拍摄完成后对像素进行调整。PhyToyEngine 探索的是更加接近真实成像的方式：模拟产生图像的关键阶段，包括镜头畸变、横向色差、暗角、传感器 CFA 采样、散粒噪声、读出噪声、固定模式噪声、ADC 量化、去马赛克、白平衡、颜色转换、曲线映射、降噪和锐化。

项目最终希望形成一个可理解、可测量、可移植的物理玩具相机渲染基础，并逐步用于 Android 和 iOS 商业相机产品。

## 当前 Alpha 能力

- Python 高精度参考实现，用于算法研究、实验和回归测试。
- 支持稳定 C ABI 的 C++20 Runtime，方便移动端集成。
- 可移植 CPU 后端和 Android Vulkan Compute 后端。
- 支持 sRGB、BT.709 YUV420 和 Bayer RAW 宿主输入归一化。
- 支持目标镜头、数字传感器、ADC、ISP，以及逐阶段中间结果输出。
- 支持带 Schema 校验和 SHA-256 完整性检查的相机 Profile。
- 提供原生命令行渲染器，以及 Python/C++/Vulkan 一致性测试。
- 对相同输入、Profile 和随机种子提供确定性渲染。

当前 Alpha 主要验证了一台固定焦点数字玩具相机模型和一台支持 RAW 的 Android 宿主 Profile。它目前是引擎内核和研究级实现，不是已经完成的相机 App，也不是已经通过实验室完整标定的商业相机复刻产品。

## 适合哪些人？

PhyToyEngine 适合：

- 希望使用可复用成像核心的移动相机开发者；
- 计算摄影和 ISP 方向的研究者；
- 想学习镜头、传感器与图像处理关系的开发者；
- 希望贡献相机 Profile、标定数据、后端或测试向量的开源贡献者；
- 评估物理成像基础、准备开发玩具相机产品的团队。

## 面向产品引擎的下一步

下一阶段重点包括真实相机标定、色卡与暗场验证、动态 Camera2 元数据处理、Android 零拷贝图像缓冲区、生产分辨率下的性能和温控验证、iOS Metal 后端，以及完整 Android/iOS 相机应用集成。

## 如何参与贡献？

欢迎贡献以下内容：

- 经过测量且拥有合法再分发权的宿主相机 Profile；
- 经过测量的玩具相机镜头、传感器和 ISP 参数；
- 参考图片、色卡、暗场、平场和统计测试数据；
- CPU、Vulkan 以及未来 Metal 后端的改进；
- 数值测试、文档和可复现性工具。

请勿提交没有再分发授权的相机样张、标定数据或第三方素材。新增 Profile 时，请记录测量来源、假设、单位和验证状态。

## 开源协议

PhyToyEngine 采用 [MIT License](LICENSE) 开源协议。构建方法请参考 [README](README.md)，Android Vulkan 集成请参考 [Android Vulkan 文档](docs/ANDROID_VULKAN.md)，当前 Alpha 的验收情况请参考 [Alpha 验收报告](docs/ALPHA_ACCEPTANCE.md)。
