# PhyToyEngine for Android

> English first · [中文说明](#中文说明)

This directory contains the first Android product-integration milestone for
PhyToyEngine: an Android Library (AAR), a Kotlin API, and a Camera2 sample app that sends
real `AIMAGE_FORMAT_PRIVATE` frames directly into the Vulkan engine through an
`AHardwareBuffer`.

## Current milestone

- `sdk`: packages the C++ engine, JNI bridge, Kotlin API, and immutable `.ptp` profiles.
- `sample`: opens a back-facing Camera2 device and targets both a `TextureView` and the
  engine-owned PRIVATE `AImageReader` surface.
- JNI: acquires the latest image and its sync fence, immediately queues it to a dedicated
  latest-frame worker, imports the camera buffer into Vulkan, reuses stable buffer IDs, skips
  preview-only CPU readback, and exposes runtime metrics without blocking the UI thread.
- Acceptance: verifies real PRIVATE input format/usage, one Vulkan submission per rendered
  frame, zero-copy camera input, import-cache bounds, errors, and rolling P95 latency.

The sample currently displays the original Camera2 stream while the engine processes the
same frames off-screen. A processed GPU output surface is the next milestone; therefore,
this build validates the production input path but is not yet the final camera UI.

## Requirements

- Android Studio / Android Gradle Plugin 9.3.1
- JDK 17
- Android SDK 37
- Android NDK 28.2.13676358
- An arm64 device with Vulkan 1.1 and external `AHardwareBuffer` image support
- Android 8.0 (API 26) or newer

## Build

From the repository root:

```bash
android/gradlew -p android :sdk:assembleRelease :sample:assembleBenchmark
```

Outputs:

```text
android/sdk/build/outputs/aar/sdk-release.aar
android/sample/build/outputs/apk/benchmark/sample-benchmark.apk
```

## SDK integration

Add the `:sdk` module to an Android project during source development, or publish/copy the
AAR and add it as a dependency. Create one session for the selected Camera2 stream size:

```kotlin
val engine = PhyToyCameraSession.open(
    context = this,
    width = 1280,
    height = 720,
)
```

Add `engine.inputSurface` to the same Camera2 capture session and capture request as the
preview surface:

```kotlin
cameraDevice.createCaptureSession(
    listOf(previewSurface, engine.inputSurface),
    captureSessionCallback,
    cameraHandler,
)

val request = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
    addTarget(previewSurface)
    addTarget(engine.inputSurface)
}.build()
```

Poll `engine.snapshot()` for received/rendered/dropped/error frames, rolling P50/P95,
Vulkan submissions, imported buffer slots, zero-copy frames, memory, and the actual input
`AImage`/buffer format and usage. The native layer processes only the newest pending frame,
so `droppedFrames` counts intentionally replaced stale work instead of blocking Camera2.
Always close the capture session and camera before closing the engine:

```kotlin
captureSession.close()
cameraDevice.close()
engine.close()
```

Profile assets are copied into app-private storage because the native engine validates and
opens packaged profile files. The default profiles are `host_generic_srgb.ptp` and
`toy_phytoy_digital_01_v1.ptp`.

## Real-device smoke acceptance

Connect and unlock one Android device, enable USB debugging, then run:

```bash
tools/run_android_camera2_smoke.sh
```

The script builds and installs a release-optimized, debug-signed benchmark sample, grants
camera permission, starts the capture,
waits for at least 30 rendered frames, and writes evidence to
`reports/android_camera2_product_alpha/`. The versioned thresholds are in
`acceptance/android_camera2_product_alpha_v1.json`.

## 中文说明

此目录是 PhyToyEngine 的第一阶段 Android 产品接入工程，包含 Android AAR、Kotlin
API 和 Camera2 示例 App。示例会创建 `AIMAGE_FORMAT_PRIVATE` 的 NDK `AImageReader`，
通过 `AHardwareBuffer` 和 acquire fence 把真实相机帧直接交给 Vulkan 引擎，避免 CPU
侧复制输入。

当前已实现 SDK 封装、Camera2 输入 Surface、Profile 打包、Buffer 槽位复用、每帧一次
Vulkan 提交，以及延迟/错误/内存/零拷贝指标。示例画面暂时显示原始 Camera2 预览，
引擎在后台处理相同帧；下一阶段会把处理结果直接输出到 GPU 预览 Surface，所以当前版
用于验证生产输入链路，还不是最终相机界面。

构建命令：

```bash
android/gradlew -p android :sdk:assembleRelease :sample:assembleBenchmark
```

接入时调用 `PhyToyCameraSession.open(...)`，将 `inputSurface` 加入 Camera2 capture
session 和 repeating request，使用 `snapshot()` 读取运行指标，并在关闭 Camera2 后调用
`close()`。连接并解锁 Android 真机后，可运行：

```bash
tools/run_android_camera2_smoke.sh
```

验收脚本会安装 App、授权并启动相机，检查真实 PRIVATE Buffer 格式、GPU sampled usage、
连续帧、错误数、P95 延迟、每帧一次提交、零拷贝帧数和 Buffer 导入缓存，并将报告保存到
`reports/android_camera2_product_alpha/`。

引擎 0.2.1 已于 2026-08-24 在 Samsung SM-S9210 上通过 11/11 项冒烟验收：30/30 帧
完成处理、丢弃 0、错误 0、P50 18.367 ms、P95 20.322 ms、30/30 帧零拷贝。报告位于
`reports/android_camera2_sm_s9210_0_2_1/evaluation.json`。这表示实时输入链路已经通过
短时真机验收；30 分钟长稳测试和多厂商设备矩阵仍需完成。
