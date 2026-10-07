package com.phytoy.sample

/** Sanitized receipt fields only; payment methods and account data are not persisted. */
internal data class GalaxyReceiptClaims(
    val packageName: String,
    val itemId: String,
    val paymentId: String,
    val orderId: String,
    val itemType: String,
    val status: String,
    val mode: String,
    val consumed: String,
    val acknowledged: String,
) {
    fun accepts(expectedPackage: String, expectedItem: String, expectedPayment: String): Boolean =
        expectedPackage.isNotBlank() && packageName == expectedPackage && itemId == expectedItem &&
            expectedItem in StyleProducts.productIds.values && paymentId.isNotBlank() &&
            paymentId == expectedPayment && orderId.isNotBlank() && itemType.equals("Item", true) &&
            status == "success" && mode == "PRODUCTION" && consumed == "N" &&
            acknowledged in setOf("Y", "N")
}

internal data class GalaxyUnlock(val purchaseId: String, val itemId: String, val paymentId: String)
