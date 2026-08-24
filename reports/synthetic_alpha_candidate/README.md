# Synthetic Alpha SM-S9210 Acceptance

> English first · [中文](#中文)

The `phytoy.synthetic_alpha.v1` contract passed all 101 automated checks on a Samsung
SM-S9210 / Adreno 750 on 2026-08-21. The complete machine-readable result is
[`evaluation.json`](evaluation.json), with device provenance in [`device.json`](device.json).

| Suite | Result |
|---|---:|
| 720p preview, 300 frames | P50 32.146 ms · P95 38.3359 ms |
| 12 MP final, 5 frames | P50 292.107 ms · P95 295.212 ms |
| 12 MP Vulkan working memory | 336,000,788 bytes |
| 12 MP peak RSS | 495,264 KiB |
| 720p endurance | 10,000/10,000 frames · 0 KiB positive RSS growth |
| Maximum endurance thermal status | 2 (moderate, contract maximum) |
| Queue/resource invariants | One submit per frame · zero allocations/imports after warmup |
| Deterministic conformance | Python vs CPU/Vulkan/Vulkan-AHB: all five stages pass |
| Digital 01 sensor statistics | All paths pass 2% mean / 5% variance limits |
| Fixed-seed repeatability | CPU/Vulkan/Vulkan-AHB all pass |

The AHardwareBuffer tests use a synthetic RGBA hardware buffer allocated on the physical
device. They validate external-memory import, synchronization, import caching, zero-copy
sampling, and the full Vulkan graph. They do not yet certify an actual Camera2
`AIMAGE_FORMAT_PRIVATE` BufferQueue or its driver-specific external YCbCr format. That final
capture-session check belongs to the Android app integration milestone.

---

# 中文

`phytoy.synthetic_alpha.v1` 协议已于 2026-08-21 在 Samsung SM-S9210 / Adreno 750
上通过全部 101 项自动检查。完整机器可读结果见 [`evaluation.json`](evaluation.json)，
设备信息见 [`device.json`](device.json)。

720p 预览 300 帧 P50 为 32.146 ms、P95 为 38.3359 ms；12MP 成片 P50 为
292.107 ms；720p 长稳完整处理 10,000 帧，预热后无新增资源分配或 Buffer 导入，
RSS 没有正增长，最大热状态为协议允许的 2。Python、CPU、Vulkan float32 和 Vulkan
AHardwareBuffer 的确定性阶段、随机均值/方差及固定 seed 重复性均通过。

当前 AHardwareBuffer 验收使用真机上分配的合成 RGBA Hardware Buffer，已覆盖外部
内存导入、同步、缓存和 Vulkan 全图，但尚未覆盖真实 Camera2
`AIMAGE_FORMAT_PRIVATE` BufferQueue 的驱动专用 YCbCr 外部格式。该项应在 Android
App 集成里完成最终拍摄会话验收。
