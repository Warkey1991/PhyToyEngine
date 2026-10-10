package com.phytoy.sample

import org.junit.Assert.*
import org.junit.Test

class PreviewPerformancePolicyTest {
    private val samsungSizes = listOf(
        PreviewSize(1920, 1440), PreviewSize(1440, 1080),
        PreviewSize(960, 720), PreviewSize(640, 480),
    )

    @Test fun cameraDoesNotStarveTwentyOrTwentyFourFpsWorkerWithFifteenFpsInput() {
        val ranges = listOf(PreviewFpsRange(15, 15), PreviewFpsRange(30, 30))
        for (target in listOf(20, 24, 30)) {
            assertEquals(PreviewFpsRange(30, 30), selectPreviewFpsRange(ranges, target))
        }
    }

    @Test fun lowCadenceUsesSmallestSufficientFixedCameraRate() {
        val ranges = listOf(PreviewFpsRange(30, 30), PreviewFpsRange(15, 15))
        for (target in listOf(5, 8, 10, 12, 15)) {
            assertEquals(PreviewFpsRange(15, 15), selectPreviewFpsRange(ranges, target))
        }
        assertNull(selectPreviewFpsRange(ranges, 0))
    }

    @Test fun variableAndLimitedCameraRangesRetainSupportedFallbacks() {
        assertEquals(PreviewFpsRange(15, 30), selectPreviewFpsRange(
            listOf(PreviewFpsRange(15, 15), PreviewFpsRange(15, 30)), 20,
        ))
        assertEquals(PreviewFpsRange(15, 15), selectPreviewFpsRange(
            listOf(PreviewFpsRange(15, 15)), 30,
        ))
        assertNull(selectPreviewFpsRange(emptyList(), 30))
    }

    @Test fun samsungWithout1280UsesNative1440InsteadOfUpscaling960() {
        assertEquals(PreviewSize(1440, 1080), selectPreviewSize(samsungSizes, 4.0 / 3, PreviewQualityTier.HIGH))
    }

    @Test fun cameraWithout1440RetainsIts1280Stream() {
        assertEquals(PreviewSize(1280, 960), selectPreviewSize(
            samsungSizes.filter { it.width < 1440 } + PreviewSize(1280, 960),
            4.0 / 3, PreviewQualityTier.HIGH,
        ))
    }

    @Test fun outputKeepsNativeShortEdgeInPortraitAndLandscape() {
        val cap = PreviewQualityTier.HIGH.maximumOutputShortEdge
        assertEquals(PreviewSize(1080, 1440), previewOutputSize(1080, 3f / 4f, cap))
        assertEquals(PreviewSize(1440, 1080), previewOutputSize(1440, 4f / 3f, cap))
        assertEquals(PreviewSize(720, 960), previewOutputSize(720, 3f / 4f, cap))
        assertEquals(PreviewSize(1080, 1920), previewOutputSize(1080, 9f / 16f, cap))
        assertEquals(PreviewSize(1920, 1080), previewOutputSize(1920, 16f / 9f, cap))
    }

    @Test fun wideStylesSelectFullHdAspectMatchedStream() {
        val sizes = samsungSizes + listOf(PreviewSize(1920, 1080), PreviewSize(1280, 720), PreviewSize(640, 360))
        assertEquals(PreviewSize(1920, 1080), selectPreviewSize(sizes, 16.0 / 9, PreviewQualityTier.HIGH))
    }

    @Test fun smallOnlyCameraStillUsesItsLargestStream() {
        assertEquals(PreviewSize(960, 720), selectPreviewSize(
            samsungSizes.filter { it.width <= 960 }, 4.0 / 3, PreviewQualityTier.HIGH,
        ))
    }

    @Test fun slowPreviewReducesCadenceWithoutLosingDetail() {
        val policy = PreviewPerformancePolicy()
        policy.startSession(0)
        for (step in 1..5) policy.observe(step * 500L, step * 3L, 202_000L, 0)
        assertEquals(30, policy.frameRateBudget)
        policy.observe(3_000L, 18L, 202_000L, 0)
        assertEquals(5, policy.frameRateBudget)
        for (step in 7..100) policy.observe(step * 500L, step * 3L, 202_000L, 0)
        assertEquals(PreviewSize(1440, 1080), selectPreviewSize(samsungSizes, 4.0 / 3, policy.tier))
        assertEquals(5, policy.frameRateBudget)
    }

    @Test fun fastPreviewAndRepeatedSnapshotsDoNotLowerCadence() {
        val policy = PreviewPerformancePolicy()
        policy.startSession(0)
        for (step in 1..100) policy.observe(step * 500L, step * 15L, 20_000L, 0)
        assertEquals(30, policy.frameRateBudget)
        policy.startSession(50_000L)
        for (step in 1..20) policy.observe(50_000L + step * 500L, 15L, 200_000L, 0)
        assertEquals(30, policy.frameRateBudget)
    }

    @Test fun oneSlowSampleOnlyTemporarilyLowersCadence() {
        val policy = PreviewPerformancePolicy()
        policy.startSession(0)
        for (step in 1..8) policy.observe(step * 500L, step * 10L, if (step == 6) 900_000L else 20_000L, 0)
        assertEquals(5, policy.frameRateBudget)
        for (step in 9..20) policy.observe(step * 500L, step * 10L, 20_000L, 0)
        assertEquals(30, policy.frameRateBudget)
        assertEquals(PreviewSize(1440, 1080), selectPreviewSize(samsungSizes, 4.0 / 3, policy.tier))
    }

    @Test fun samplingAfterCaptureRequiresFreshFramesAndWarmup() {
        val policy = slowPolicy()
        policy.suspendSampling(8_000L, 100L)
        for (step in 1..5) policy.observe(8_000L + step * 500L, 100L + step * 15L, 20_000L, 0)
        assertEquals(10, policy.frameRateBudget)
        policy.observe(11_000L, 190L, 20_000L, 0)
        assertEquals(30, policy.frameRateBudget)
    }

    @Test fun healthyFramesRestoreCadenceOnceSlowSamplesExpire() {
        val policy = slowPolicy()
        for (step in 9..14) policy.observe(step * 500L, step * 15L, 20_000L, 0)
        assertEquals(10, policy.frameRateBudget)
        for (step in 15..20) policy.observe(step * 500L, step * 15L, 20_000L, 0)
        assertEquals(30, policy.frameRateBudget)
    }

    @Test fun moderateThermalSamplesDoNotOverrideLearnedCadence() {
        val policy = slowPolicy()
        for (step in 9..30) policy.observe(step * 500L, step * 15L, 1_000L, 2)
        assertEquals(10, policy.frameRateBudget)
    }

    @Test fun stalledCountersCannotCountAsHealthyFrames() {
        val policy = slowPolicy()
        for (step in 9..100) policy.observe(step * 500L, 48L, 1_000L, 0)
        assertEquals(10, policy.frameRateBudget)
    }

    @Test fun reopeningKeepsLearnedCadenceAndClearStream() {
        val policy = slowPolicy()
        policy.startSession(5_000L)
        policy.observe(5_500L, 3L, 1_000L, 0)
        assertEquals(10, policy.frameRateBudget)
        assertEquals(PreviewSize(1440, 1080), selectPreviewSize(samsungSizes, 4.0 / 3, policy.tier))
    }

    private fun slowPolicy(): PreviewPerformancePolicy {
        val policy = PreviewPerformancePolicy()
        policy.startSession(0)
        for (step in 1..8) policy.observe(step * 500L, step * 6L, 90_000L, 0)
        assertEquals(10, policy.frameRateBudget)
        return policy
    }
}
