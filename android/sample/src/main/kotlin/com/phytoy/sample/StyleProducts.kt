package com.phytoy.sample

/** IDs are registered separately per store/package. Play uses BUY "unlock"; Galaxy uses permanent Item acknowledgement. */
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
