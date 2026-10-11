package com.team.phytoy.camera

import android.app.Activity

/** Same camera UI, with ownership queried only from this build's store/account. */
internal interface CameraBillingController {
    fun currentState(): BillingSnapshot
    fun isUnlocked(style: CameraStyle): Boolean
    fun canPurchase(productId: String): Boolean
    fun onPause() {}
    fun onResume()
    fun restorePurchases()
    fun purchase(activity: Activity, productId: String)
    fun close()
}
