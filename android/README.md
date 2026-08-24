# PhyToyEngine for Android

> English first · [中文说明](#中文说明)

This directory contains the Android Synthetic Alpha for PhyToyEngine: an Android Library
(AAR), a Kotlin API, and a Camera2 sample that sends real `AIMAGE_FORMAT_PRIVATE` frames
through the complete PhyToy Digital 01 Vulkan graph and presents the result directly to an
Android GPU Surface.

## Current milestone

- `sdk`: packages the C++ engine, JNI bridge, Kotlin API, and immutable `.ptp` profiles.
- `sample`: provides a full-screen Digital 01 camera UI, shutter state, MediaStore JPEG
  saving and last-photo review. Its `TextureView` remains only the Vulkan swapchain output,
  while Camera2 targets the engine-owned PRIVATE `AImageReader` surface exclusively.
- JNI: acquires the latest image and its sync fence, immediately queues it to a dedicated
  latest-frame worker, imports the camera buffer into Vulkan, reuses stable buffer IDs, skips
  CPU readback, records normalization/optics/sensor/ISP and the graphics pass into one
  command buffer, presents via Vulkan WSI, and exposes metrics without blocking the UI.
- Acceptance: verifies PRIVATE input format/usage, one submission, zero-copy input and one
  presented swapchain image per rendered frame, output dimensions, import-cache bounds,
  errors, and rolling P95 latency.

There is no raw Camera2 preview bypass in 0.3.0. If the profile graph or presentation fails,
the user does not receive an unprocessed fallback image disguised as an effect preview.

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
    outputSurface = previewSurface,
    outputRotationDegrees = 90,
)
```

Add only `engine.inputSurface` to Camera2. `previewSurface` belongs to Vulkan output and
must never be added as a Camera2 target:

```kotlin
cameraDevice.createCaptureSession(
    listOf(engine.inputSurface),
    captureSessionCallback,
    cameraHandler,
)

val request = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
    addTarget(engine.inputSurface)
}.build()
```

Poll `engine.snapshot()` for received/rendered/presented/dropped/error frames, swapchain
recreates and dimensions, rolling P50/P95, Vulkan submissions, imported buffer slots,
zero-copy frames, memory, and actual input format/usage. The native layer processes only
the newest pending frame, so `droppedFrames` counts replaced stale work; thermal cadence
skips are reported separately.
Always close the capture session and camera before closing the engine:

```kotlin
captureSession.close()
cameraDevice.close()
engine.close()
previewSurface.release()
```

### Still capture

Call `captureNextFrame()` from a background thread. The next admitted Camera2 frame runs
through the same Digital 01 Vulkan graph and preview presentation, with one additional
readback of its final sRGB pixels. Normal preview frames remain GPU-only and every frame,
including the captured one, still uses one Vulkan queue submission:

```kotlin
photoExecutor.execute {
    val photo = engine.captureNextFrame()
    // photo.argb8888 is photo.width × photo.height.
    // Apply photo.rotationDegrees before JPEG encoding.
}
```

The sample rotates the result, encodes JPEG at quality 95, publishes it to
`Pictures/PhyToy` through `MediaStore`, updates the lower-left thumbnail and opens the
system photo viewer when that thumbnail is tapped.

Profile assets are copied into app-private storage because the native engine validates and
opens packaged profile files. The default profiles are `host_generic_srgb.ptp` and the
product-balanced `toy_phytoy_digital_01_v1_1.ptp`; the immutable v1.0.0 package remains
available for regression comparisons.

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

Engine 0.3.0 passed 17/17 processed-preview checks on Samsung SM-S9210: 30 rendered,
submitted, zero-copy and presented frames, zero queue drops/errors, P50 52.622 ms, P95
59.161 ms, a stable 720×1560 output and zero swapchain recreations. The device was already
in Android thermal Light state from continuous USB testing, so the adaptive policy used
10 FPS. See `reports/android_camera2_sm_s9210_0_3_0_processed_preview/evaluation.json`.
An unplugged 30-minute run and the multi-vendor matrix remain later product gates.

## 中文说明

此目录是 PhyToyEngine 的 Android Synthetic Alpha，包含 Android AAR、Kotlin API 和
具备正式取景界面、快门、JPEG 保存与相册回看的 Camera2 示例 App。真实
`AIMAGE_FORMAT_PRIVATE` 帧通过 `AHardwareBuffer` 与 acquire
fence 零拷贝进入 Vulkan，依次执行归一化、PhyToy Digital 01 光学、传感器、ISP，最后
由 Vulkan 图形 Pass 写入 Android swapchain GPU Surface。

0.3.0 已移除原始 Camera2 预览旁路。Camera2 capture request 只允许加入引擎的
`inputSurface`；`TextureView` 对应的 Surface 只交给 Vulkan 展示处理后结果。完整计算和
展示仍保持每帧一次 queue submission，并记录 `renderedFrames`、`presentedFrames`、交换链
重建、输出尺寸、延迟、错误、内存与零拷贝指标。

`captureNextFrame()` 必须在后台线程调用。它让下一张 PRIVATE 帧继续以一次 Vulkan
submission 完成 Digital 01 处理和预览显示，同时仅对这张拍照帧读回最终 sRGB；普通预览
仍无 CPU readback。示例 App 将结果旋转后以质量 95 写入 `Pictures/PhyToy`，并更新缩略图。

Vulkan 引擎默认最多处理 15 FPS，并通过 Android Thermal API 自动调整为 Normal
15 FPS、Light 10 FPS、Moderate 5 FPS、Severe 3 FPS、Critical 0 FPS。温控主动跳过的帧单独记录为
`throttledFrames`，不会混入表示处理管线跟不上的 `droppedFrames`。可通过
`PhyToyCameraSession.open(..., processingFrameRateLimit = 15, thermalAdaptive = true)`
配置正常档位或关闭自动温控。

示例 App 将 Camera2 AE 固定为 15 FPS，避免在只显示引擎结果时生成无效的 24–30 FPS
原始帧，从而降低厂商 Camera HAL/ISP 的持续负载；SDK 本身不直接控制调用方 request。

构建命令：

```bash
android/gradlew -p android :sdk:assembleRelease :sample:assembleBenchmark
```

接入时把预览 `Surface` 和相机相对旋转角传给 `PhyToyCameraSession.open(...)`，只将
`inputSurface` 加入 Camera2 capture session 和 repeating request，使用 `snapshot()`
读取运行指标，并按 Camera2 → engine → preview Surface 的顺序关闭资源。连接并解锁
Android 真机后，可运行：

```bash
tools/run_android_camera2_smoke.sh
```

验收脚本会安装 App、授权并启动相机，检查真实 PRIVATE Buffer 格式、GPU sampled usage、
连续帧、错误数、P95 延迟、每帧一次提交、零拷贝与逐帧显示、交换链和 Buffer 导入缓存，并将报告保存到
`reports/android_camera2_product_alpha/`。

引擎 0.3.0 已于 2026-08-24 在 Samsung SM-S9210 上通过 17/17 项效果预览验收：
30 帧渲染/提交/零拷贝/显示一致，队列丢帧 0、错误 0，P50 52.622 ms、P95 59.161 ms，
输出 720×1560，交换链重建 0。该轮开始时设备已因连续 USB 调试处于 Light 温控档，
引擎按策略运行于 10 FPS。报告位于
`reports/android_camera2_sm_s9210_0_3_0_processed_preview/evaluation.json`。这表示用户可见
Synthetic Alpha 链路已通过短时真机验收；30 分钟不插电长稳测试和多厂商矩阵仍需完成。
