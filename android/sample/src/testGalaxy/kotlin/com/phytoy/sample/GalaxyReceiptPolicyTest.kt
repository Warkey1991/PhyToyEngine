package com.phytoy.sample

import org.junit.Assert.*
import org.junit.Test

class GalaxyReceiptPolicyTest {
    private val app = "com.ycolor.team.phytoy.camera.android.galaxyapp"
    private val item = "camera_digital_01"
    private val payment = "payment-1"
    private fun receipt() = GalaxyReceiptClaims(app, item, payment, "order-1", "Item", "success", "PRODUCTION", "N", "Y")
    private fun accepts(receipt: GalaxyReceiptClaims) = receipt.accepts(app, item, payment)

    @Test fun productionPermanentReceiptIsAccepted() { assertTrue(accepts(receipt())) }
    @Test fun unconfiguredBuildCannotBuyEvenWithAStorePrice() {
        val style = CameraStyle.DIGITAL_01
        val snapshot = BillingSnapshot(connection = BillingConnection.READY,
            products = mapOf(style to BillingProduct(style, item, formattedPrice = "$9.99", eligible = true)),
            verificationConfigured = false)
        assertFalse(snapshot.canPurchase(style))
        assertFalse(snapshot.isUnlocked(style))
    }
    @Test fun otherPackageOrItemCannotUnlock() {
        assertFalse(accepts(receipt().copy(packageName = "com.other.app")))
        assertFalse(accepts(receipt().copy(itemId = "camera_plastic_82")))
        assertFalse(receipt().copy(itemId = "unknown").accepts(app, "unknown", payment))
    }
    @Test fun differentPaymentCannotReplayForCurrentPurchase() {
        assertFalse(accepts(receipt().copy(paymentId = "payment-2")))
        assertFalse(accepts(receipt().copy(paymentId = "")))
    }
    @Test fun testCancelledOrPendingReceiptsNeverUnlock() {
        assertFalse(accepts(receipt().copy(mode = "TEST")))
        assertFalse(accepts(receipt().copy(status = "cancel")))
        assertFalse(accepts(receipt().copy(status = "pending")))
        assertFalse(accepts(receipt().copy(status = "fail")))
    }
    @Test fun subscriptionsAndConsumedItemsNeverUnlockPermanentCameras() {
        assertFalse(accepts(receipt().copy(itemType = "Subscription")))
        assertFalse(accepts(receipt().copy(consumed = "Y")))
        assertFalse(accepts(receipt().copy(consumed = "")))
    }
    @Test fun missingOrderOrUnknownAcknowledgementFailsClosed() {
        assertFalse(accepts(receipt().copy(orderId = "")))
        assertFalse(accepts(receipt().copy(acknowledged = "")))
        assertTrue(accepts(receipt().copy(acknowledged = "N"))) // Controller must acknowledge before granting.
    }
}
