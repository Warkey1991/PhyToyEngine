package com.team.phytoy.camera

internal enum class BillingConnection { DISCONNECTED, CONNECTING, READY, UNAVAILABLE, CLOSED }

internal data class BillingProduct(
    val style: CameraStyle?,
    val productId: String,
    /** Exact localized price from this channel's store; never fabricate a fallback dollar amount. */
    val formattedPrice: String? = null,
    val currencyCode: String? = null,
    val priceAmountMicros: Long? = null,
    /** A permanent purchase is available for this store account. */
    val eligible: Boolean = false,
    val unavailableStatusCode: Int? = null,
)

internal data class BillingSnapshot(
    val connection: BillingConnection = BillingConnection.DISCONNECTED,
    val products: Map<String, BillingProduct> = StyleProducts.emptyProducts(),
    val ownedProductIds: Set<String> = emptySet(),
    val pendingProductIds: Set<String> = emptySet(),
    val busy: Boolean = false,
    val verificationConfigured: Boolean = false,
    /** Keep the remembered camera while authenticated offline receipts are loading. */
    val entitlementsLoaded: Boolean = true,
) {
    val allCamerasUnlocked: Boolean get() = StyleProducts.ALL_CAMERAS_PRODUCT_ID in ownedProductIds
    val unlockedStyles: Set<CameraStyle> get() = StyleProducts.unlockedStyles(ownedProductIds)
    val pendingStyles: Set<CameraStyle> get() = StyleProducts.pendingStyles(pendingProductIds)
    fun isUnlocked(style: CameraStyle): Boolean = style in unlockedStyles
    fun ownsProduct(productId: String): Boolean = productId in ownedProductIds ||
        (productId in StyleProducts.allProductIds && allCamerasUnlocked)
    fun hasPendingOverlap(productId: String): Boolean = productId in pendingProductIds ||
        StyleProducts.ALL_CAMERAS_PRODUCT_ID in pendingProductIds ||
        (productId == StyleProducts.ALL_CAMERAS_PRODUCT_ID && pendingProductIds.isNotEmpty())
    fun canPurchase(productId: String): Boolean = productId in StyleProducts.allProductIds &&
        verificationConfigured && connection == BillingConnection.READY && !busy &&
        !ownsProduct(productId) && !hasPendingOverlap(productId) && products[productId]?.eligible == true
}

internal enum class BillingError {
    NOT_CONFIGURED, UNAVAILABLE, PRODUCT_UNAVAILABLE, NETWORK, VERIFICATION_FAILED,
    ACKNOWLEDGEMENT_FAILED, PURCHASE_FAILED, CACHE_WRITE_FAILED,
}

internal sealed interface BillingEvent {
    data class PurchaseCompleted(val productId: String) : BillingEvent
    data class Pending(val productIds: Set<String>) : BillingEvent
    data class RestoreCompleted(val ownedProductIds: Set<String>, val pendingProductIds: Set<String>) : BillingEvent
    data class Revoked(val productIds: Set<String>) : BillingEvent
    data object Canceled : BillingEvent
    data class Error(
        val reason: BillingError,
        val responseCode: Int? = null,
        val subResponseCode: Int? = null,
        /** Diagnostic only; the activity maps reason/codes to localized product copy. */
        val debugMessage: String? = null,
    ) : BillingEvent
}
