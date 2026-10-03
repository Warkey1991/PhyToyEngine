package com.phytoy.sample

/** Stable Play product IDs. Configure one permanent BUY option named "unlock" per product. */
internal object StyleProducts {
    const val PURCHASE_OPTION_ID = "unlock"
    val freeStyles: Set<CameraStyle> = setOf(CameraStyle.HARINEZUMI_2PP, CameraStyle.HARINEZUMI_2PP_MONO)
    val productIds: Map<CameraStyle, String> = linkedMapOf(
        CameraStyle.DIGITAL_01 to "camera_digital_01",
        CameraStyle.PLASTIC_82 to "camera_plastic_82",
        CameraStyle.STREET_84 to "camera_street_84",
        CameraStyle.FISHEYE_05 to "camera_fisheye_05",
    )
    fun styleForProduct(productId: String): CameraStyle? = productIds.entries
        .firstOrNull { it.value == productId }?.key
}
