package com.team.phytoy.camera

/** IDs are registered separately per store/package. Play uses BUY "unlock"; Galaxy uses permanent Item acknowledgement. */
internal object StyleProducts {
    const val PURCHASE_OPTION_ID = "unlock"
    const val ALL_CAMERAS_PRODUCT_ID = "camera_all_lifetime"
    val freeStyles: Set<CameraStyle> = setOf(CameraStyle.HARINEZUMI_2PP, CameraStyle.HARINEZUMI_2PP_MONO)
    val productIds: Map<CameraStyle, String> = linkedMapOf(
        CameraStyle.DIGITAL_01 to "camera_digital_01",
        CameraStyle.PLASTIC_82 to "camera_plastic_82",
        CameraStyle.STREET_84 to "camera_street_84",
        CameraStyle.FISHEYE_05 to "camera_fisheye_05",
    )
    val allProductIds: Set<String> = productIds.values.toSet() + ALL_CAMERAS_PRODUCT_ID
    fun styleForProduct(productId: String): CameraStyle? = productIds.entries
        .firstOrNull { it.value == productId }?.key
    fun unlockedStyles(ownedProductIds: Set<String>): Set<CameraStyle> = CameraStyle.entries.filter { style ->
        CameraEntitlementPolicy.canUse(productIds[style], style in freeStyles, ownedProductIds)
    }.toSet()
    fun pendingStyles(pendingProductIds: Set<String>): Set<CameraStyle> =
        if (ALL_CAMERAS_PRODUCT_ID in pendingProductIds) CameraStyle.entries.toSet() - freeStyles
        else pendingProductIds.mapNotNull(::styleForProduct).toSet()
    fun emptyProducts(): Map<String, BillingProduct> = allProductIds.associateWith { id ->
        BillingProduct(styleForProduct(id), id)
    }
}

/** Keep the lifetime purchase identity, so a future camera is unlocked without another receipt. */
internal object CameraEntitlementPolicy {
    fun canUse(cameraProductId: String?, free: Boolean, ownedProductIds: Set<String>): Boolean =
        free || StyleProducts.ALL_CAMERAS_PRODUCT_ID in ownedProductIds ||
            (cameraProductId != null && cameraProductId in ownedProductIds)
}
