package com.phytoy.sample

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.samsung.android.sdk.iap.lib.constants.HelperDefine
import com.samsung.android.sdk.iap.lib.helper.IapHelper
import com.samsung.android.sdk.iap.lib.vo.ErrorVo
import com.samsung.android.sdk.iap.lib.vo.ProductVo
import java.util.ArrayDeque
import java.util.concurrent.Executors

/**
 * Galaxy-only permanent Item purchases. SDK requests are serialized because IapHelper is a singleton.
 * Verify Samsung's production receipt, acknowledge (never consume), durably cache, then grant.
 * Successful owned-list queries revoke absent purchases; service failures preserve offline access.
 */
internal class GalaxyBillingController(
    context: Context,
    var onStateChanged: (BillingSnapshot) -> Unit = {},
    var onEvent: (BillingEvent) -> Unit = {},
) : CameraBillingController {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val verifier = GalaxyReceiptVerifier(appContext.packageName)
    private val cache = GalaxyPurchaseCache(appContext)
    private val configured = BuildConfig.GALAXY_BILLING_CONFIGURED
    private val helper by lazy {
        IapHelper.getInstance(appContext).apply {
            setOperationMode(HelperDefine.OperationMode.OPERATION_MODE_PRODUCTION)
            setShowErrorDialog(false)
        }
    }
    private val products = StyleProducts.productIds.mapValues { (style, id) -> BillingProduct(style, id) }.toMutableMap()
    private val unlocks = linkedMapOf<String, GalaxyUnlock>()
    private var connection = BillingConnection.DISCONNECTED
    private var busy = false
    private var refreshWanted = false
    private var restoreAwaiting = false
    private var restoreFailed = false
    private var generation = 0L
    @Volatile private var closed = false
    private var state = BillingSnapshot(verificationConfigured = configured)
    private var timeout: Runnable? = null

    init {
        checkMainThread()
        // Cache IO and Keystore work stay off the camera/UI thread.
        if (configured) {
            busy = true
            state = snapshot()
            worker.execute {
                val restored = cache.read()
                dispatch {
                    restored.forEach { unlocks[it.purchaseId] = it }
                    busy = false
                    publish()
                    if (refreshWanted) refresh()
                }
            }
        }
    }

    override fun currentState(): BillingSnapshot = state
    override fun isUnlocked(style: CameraStyle): Boolean = state.isUnlocked(style)
    override fun canPurchase(style: CameraStyle): Boolean = state.canPurchase(style)

    override fun onResume() {
        checkMainThread()
        if (closed || !configured) return
        refreshWanted = true
        if (!busy) refresh()
    }

    override fun restorePurchases() {
        checkMainThread()
        if (closed) return
        if (!configured) { emit(BillingEvent.Error(BillingError.NOT_CONFIGURED)); return }
        restoreAwaiting = true
        restoreFailed = false
        refreshWanted = true
        if (!busy) refresh()
    }

    private fun begin(): Long {
        busy = true
        val request = ++generation
        timeout?.let(handler::removeCallbacks)
        timeout = Runnable {
            if (!valid(request)) return@Runnable
            generation++ // Late callbacks cannot grant a timed-out purchase.
            busy = false
            connection = BillingConnection.UNAVAILABLE
            refreshWanted = false
            restoreAwaiting = false
            emit(BillingEvent.Error(BillingError.NETWORK))
            publish()
        }.also { handler.postDelayed(it, 60_000L) }
        publish()
        return request
    }

    private fun finish(request: Long) {
        if (!valid(request)) return
        timeout?.let(handler::removeCallbacks)
        timeout = null
        busy = false
        publish()
        if (refreshWanted) {
            refresh()
        } else if (restoreAwaiting) {
            restoreAwaiting = false
            if (!restoreFailed) emit(BillingEvent.RestoreCompleted(state.unlockedStyles, emptySet()))
        }
    }

    private fun refresh() {
        if (closed || busy || !configured) return
        refreshWanted = false
        if (restoreAwaiting) restoreFailed = false
        connection = BillingConnection.CONNECTING
        val request = begin()
        runCatching {
            helper.getProductsDetails(StyleProducts.productIds.values.joinToString(",")) { error, details ->
                dispatch callback@{
                    if (!valid(request)) return@callback
                    if (error.errorCode == IapHelper.IAP_ERROR_NONE) {
                        updateProducts(details.orEmpty())
                        connection = BillingConnection.READY
                    } else {
                        products.replaceAll { _, product -> product.copy(eligible = false) }
                        connection = BillingConnection.UNAVAILABLE
                        // Restoration can still succeed even if product/price availability fails.
                    }
                    publish()
                    queryOwned(request)
                }
            }
        }.onFailure { fail(request, BillingError.UNAVAILABLE) }
    }

    private fun updateProducts(details: List<ProductVo>) {
        StyleProducts.productIds.forEach { (style, id) ->
            val product = details.firstOrNull { it.itemId == id }
            val eligible = product != null && product.type.equals("item", true) &&
                !product.itemPriceString.isNullOrBlank() && !product.currencyCode.isNullOrBlank()
            products[style] = BillingProduct(style, id,
                formattedPrice = product?.itemPriceString?.takeIf { eligible },
                currencyCode = product?.currencyCode?.takeIf { eligible }, eligible = eligible)
        }
    }

    private fun queryOwned(request: Long) {
        runCatching {
            val sent = helper.getOwnedList(HelperDefine.PRODUCT_TYPE_ITEM) { error, owned ->
                dispatch callback@{
                    if (!valid(request)) return@callback
                    if (error.errorCode != IapHelper.IAP_ERROR_NONE) {
                        fail(request, errorReason(error), error.errorCode)
                        return@callback
                    }
                    val candidates = owned.orEmpty().mapNotNull { item ->
                        if (item.itemId !in StyleProducts.productIds.values || !item.type.equals("item", true) ||
                            item.purchaseId.isNullOrBlank() || item.paymentId.isNullOrBlank()) null
                        else GalaxyUnlock(item.purchaseId, item.itemId, item.paymentId)
                    }.distinctBy { it.purchaseId }
                    val active = candidates.map { it.purchaseId }.toSet()
                    val before = unlockedStyles()
                    unlocks.keys.retainAll(active) // This successful query alone is authoritative for absence.
                    val revoked = before - unlockedStyles()
                    if (revoked.isNotEmpty()) emit(BillingEvent.Revoked(revoked))
                    publish()
                    persist(request) {
                        verifyNext(request, ArrayDeque(candidates), purchasedStyle = null)
                    }
                }
            }
            if (!sent) fail(request, BillingError.UNAVAILABLE)
        }.onFailure { fail(request, BillingError.UNAVAILABLE) }
    }

    override fun purchase(activity: Activity, style: CameraStyle) {
        checkMainThread()
        if (closed) return
        if (isUnlocked(style)) { emit(BillingEvent.PurchaseCompleted(style)); return }
        if (!configured) { emit(BillingEvent.Error(BillingError.NOT_CONFIGURED)); return }
        if (!canPurchase(style) || activity.isFinishing || activity.isDestroyed) {
            emit(BillingEvent.Error(BillingError.PRODUCT_UNAVAILABLE)); return
        }
        val id = checkNotNull(StyleProducts.productIds[style])
        val request = begin()
        // Refresh the exact current price before opening the store's payment sheet.
        runCatching {
            helper.getProductsDetails(id) { error, details -> dispatch callback@{
                if (!valid(request)) return@callback
                val product = details.orEmpty().firstOrNull { it.itemId == id && it.type.equals("item", true) &&
                    !it.itemPriceString.isNullOrBlank() }
                if (error.errorCode != IapHelper.IAP_ERROR_NONE || product == null || activity.isFinishing || activity.isDestroyed) {
                    fail(request, BillingError.PRODUCT_UNAVAILABLE, error.errorCode)
                    return@callback
                }
                startPayment(request, style, id)
            } }
        }.onFailure { fail(request, BillingError.PURCHASE_FAILED) }
    }

    private fun startPayment(request: Long, style: CameraStyle, id: String) {
        runCatching {
            val sent = helper.startPayment(id) { error, purchase -> dispatch callback@{
                if (!valid(request)) return@callback
                when (error.errorCode) {
                    IapHelper.IAP_PAYMENT_IS_CANCELED -> { emit(BillingEvent.Canceled); finish(request) }
                    IapHelper.IAP_ERROR_ALREADY_PURCHASED -> { refreshWanted = true; finish(request) }
                    IapHelper.IAP_ERROR_NONE -> {
                        if (purchase == null || purchase.itemId != id || purchase.purchaseId.isNullOrBlank() ||
                            purchase.paymentId.isNullOrBlank() || !purchase.type.equals("item", true)) {
                            fail(request, BillingError.VERIFICATION_FAILED)
                        } else {
                            // No grant from an SDK success callback alone.
                            verifyNext(request, ArrayDeque(listOf(GalaxyUnlock(purchase.purchaseId, id, purchase.paymentId))), style)
                        }
                    }
                    else -> fail(request, errorReason(error), error.errorCode)
                }
            } }
            if (!sent) fail(request, BillingError.PURCHASE_FAILED)
        }.onFailure { fail(request, BillingError.PURCHASE_FAILED) }
    }

    private fun verifyNext(request: Long, queue: ArrayDeque<GalaxyUnlock>, purchasedStyle: CameraStyle?) {
        if (!valid(request)) return
        val unlock = queue.pollFirst()
        if (unlock == null) { finish(request); return }
        worker.execute {
            val result = runCatching { verifier.verify(unlock) }
            dispatch callback@{
                if (!valid(request)) return@callback
                val claims = result.getOrNull()
                if (claims == null) {
                    restoreFailed = true
                    val reason = if (result.exceptionOrNull() is IllegalArgumentException)
                        BillingError.VERIFICATION_FAILED else BillingError.NETWORK
                    // A negative validated receipt revokes; network failures preserve prior verified cache.
                    if (reason == BillingError.VERIFICATION_FAILED) {
                        val before = unlockedStyles()
                        unlocks.remove(unlock.purchaseId)
                        val revoked = before - unlockedStyles()
                        if (revoked.isNotEmpty()) emit(BillingEvent.Revoked(revoked))
                        publish()
                    }
                    emit(BillingEvent.Error(reason))
                    persist(request) { verifyNext(request, queue, purchasedStyle) }
                } else if (claims.acknowledged == "Y") {
                    saveGrant(request, unlock, queue, purchasedStyle)
                } else {
                    acknowledge(request, unlock, queue, purchasedStyle)
                }
            }
        }
    }

    private fun acknowledge(request: Long, unlock: GalaxyUnlock, queue: ArrayDeque<GalaxyUnlock>, style: CameraStyle?) {
        runCatching {
            val sent = helper.acknowledgePurchases(unlock.purchaseId) { error, acknowledgements -> dispatch callback@{
                if (!valid(request)) return@callback
                val acknowledged = error.errorCode == IapHelper.IAP_ERROR_NONE && acknowledgements.orEmpty().any {
                    it.purchaseId == unlock.purchaseId && it.statusCode in setOf(0, 4)
                }
                if (acknowledged) {
                    // Status 4 may mean consumed OR already acknowledged. Recheck the receipt so
                    // a consumed Item can never be mistaken for a permanent camera unlock.
                    worker.execute {
                        val confirmed = runCatching { verifier.verify(unlock) }
                        dispatch verified@{
                            if (!valid(request)) return@verified
                            if (confirmed.getOrNull()?.acknowledged == "Y") {
                                saveGrant(request, unlock, queue, style)
                            } else {
                                restoreFailed = true
                                emit(BillingEvent.Error(BillingError.ACKNOWLEDGEMENT_FAILED))
                                verifyNext(request, queue, style)
                            }
                        }
                    }
                }
                else {
                    restoreFailed = true
                    emit(BillingEvent.Error(BillingError.ACKNOWLEDGEMENT_FAILED, error.errorCode))
                    verifyNext(request, queue, style)
                }
            } }
            if (!sent) fail(request, BillingError.ACKNOWLEDGEMENT_FAILED)
        }.onFailure { fail(request, BillingError.ACKNOWLEDGEMENT_FAILED) }
    }

    private fun saveGrant(request: Long, unlock: GalaxyUnlock, queue: ArrayDeque<GalaxyUnlock>, style: CameraStyle?) {
        val candidate = unlocks.toMutableMap().apply { put(unlock.purchaseId, unlock) }
        worker.execute {
            val written = cache.write(candidate.values)
            dispatch callback@{
                if (!valid(request)) return@callback
                if (written) {
                    unlocks[unlock.purchaseId] = unlock
                    publish()
                    if (style != null && StyleProducts.styleForProduct(unlock.itemId) == style) {
                        emit(BillingEvent.PurchaseCompleted(style))
                    }
                } else {
                    restoreFailed = true
                    emit(BillingEvent.Error(BillingError.CACHE_WRITE_FAILED))
                }
                verifyNext(request, queue, style)
            }
        }
    }

    private fun persist(request: Long, next: () -> Unit) {
        val current = unlocks.values.toList()
        worker.execute {
            val written = cache.write(current)
            dispatch {
                if (valid(request)) {
                    if (!written) { restoreFailed = true; emit(BillingEvent.Error(BillingError.CACHE_WRITE_FAILED)) }
                    next()
                }
            }
        }
    }

    private fun fail(request: Long, reason: BillingError, code: Int? = null) {
        if (!valid(request)) return
        restoreFailed = true
        connection = BillingConnection.UNAVAILABLE
        emit(BillingEvent.Error(reason, code))
        finish(request)
    }

    private fun errorReason(error: ErrorVo): BillingError = when (error.errorCode) {
        IapHelper.IAP_ERROR_NETWORK_NOT_AVAILABLE, IapHelper.IAP_ERROR_IOEXCEPTION_ERROR,
        IapHelper.IAP_ERROR_SOCKET_TIMEOUT, IapHelper.IAP_ERROR_CONNECT_TIMEOUT -> BillingError.NETWORK
        IapHelper.IAP_ERROR_PRODUCT_DOES_NOT_EXIST, IapHelper.IAP_ERROR_NOT_EXIST_LOCAL_PRICE -> BillingError.PRODUCT_UNAVAILABLE
        else -> BillingError.UNAVAILABLE
    }

    private fun unlockedStyles(): Set<CameraStyle> = StyleProducts.freeStyles +
        unlocks.values.mapNotNull { StyleProducts.styleForProduct(it.itemId) }
    private fun snapshot() = BillingSnapshot(connection, products.toMap(), unlockedStyles(),
        busy = busy, verificationConfigured = configured)
    private fun publish() {
        val updated = snapshot()
        if (state != updated) { state = updated; onStateChanged(updated) }
    }
    private fun valid(request: Long): Boolean = !closed && busy && generation == request
    private fun emit(event: BillingEvent) { if (!closed) onEvent(event) }
    private fun dispatch(block: () -> Unit) {
        if (closed) return
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else handler.post { if (!closed) block() }
    }
    private fun checkMainThread() { check(Looper.myLooper() == Looper.getMainLooper()) }

    override fun close() {
        checkMainThread()
        if (closed) return
        // Overwrite any in-flight stale grant before shutting down the serial cache worker.
        val current = unlocks.values.toList()
        if (configured) worker.execute { cache.write(current) }
        closed = true
        generation++
        handler.removeCallbacksAndMessages(null)
        worker.shutdown()
        busy = false
        connection = BillingConnection.CLOSED
        state = snapshot()
        onStateChanged = {}
        onEvent = {}
    }
}
