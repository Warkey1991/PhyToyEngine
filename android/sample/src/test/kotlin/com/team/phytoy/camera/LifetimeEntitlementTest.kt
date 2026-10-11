package com.team.phytoy.camera

import org.junit.Assert.*
import org.junit.Test

class LifetimeEntitlementTest {
    private val all = StyleProducts.ALL_CAMERAS_PRODUCT_ID
    private val digital = StyleProducts.productIds.getValue(CameraStyle.DIGITAL_01)
    private fun state(owned: Set<String> = emptySet(), pending: Set<String> = emptySet()) = BillingSnapshot(
        BillingConnection.READY, StyleProducts.emptyProducts().mapValues { (_, product) ->
            product.copy(formattedPrice = "€5,99", currencyCode = "EUR", eligible = true)
        }, owned, pending, verificationConfigured = true)

    @Test fun catalogHasFourSinglesAndOneIndependentLifetimeProduct() {
        assertEquals(5, StyleProducts.allProductIds.size)
        assertFalse(all in StyleProducts.productIds.values)
        assertNull(StyleProducts.styleForProduct(all))
    }
    @Test fun lifetimeUnlocksEveryCameraAndBlocksEveryRepurchase() {
        val state = state(setOf(all))
        assertEquals(CameraStyle.entries.toSet(), state.unlockedStyles)
        StyleProducts.allProductIds.forEach { assertFalse(state.canPurchase(it)) }
    }
    @Test fun futureCameraWithNoSingleSkuIsIncluded() {
        assertTrue(CameraEntitlementPolicy.canUse(null, false, setOf(all)))
        assertTrue(CameraEntitlementPolicy.canUse("future_camera", false, setOf(all)))
        assertFalse(CameraEntitlementPolicy.canUse(null, false, StyleProducts.productIds.values.toSet()))
    }
    @Test fun fourSinglesDoNotBecomeLifetimeOrUnlockFutureCameras() {
        val state = state(StyleProducts.productIds.values.toSet())
        assertFalse(state.allCamerasUnlocked)
        assertTrue(state.canPurchase(all))
        assertFalse(CameraEntitlementPolicy.canUse("future_camera", false, state.ownedProductIds))
    }
    @Test fun refundedLifetimeRetainsAnIndependentlyPurchasedSingle() {
        val state = state(setOf(all, digital)).copy(ownedProductIds = setOf(digital))
        assertTrue(state.isUnlocked(CameraStyle.DIGITAL_01))
        assertFalse(state.isUnlocked(CameraStyle.STREET_84))
        assertTrue(state.canPurchase(all))
    }
    @Test fun refundedSingleDoesNotRestrictActiveLifetime() {
        val state = state(setOf(all, digital)).copy(ownedProductIds = setOf(all))
        assertTrue(state.isUnlocked(CameraStyle.DIGITAL_01))
        assertFalse(state.canPurchase(digital))
    }
    @Test fun pendingLifetimeGrantsNothingAndBlocksAllOverlappingPayments() {
        val state = state(pending = setOf(all))
        assertEquals(StyleProducts.freeStyles, state.unlockedStyles)
        StyleProducts.allProductIds.forEach { assertFalse(state.canPurchase(it)) }
        assertEquals(CameraStyle.entries.toSet() - StyleProducts.freeStyles, state.pendingStyles)
    }
    @Test fun pendingSingleBlocksLifetimeButNotOtherSingles() {
        val state = state(pending = setOf(digital))
        assertFalse(state.canPurchase(all))
        assertFalse(state.canPurchase(digital))
        assertTrue(state.canPurchase("camera_plastic_82"))
    }
    @Test fun missingLifetimeProductDoesNotDisableAvailableSingles() {
        val state = state().copy(products = state().products - all)
        assertFalse(state.canPurchase(all))
        assertTrue(state.canPurchase(digital))
    }
    @Test fun pricesKeepStoreLocalizedTextWithoutDollarFallback() {
        assertEquals("€5,99", state().products.getValue(all).formattedPrice)
        assertNull(StyleProducts.emptyProducts().getValue(all).formattedPrice)
    }
    @Test fun playLedgerReconcilesLifetimeRefundAndRetainsSingles() {
        val ledger = BillingEntitlementLedger(StyleProducts.allProductIds)
        val full = BillingEntitlementLedger.Fact("full", setOf(all), BillingEntitlementLedger.Status.PURCHASED, true)
        val single = full.copy(token = "single", products = setOf(digital))
        assertTrue(ledger.grant(full, true)); assertTrue(ledger.grant(single, true))
        ledger.reconcile(listOf(single))
        assertEquals(setOf(digital), ledger.ownedProducts)
    }
    @Test fun pendingOrUnacknowledgedLifetimeNeverGrantsInPlayLedger() {
        val ledger = BillingEntitlementLedger(StyleProducts.allProductIds)
        val full = BillingEntitlementLedger.Fact("full", setOf(all), BillingEntitlementLedger.Status.PENDING, false)
        assertFalse(ledger.grant(full, true))
        assertFalse(ledger.grant(full.copy(status = BillingEntitlementLedger.Status.PURCHASED), true))
        assertTrue(ledger.ownedProducts.isEmpty())
    }
}
