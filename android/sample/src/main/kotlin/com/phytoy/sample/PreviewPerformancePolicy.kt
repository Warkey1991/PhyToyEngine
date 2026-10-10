package com.phytoy.sample

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Clear preview input limits; still capture sizes remain independent. */
internal enum class PreviewQualityTier(
    val maximumWidth: Int,
    val maximumHeight: Int,
    val maximumOutputShortEdge: Int,
) {
    // Samsung exposes 1440x1080 rather than 1280x960 for 4:3. Include its
    // native-size stream instead of excluding it and enlarging 960x720.
    HIGH(1920, 1080, 1080);

    val maximumPixels: Long get() = maximumWidth.toLong() * maximumHeight
}

internal data class PreviewSize(val width: Int, val height: Int) {
    val pixels: Long get() = width.toLong() * height
    val aspect: Double get() = width.toDouble() / height
}

internal data class PreviewFpsRange(val lower: Int, val upper: Int)

/** Supply enough fresh camera frames for the worker's learned processing cadence. */
internal fun selectPreviewFpsRange(supported: List<PreviewFpsRange>, target: Int): PreviewFpsRange? {
    if (target <= 0) return null
    val fixed = supported.filter { it.lower == it.upper }
    fixed.filter { it.upper >= target }.minByOrNull { it.upper }?.let { return it }
    return supported.filter { target in it.lower..it.upper }
        .minByOrNull { it.upper - it.lower }
        ?: supported.minByOrNull { abs(it.upper - target) }
}

internal fun selectPreviewSize(
    supported: List<PreviewSize>,
    captureAspect: Double,
    tier: PreviewQualityTier,
): PreviewSize {
    require(supported.isNotEmpty()) { "Camera has no PRIVATE output sizes" }
    val bounded = supported.filter {
        it.width <= tier.maximumWidth && it.height <= tier.maximumHeight &&
            it.pixels <= tier.maximumPixels
    }
    // Prefer a clear stream over a tiny exact aspect match. Small-only cameras
    // retain their largest usable stream, and presentation crops to the frame.
    val qualityFloor = minOf(960L * 540L, bounded.maxOfOrNull { it.pixels } ?: 0L)
    val clearStreams = bounded.filter { it.pixels >= qualityFloor }
    val aspectMatched = clearStreams.filter { abs(it.aspect - captureAspect) <= 0.015 }
    return aspectMatched.maxByOrNull { it.pixels }
        ?: clearStreams.minWithOrNull(
            compareBy<PreviewSize> { abs(it.aspect - captureAspect) }
                .thenByDescending { it.pixels },
        )
        ?: supported.minBy { it.pixels }
}

/** Limit the short edge in either orientation, avoiding a second landscape downscale. */
internal fun previewOutputSize(
    viewportWidth: Int,
    captureAspect: Float,
    maximumShortEdge: Int,
): PreviewSize {
    require(captureAspect.isFinite() && captureAspect > 0f)
    require(maximumShortEdge > 0)
    val maximumWidth = (maximumShortEdge * maxOf(1f, captureAspect)).roundToInt()
    val width = viewportWidth.coerceIn(1, maximumWidth)
    return PreviewSize(width, (width / captureAspect).roundToInt().coerceAtLeast(1))
}

/** Learns processing cadence without trading away preview detail or reopening the camera. */
internal class PreviewPerformancePolicy {
    val tier = PreviewQualityTier.HIGH
    var frameRateBudget = 30
        private set

    private val latencies = ArrayDeque<Long>()
    private var startedAt = 0L
    private var lastRenderedFrames = 0L
    private var baselineRenderedFrames = 0L
    private var lastEvaluationAt = 0L

    fun startSession(now: Long) {
        startedAt = now
        lastRenderedFrames = 0L
        baselineRenderedFrames = 0L
        lastEvaluationAt = now
        latencies.clear()
    }

    /** Ignore capture, hidden preview and thermal-limit samples, including stale counters. */
    fun suspendSampling(now: Long, renderedFrames: Long) {
        startedAt = now
        lastRenderedFrames = renderedFrames
        baselineRenderedFrames = renderedFrames
        lastEvaluationAt = now
        latencies.clear()
    }

    fun observe(now: Long, renderedFrames: Long, latencyUs: Long, thermalStatus: Int) {
        if (thermalStatus >= 2) {
            suspendSampling(now, renderedFrames)
            return
        }
        if (renderedFrames <= lastRenderedFrames || latencyUs <= 0L) return
        lastRenderedFrames = renderedFrames
        latencies.addLast(latencyUs)
        if (latencies.size > 10) latencies.removeFirst()
        if (now - startedAt < 3_000L || renderedFrames - baselineRenderedFrames < 12L ||
            latencies.size < 6 || now - lastEvaluationAt < 1_000L
        ) return
        lastEvaluationAt = now
        val sorted = latencies.sorted()
        val p95 = sorted[(ceil(sorted.size * 0.95).toInt() - 1).coerceAtLeast(0)]
        val capacity = (900_000L / p95).toInt().coerceIn(5, 30)
        frameRateBudget = listOf(30, 24, 20, 15, 12, 10, 8, 5).first { it <= capacity }
    }
}
