package com.team.phytoy.camera

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
            expectedItem in StyleProducts.allProductIds && paymentId.isNotBlank() &&
            paymentId == expectedPayment && orderId.isNotBlank() && itemType.equals("Item", true) &&
            status == "success" && mode == "PRODUCTION" && consumed == "N" &&
            acknowledged in setOf("Y", "N")
}

internal data class GalaxyUnlock(val purchaseId: String, val itemId: String, val paymentId: String)

/** Service failures are not proof that a previously verified purchase was refunded. */
internal object GalaxyReceiptServicePolicy {
    fun checkStatus(status: String, errorCode: Int?) {
        when {
            status == "cancel" -> throw IllegalArgumentException("Purchase canceled")
            status == "fail" && errorCode in setOf(9135, 9153) -> throw IllegalArgumentException("Purchase not found")
            status != "success" -> throw java.io.IOException("Receipt service unavailable")
        }
    }
    fun requireComplete(claims: GalaxyReceiptClaims) {
        if (listOf(claims.packageName, claims.itemId, claims.paymentId, claims.orderId,
                claims.itemType, claims.mode, claims.consumed, claims.acknowledged).any(String::isBlank)) {
            throw java.io.IOException("Incomplete receipt service response")
        }
    }
}
