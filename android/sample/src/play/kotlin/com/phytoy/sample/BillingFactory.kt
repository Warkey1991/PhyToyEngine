package com.phytoy.sample

import android.content.Context

internal fun createBillingController(
    context: Context,
    onStateChanged: (BillingSnapshot) -> Unit = {},
    onEvent: (BillingEvent) -> Unit = {},
): CameraBillingController = PlayBillingController(context, onStateChanged = onStateChanged, onEvent = onEvent)
