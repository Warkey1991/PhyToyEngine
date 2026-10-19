package com.team.phytoy.camera

import android.content.Context

/** This edition ships with access included; no store, account, receipt or network is consulted. */
@Suppress("UNUSED_PARAMETER")
internal fun createBillingController(
    context: Context,
    onStateChanged: (BillingSnapshot) -> Unit = {},
    onEvent: (BillingEvent) -> Unit = {},
): CameraBillingController = FullAccessController()
