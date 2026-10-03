package com.phytoy.sample

internal enum class BillingConnection { DISCONNECTED, CONNECTING, READY, UNAVAILABLE, CLOSED }

internal data class BillingProduct(
    val style: CameraStyle,
    val productId: String,
    /** Exact localized Play price; never fabricate a fallback dollar amount. */
    val formattedPrice: String? = null,
    val currencyCode: String? = null,
    val priceAmountMicros: Long? = null,
    /** An eligible, permanent "unlock" purchase option is available for this account. */
    val eligible: Boolean = false,
    val unavailableStatusCode: Int? = null,
)

internal data class BillingSnapshot(
    val connection: BillingConnection = BillingConnection.DISCONNECTED,
    val products: Map<CameraStyle, BillingProduct> = StyleProducts.productIds.mapValues { (style, id) ->
        BillingProduct(style, id)
    },
    val unlockedStyles: Set<CameraStyle> = StyleProducts.freeStyles,
    val pendingStyles: Set<CameraStyle> = emptySet(),
    val busy: Boolean = false,
    val verificationConfigured: Boolean = false,
) {
    fun isUnlocked(style: CameraStyle): Boolean = style in unlockedStyles
    fun canPurchase(style: CameraStyle): Boolean = verificationConfigured &&
        connection == BillingConnection.READY && !busy && !isUnlocked(style) &&
        style !in pendingStyles && products[style]?.eligible == true
}

internal enum class BillingError {
    NOT_CONFIGURED, UNAVAILABLE, PRODUCT_UNAVAILABLE, NETWORK, VERIFICATION_FAILED,
    ACKNOWLEDGEMENT_FAILED, PURCHASE_FAILED, CACHE_WRITE_FAILED,
}

internal sealed interface BillingEvent {
    data class PurchaseCompleted(val style: CameraStyle) : BillingEvent
    data class Pending(val styles: Set<CameraStyle>) : BillingEvent
    data class RestoreCompleted(val unlockedStyles: Set<CameraStyle>, val pendingStyles: Set<CameraStyle>) : BillingEvent
    data class Revoked(val styles: Set<CameraStyle>) : BillingEvent
    data object Canceled : BillingEvent
    data class Error(
        val reason: BillingError,
        val responseCode: Int? = null,
        val subResponseCode: Int? = null,
        /** Diagnostic only; the activity maps reason/codes to localized product copy. */
        val debugMessage: String? = null,
    ) : BillingEvent
}
