package com.team.phytoy.camera

import android.content.Context

internal fun createBillingController(
    context: Context,
    onStateChanged: (BillingSnapshot) -> Unit = {},
    onEvent: (BillingEvent) -> Unit = {},
): CameraBillingController = GalaxyBillingController(context, onStateChanged = onStateChanged, onEvent = onEvent)
