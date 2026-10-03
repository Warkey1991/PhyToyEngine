package com.phytoy.sample

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/**
 * One controller per activity/application; all public methods and UI callbacks use the main thread.
 * Call onResume() on every foreground return and close() on destruction. Never consume these products.
 * Only verified PURCHASED purchases with successful acknowledgement grant a paid style.
 * Offline receipts remain usable until a successful Play ownership query removes/revokes them.
 */
internal class PlayBillingController(
    context: Context,
    private val verifier: PlayPurchaseVerifier = RsaPlayPurchaseVerifier(BuildConfig.PLAY_BILLING_PUBLIC_KEY),
    var onStateChanged: (BillingSnapshot) -> Unit = {},
    var onEvent: (BillingEvent) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val ledger = BillingEntitlementLedger(StyleProducts.productIds.values.toSet())
    private val cache = PlayPurchaseCache(appContext, verifier.cacheIdentity)
    private val receipts = mutableMapOf<String, CachedPlayPurchase>()
    private val products = StyleProducts.productIds.mapValues { (style, id) -> BillingProduct(style, id) }.toMutableMap()
    private val verificationJobs = mutableMapOf<String, Long>()
    private data class PendingGrant(val purchase: Purchase, val proof: SignedPlayPurchase, val job: Long)
    private val pendingGrants = mutableMapOf<String, PendingGrant>()
    private var cacheRevision = 0L
    private var cacheWriteInFlight = false
    private var cacheWriteDirty = false
    private var nextJob = 0L
    private var activeTokens: Set<String>? = null
    private var purchaseRevision = 0L
    private var connection = BillingConnection.DISCONNECTED
    private var connecting = false
    private var productQueryInFlight = false
    private var purchaseQueryInFlight = false
    private var queryAgain = false
    private var restoreAwaiting = false
    private var flowBusy = false
    private var flowLaunched = false
    private var requestedStyle: CameraStyle? = null
    private var launchRequest = 0L
    private var retryAttempt = 0
    private var retryScheduled = false
    @Volatile private var closed = false
    private var state = BillingSnapshot(verificationConfigured = verifier.configured)
    private val retry = Runnable {
        retryScheduled = false
        if (!closed) connectOrRefresh()
    }
    private val billingClient = BillingClient.newBuilder(appContext)
        .setListener { result, purchases -> dispatch { handlePurchasesUpdated(result, purchases.orEmpty()) } }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    init {
        checkMainThread()
        if (verifier.configured) {
            cache.read().forEach { cached ->
                runCatching {
                    val purchase = Purchase(cached.proof.signedJson, cached.proof.signature)
                    if (cached.acknowledgedByApp && validPurchaseStructure(purchase) &&
                        verifier.verifyCached(cached.proof) && ledger.grant(fact(purchase, acknowledged = true), true)
                    ) receipts[purchase.purchaseToken] = cached
                }
            }
        }
        state = buildSnapshot()
    }

    fun currentState(): BillingSnapshot = state
    fun isUnlocked(style: CameraStyle): Boolean = state.isUnlocked(style)
    fun canPurchase(style: CameraStyle): Boolean = state.canPurchase(style)

    fun onResume() {
        checkMainThread()
        if (closed) return
        if (flowLaunched) {
            // A foreground return can precede the purchase-update callback.
            flowBusy = false
            flowLaunched = false
        }
        resetRetry()
        connectOrRefresh()
    }

    fun restorePurchases() {
        checkMainThread()
        if (closed) return
        if (!verifier.configured) {
            emit(BillingEvent.Error(BillingError.NOT_CONFIGURED))
            return
        }
        restoreAwaiting = true
        resetRetry()
        connectOrRefresh()
    }

    fun purchase(activity: Activity, style: CameraStyle) {
        checkMainThread()
        if (closed) return
        if (isUnlocked(style)) {
            emit(BillingEvent.PurchaseCompleted(style))
            return
        }
        if (!verifier.configured) {
            emit(BillingEvent.Error(BillingError.NOT_CONFIGURED))
            return
        }
        if (style in state.pendingStyles) {
            emit(BillingEvent.Pending(setOf(style)))
            return
        }
        val productId = StyleProducts.productIds[style]
        if (productId == null || !billingClient.isReady || !state.canPurchase(style)) {
            emit(BillingEvent.Error(if (productId == null || state.products[style]?.eligible == false)
                BillingError.PRODUCT_UNAVAILABLE else BillingError.UNAVAILABLE))
            if (!billingClient.isReady) connectOrRefresh()
            return
        }
        requestedStyle = style
        flowBusy = true
        val request = ++launchRequest
        val activityReference = WeakReference(activity)
        publish()
        // Query fresh details immediately before checkout. Persist prices/proofs, never ProductDetails.
        queryProductDetails(setOf(style)) { result, details ->
            if (request != launchRequest || !flowBusy) return@queryProductDetails
            val host = activityReference.get()
            val detail = details.firstOrNull { it.productId == productId }
            val offer = detail?.let(::selectOffer)
            if (result.responseCode != BillingClient.BillingResponseCode.OK || host == null ||
                host.isFinishing || host.isDestroyed || detail == null || offer == null
            ) {
                flowBusy = false
                requestedStyle = null
                publish()
                emit(error(result, if (result.responseCode == BillingClient.BillingResponseCode.OK)
                    BillingError.PRODUCT_UNAVAILABLE else BillingError.PURCHASE_FAILED))
                return@queryProductDetails
            }
            val params = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(
                BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(detail).setOfferToken(checkNotNull(offer.offerToken)).build(),
            )).build()
            val launched = billingClient.launchBillingFlow(host, params)
            if (launched.responseCode == BillingClient.BillingResponseCode.OK) {
                flowLaunched = true
            } else {
                flowBusy = false
                if (launched.responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
                    queryPurchases()
                } else {
                    requestedStyle = null
                    emit(error(launched, BillingError.PURCHASE_FAILED))
                }
            }
            publish()
        }
    }

    fun close() {
        checkMainThread()
        if (closed) return
        // A running write may contain an older receipt set. Queue the latest desired
        // state before shutdown so a stale callback cannot leave a revoked proof behind.
        if (cacheWriteDirty || cacheWriteInFlight) {
            val latest = candidateReceipts()
            worker.execute { cache.write(latest.values.toList()) }
        }
        closed = true
        launchRequest++
        mainHandler.removeCallbacksAndMessages(null)
        billingClient.endConnection()
        verificationJobs.clear()
        worker.shutdown() // Already queued receipt writes finish in order.
        connection = BillingConnection.CLOSED
        connecting = false
        flowBusy = false
        productQueryInFlight = false
        purchaseQueryInFlight = false
        state = buildSnapshot()
        onStateChanged = {}
        onEvent = {}
    }

    private fun connectOrRefresh() {
        if (closed || !verifier.configured) return
        if (cacheWriteDirty) flushReceipts()
        if (billingClient.isReady) {
            connection = BillingConnection.READY
            queryCatalog()
            queryPurchases()
            publish()
            return
        }
        if (connecting || billingClient.connectionState == BillingClient.ConnectionState.CONNECTING) return
        connecting = true
        connection = BillingConnection.CONNECTING
        publish()
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) = dispatch {
                connecting = false
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    connection = BillingConnection.READY
                    resetRetry()
                    queryCatalog()
                    queryPurchases()
                } else {
                    connection = BillingConnection.UNAVAILABLE
                    if (restoreAwaiting) {
                        restoreAwaiting = false
                        emit(error(result, BillingError.UNAVAILABLE))
                    }
                    scheduleRetry(result.responseCode)
                }
                publish()
            }

            override fun onBillingServiceDisconnected() = dispatch {
                connecting = false
                connection = BillingConnection.DISCONNECTED
                // Billing 9 reconnects when an API is used. Foreground onResume also
                // starts a fresh connection; do not race it from this callback.
                publish()
            }
        })
    }

    private fun queryCatalog() {
        if (productQueryInFlight || !billingClient.isReady) return
        productQueryInFlight = true
        publish()
        queryProductDetails(StyleProducts.productIds.keys) { result, _ ->
            productQueryInFlight = false
            if (result.responseCode != BillingClient.BillingResponseCode.OK) scheduleRetry(result.responseCode)
            publish()
        }
    }

    private fun queryProductDetails(styles: Set<CameraStyle>, done: (BillingResult, List<ProductDetails>) -> Unit) {
        val params = QueryProductDetailsParams.newBuilder().setProductList(styles.mapNotNull { style ->
            StyleProducts.productIds[style]?.let { id -> QueryProductDetailsParams.Product.newBuilder()
                .setProductId(id).setProductType(BillingClient.ProductType.INAPP).build() }
        }).build()
        billingClient.queryProductDetailsAsync(params) { result, queried -> dispatch {
            if (billingClient.isReady) connection = BillingConnection.READY
            styles.forEach { style ->
                val id = checkNotNull(StyleProducts.productIds[style])
                val detail = queried.productDetailsList.firstOrNull { it.productId == id }
                val offer = detail?.let(::selectOffer)
                products[style] = BillingProduct(
                    style, id, offer?.formattedPrice, offer?.priceCurrencyCode, offer?.priceAmountMicros,
                    eligible = result.responseCode == BillingClient.BillingResponseCode.OK && offer != null,
                    unavailableStatusCode = queried.unfetchedProductList.firstOrNull { it.productId == id }?.statusCode,
                )
            }
            publish()
            done(result, queried.productDetailsList)
        } }
    }

    private fun selectOffer(details: ProductDetails): ProductDetails.OneTimePurchaseOfferDetails? =
        details.oneTimePurchaseOfferDetailsList.orEmpty().firstOrNull { offer ->
            offer.purchaseOptionId == StyleProducts.PURCHASE_OPTION_ID && offer.offerId.isNullOrBlank() &&
                offer.rentalDetails == null && offer.preorderDetails == null &&
                !offer.offerToken.isNullOrBlank() && !offer.formattedPrice.isNullOrBlank()
        }

    private fun queryPurchases() {
        if (!billingClient.isReady) return
        if (purchaseQueryInFlight) {
            queryAgain = true
            return
        }
        purchaseQueryInFlight = true
        val revision = purchaseRevision
        publish()
        val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
        billingClient.queryPurchasesAsync(params) { result, purchases -> dispatch {
            if (billingClient.isReady) connection = BillingConnection.READY
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                // An older query must not revoke a purchase just delivered by the update listener.
                val authoritative = revision == purchaseRevision
                if (!authoritative) queryAgain = true
                processPurchases(purchases, authoritative)
            } else {
                if (restoreAwaiting) {
                    restoreAwaiting = false
                    emit(error(result, BillingError.NETWORK))
                }
                scheduleRetry(result.responseCode)
            }
            purchaseQueryInFlight = false
            publish()
            if (queryAgain) {
                queryAgain = false
                queryPurchases()
            } else finishRestoreIfReady()
        } }
    }

    private fun handlePurchasesUpdated(result: BillingResult, purchases: List<Purchase>) {
        purchaseRevision++
        flowBusy = false
        flowLaunched = false
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                processPurchases(purchases, authoritative = false)
                if (purchases.isEmpty()) queryPurchases()
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                requestedStyle = null
                emit(BillingEvent.Canceled)
            }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> queryPurchases()
            else -> {
                requestedStyle = null
                emit(error(result, BillingError.PURCHASE_FAILED))
                scheduleRetry(result.responseCode)
            }
        }
        publish()
    }

    private fun processPurchases(purchases: List<Purchase>, authoritative: Boolean) {
        val before = ledger.ownedProducts
        if (authoritative) {
            val facts = purchases.map { fact(it) }
            activeTokens = facts.filter { it.status == BillingEntitlementLedger.Status.PURCHASED }.map { it.token }.toSet()
            ledger.reconcile(facts)
            val hadStaleJobs = verificationJobs.keys.any { it !in checkNotNull(activeTokens) }
            verificationJobs.keys.retainAll(checkNotNull(activeTokens))
            pendingGrants.keys.retainAll(checkNotNull(activeTokens))
            val removed = receipts.keys.removeAll { it !in checkNotNull(activeTokens) }
            if (removed || hadStaleJobs) persistReceipts()
        }
        purchases.forEach { purchase ->
            val fact = fact(purchase)
            val pendingBefore = ledger.pendingProducts
            ledger.updatePending(fact)
            when (fact.status) {
                BillingEntitlementLedger.Status.PENDING -> {
                    val styles = styles(fact.products)
                    if (styles.isNotEmpty() && (!authoritative || pendingBefore != ledger.pendingProducts)) {
                        emit(BillingEvent.Pending(styles))
                    }
                }
                BillingEntitlementLedger.Status.PURCHASED -> {
                    if (!authoritative) activeTokens = activeTokens?.plus(purchase.purchaseToken)
                    verifyAndAcknowledge(purchase)
                }
                BillingEntitlementLedger.Status.UNSPECIFIED -> Unit
            }
        }
        val revoked = styles(before - ledger.ownedProducts)
        if (revoked.isNotEmpty()) emit(BillingEvent.Revoked(revoked))
        publish()
    }

    private fun verifyAndAcknowledge(purchase: Purchase) {
        if (styles(purchase.products.toSet()).isEmpty()) return
        if (!verifier.configured || !validPurchaseStructure(purchase)) {
            rejectPurchase(purchase, BillingError.VERIFICATION_FAILED)
            return
        }
        val token = purchase.purchaseToken
        val job = ++nextJob
        verificationJobs[token] = job
        val proof = SignedPlayPurchase(purchase.originalJson, purchase.signature)
        worker.execute {
            val verified = runCatching { verifier.verify(proof) }.getOrDefault(false)
            dispatch {
                if (!currentJob(token, job)) return@dispatch
                if (!verified) {
                    verificationJobs.remove(token)
                    rejectPurchase(purchase, BillingError.VERIFICATION_FAILED)
                    finishRestoreIfReady()
                    return@dispatch
                }
                if (purchase.isAcknowledged) {
                    grantPurchase(purchase, proof, job)
                } else {
                    val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(token).build()
                    billingClient.acknowledgePurchase(params) { result -> dispatch acknowledged@{
                        if (!currentJob(token, job)) return@acknowledged
                        if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                            grantPurchase(purchase, proof, job)
                        } else {
                            verificationJobs.remove(token)
                            restoreAwaiting = false
                            emit(error(result, BillingError.ACKNOWLEDGEMENT_FAILED))
                            scheduleRetry(result.responseCode)
                            publish()
                            finishRestoreIfReady()
                        }
                    } }
                }
            }
        }
    }

    private fun grantPurchase(purchase: Purchase, proof: SignedPlayPurchase, job: Long) {
        val token = purchase.purchaseToken
        if (!currentJob(token, job)) return
        pendingGrants[token] = PendingGrant(purchase, proof, job)
        persistReceipts() // Keep the verification job until this candidate is durable.
    }

    private fun rejectPurchase(purchase: Purchase, reason: BillingError) {
        // A failed restore must not later report an empty or successful restore
        // when the remaining verification/cache jobs drain.
        restoreAwaiting = false
        val before = ledger.ownedProducts
        verificationJobs.remove(purchase.purchaseToken)
        ledger.revoke(purchase.purchaseToken)
        val hadPending = pendingGrants.remove(purchase.purchaseToken) != null
        if (receipts.remove(purchase.purchaseToken) != null || hadPending) persistReceipts()
        val revoked = styles(before - ledger.ownedProducts)
        if (revoked.isNotEmpty()) emit(BillingEvent.Revoked(revoked))
        requestedStyle?.let { if (it in styles(purchase.products.toSet())) requestedStyle = null }
        emit(BillingEvent.Error(reason))
        publish()
    }

    private fun validPurchaseStructure(purchase: Purchase): Boolean =
        purchase.packageName == appContext.packageName && purchase.purchaseToken.isNotBlank() &&
            purchase.quantity == 1 && purchase.products.size == 1 &&
            purchase.purchaseState == Purchase.PurchaseState.PURCHASED

    private fun fact(purchase: Purchase, acknowledged: Boolean = purchase.isAcknowledged) =
        BillingEntitlementLedger.Fact(purchase.purchaseToken, purchase.products.toSet(), when (purchase.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> BillingEntitlementLedger.Status.PURCHASED
            Purchase.PurchaseState.PENDING -> BillingEntitlementLedger.Status.PENDING
            else -> BillingEntitlementLedger.Status.UNSPECIFIED
        }, acknowledged)

    private fun currentJob(token: String, job: Long): Boolean = !closed && verificationJobs[token] == job &&
        (activeTokens == null || token in checkNotNull(activeTokens))

    private fun persistReceipts() {
        cacheRevision++
        cacheWriteDirty = true
        flushReceipts()
    }

    private fun candidateReceipts(): Map<String, CachedPlayPurchase> = receipts.toMutableMap().apply {
        pendingGrants.forEach { (token, grant) ->
            if (currentJob(token, grant.job)) put(token, CachedPlayPurchase(grant.proof, acknowledgedByApp = true))
        }
    }

    private fun flushReceipts() {
        if (closed || cacheWriteInFlight || !cacheWriteDirty) return
        pendingGrants.entries.removeAll { (token, grant) -> !currentJob(token, grant.job) }
        val revision = cacheRevision
        val grants = pendingGrants.toMap()
        val candidate = candidateReceipts()
        cacheWriteInFlight = true
        publish()
        worker.execute {
            val written = cache.write(candidate.values.toList())
            dispatch {
                cacheWriteInFlight = false
                if (revision != cacheRevision || grants.any { (token, grant) -> !currentJob(token, grant.job) }) {
                    // A refund/new query arrived during IO. Never grant the old
                    // candidate, and overwrite it with the latest desired receipts.
                    cacheWriteDirty = true
                    flushReceipts()
                    return@dispatch
                }
                if (!written) {
                    grants.forEach { (token, _) ->
                        verificationJobs.remove(token)
                        pendingGrants.remove(token)
                    }
                    cacheRevision++
                    cacheWriteDirty = true
                    restoreAwaiting = false
                    emit(BillingEvent.Error(BillingError.CACHE_WRITE_FAILED))
                    scheduleRetry(BillingClient.BillingResponseCode.ERROR)
                    publish()
                    return@dispatch
                }
                cacheWriteDirty = false
                val completed = mutableSetOf<CameraStyle>()
                grants.forEach { (token, grant) ->
                    if (!currentJob(token, grant.job)) return@forEach
                    if (ledger.grant(fact(grant.purchase, acknowledged = true), signatureVerified = true)) {
                        receipts[token] = CachedPlayPurchase(grant.proof, acknowledgedByApp = true)
                        completed += styles(grant.purchase.products.toSet())
                    }
                    verificationJobs.remove(token)
                    pendingGrants.remove(token)
                }
                publish()
                requestedStyle?.takeIf { it in completed }?.let { style ->
                    requestedStyle = null
                    emit(BillingEvent.PurchaseCompleted(style))
                }
                finishRestoreIfReady()
            }
        }
    }

    private fun finishRestoreIfReady() {
        if (restoreAwaiting && !purchaseQueryInFlight && !queryAgain && verificationJobs.isEmpty() &&
            !cacheWriteInFlight && !cacheWriteDirty) {
            restoreAwaiting = false
            emit(BillingEvent.RestoreCompleted(state.unlockedStyles, state.pendingStyles))
        }
    }

    private fun buildSnapshot() = BillingSnapshot(
        connection = connection,
        products = products.toMap(),
        unlockedStyles = StyleProducts.freeStyles + styles(ledger.ownedProducts),
        pendingStyles = styles(ledger.pendingProducts),
        busy = connecting || productQueryInFlight || purchaseQueryInFlight || flowBusy ||
            cacheWriteInFlight || verificationJobs.isNotEmpty(),
        verificationConfigured = verifier.configured,
    )

    private fun publish() {
        val updated = buildSnapshot()
        if (updated != state) {
            state = updated
            onStateChanged(updated)
        }
    }

    private fun styles(ids: Set<String>): Set<CameraStyle> = ids.mapNotNull(StyleProducts::styleForProduct).toSet()
    private fun emit(event: BillingEvent) { if (!closed) onEvent(event) }
    private fun error(result: BillingResult, reason: BillingError) = BillingEvent.Error(
        reason, result.responseCode, result.onPurchasesUpdatedSubResponseCode, result.debugMessage,
    )

    private fun scheduleRetry(responseCode: Int) {
        val transient = responseCode in setOf(BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE, BillingClient.BillingResponseCode.NETWORK_ERROR,
            BillingClient.BillingResponseCode.ERROR)
        if (!transient || retryScheduled || retryAttempt >= 6 || closed) return
        val delay = minOf(30_000L, 1_000L shl retryAttempt++)
        retryScheduled = true
        mainHandler.postDelayed(retry, delay)
    }

    private fun resetRetry() {
        mainHandler.removeCallbacks(retry)
        retryScheduled = false
        retryAttempt = 0
    }

    private fun dispatch(block: () -> Unit) {
        if (closed) return
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post { if (!closed) block() }
    }

    private fun checkMainThread() { check(Looper.myLooper() == Looper.getMainLooper()) { "Billing must run on the main thread" } }
}
