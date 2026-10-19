package com.team.phytoy.camera

import android.app.Activity

/** A bundled entitlement, independent of store receipts and persisted settings. */
internal class FullAccessController : CameraBillingController {
    private val access = BillingSnapshot(
        connection = BillingConnection.READY,
        products = emptyMap(),
        ownedProductIds = setOf(StyleProducts.ALL_CAMERAS_PRODUCT_ID),
        entitlementsLoaded = true,
    )

    override fun currentState(): BillingSnapshot = access
    override fun isUnlocked(style: CameraStyle): Boolean = access.isUnlocked(style)
    override fun canPurchase(productId: String): Boolean = false
    override fun onResume() = Unit
    override fun restorePurchases() = Unit
    override fun purchase(activity: Activity, productId: String) = Unit
    override fun close() = Unit
}
