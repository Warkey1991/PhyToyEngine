package com.phytoy.sample

import android.app.Activity

/** Same camera UI, with ownership queried only from this build's store/account. */
internal interface CameraBillingController {
    fun currentState(): BillingSnapshot
    fun isUnlocked(style: CameraStyle): Boolean
    fun canPurchase(style: CameraStyle): Boolean
    fun onResume()
    fun restorePurchases()
    fun purchase(activity: Activity, style: CameraStyle)
    fun close()
}
