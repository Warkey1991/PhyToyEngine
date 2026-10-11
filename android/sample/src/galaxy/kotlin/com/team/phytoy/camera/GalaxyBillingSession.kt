package com.team.phytoy.camera

import java.util.ArrayDeque

internal data class GalaxyReply<T>(val value: T? = null, val error: BillingError? = null, val code: Int? = null)
internal sealed interface GalaxyPayment {
    data class Paid(val receipt: GalaxyUnlock) : GalaxyPayment
    data object Canceled : GalaxyPayment
    data object AlreadyOwned : GalaxyPayment
}
internal interface GalaxyStore {
    fun products(ids: Set<String>, done: (GalaxyReply<List<BillingProduct>>) -> Unit)
    fun owned(done: (GalaxyReply<List<GalaxyUnlock>>) -> Unit)
    fun pay(id: String, done: (GalaxyReply<GalaxyPayment>) -> Unit)
    fun acknowledge(purchaseId: String, done: (GalaxyReply<Boolean>) -> Unit)
}
internal interface GalaxyReceiptStore {
    fun read(): List<GalaxyUnlock>
    fun write(receipts: Collection<GalaxyUnlock>): Boolean
}
internal fun interface GalaxyTimer { fun cancel() }
internal interface GalaxyRuntime {
    fun dispatch(block: () -> Unit)
    /** A single serial worker orders receipt verification and durable cache writes. */
    fun work(block: () -> Unit)
    fun after(millis: Long, block: () -> Unit): GalaxyTimer
}

/** Store-independent transaction state machine, also exercised against a deterministic fake store. */
internal class GalaxyBillingSession(
    private val configured: Boolean,
    private val store: GalaxyStore,
    private val cache: GalaxyReceiptStore,
    private val verify: (GalaxyUnlock) -> GalaxyReceiptClaims,
    private val runtime: GalaxyRuntime,
    private val onState: (BillingSnapshot) -> Unit,
    private val onEvent: (BillingEvent) -> Unit,
) {
    private val products = StyleProducts.emptyProducts().toMutableMap()
    private val receipts = linkedMapOf<String, GalaxyUnlock>()
    private var connection = BillingConnection.DISCONNECTED
    private var busy = false
    private var closed = false
    private var loaded = false
    private var refreshWanted = false
    private var restoreAwaiting = false
    private var restoreFailed = false
    private var ticket = 0L
    private var timer: GalaxyTimer? = null
    private var awaitingPayment = false
    private var recoveredPayment: Long? = null
    private var requestedProduct: String? = null
    var state: BillingSnapshot = snapshot()
        private set

    fun start() {
        if (!configured || closed || busy || loaded) return
        busy = true
        publish()
        runtime.work {
            val restored = runCatching { cache.read() }.getOrDefault(emptyList())
            runtime.dispatch {
                if (!closed) {
                    restored.filter(::validReceipt).forEach { receipts[it.purchaseId] = it }
                    loaded = true
                    finish()
                }
            }
        }
    }

    fun resume() {
        if (closed || !configured) return
        if (awaitingPayment) {
            // No timeout while the store payment UI is open. Only a foreground return
            // starts a recovery query if the SDK never delivers a completion callback.
            timer?.cancel()
            val current = ticket
            timer = runtime.after(30_000) {
                if (accept(current)) { recoveredPayment = current; awaitingPayment = false; refreshWanted = true; finish() }
            }
        } else {
            refreshWanted = true
            if (!busy) refresh()
        }
    }

    fun pause() { if (awaitingPayment) { timer?.cancel(); timer = null } }

    fun restore() {
        if (closed) return
        if (!configured) { emit(BillingEvent.Error(BillingError.NOT_CONFIGURED)); return }
        restoreAwaiting = true
        refreshWanted = true
        if (!busy) refresh()
    }

    fun purchase(id: String) {
        if (closed) return
        if (!configured) { emit(BillingEvent.Error(BillingError.NOT_CONFIGURED)); return }
        if (state.ownsProduct(id)) { emit(BillingEvent.PurchaseCompleted(id)); return }
        if (!state.canPurchase(id)) { emit(BillingEvent.Error(BillingError.PRODUCT_UNAVAILABLE)); return }
        busy = true
        requestedProduct = id
        publish()
        request<List<BillingProduct>>({ store.products(setOf(id), it) }) { reply ->
            val product = reply.value.orEmpty().firstOrNull { it.productId == id && it.eligible }
            if (reply.error != null || product == null) {
                products[id] = products.getValue(id).copy(eligible = false)
                fail(BillingError.PRODUCT_UNAVAILABLE, reply.code)
            } else {
                products[id] = product
                publish()
                payment(id)
            }
        }
    }

    private fun refresh() {
        if (closed || busy || !configured) return
        refreshWanted = false
        restoreFailed = false
        connection = BillingConnection.CONNECTING
        busy = true
        publish()
        request<List<BillingProduct>>({ store.products(StyleProducts.allProductIds, it) }) { reply ->
            StyleProducts.allProductIds.forEach { id ->
                products[id] = reply.value.orEmpty().firstOrNull { it.productId == id }
                    ?: products.getValue(id).copy(eligible = false)
            }
            connection = if (reply.error == null) BillingConnection.READY else BillingConnection.UNAVAILABLE
            publish()
            queryOwned() // Price availability does not prevent restoring an existing purchase.
        }
    }

    private fun queryOwned() {
        request<List<GalaxyUnlock>>({ store.owned(it) }) { reply ->
            if (reply.error != null || reply.value == null) { fail(reply.error ?: BillingError.UNAVAILABLE, reply.code); return@request }
            val candidates = reply.value.filter(::validReceipt).distinctBy { it.purchaseId }
            val before = ownedIds()
            receipts.entries.removeAll { (_, cached) -> candidates.none { it == cached } }
            val revoked = before - ownedIds()
            if (revoked.isNotEmpty()) emit(BillingEvent.Revoked(revoked))
            publish()
            persist { verifyNext(ArrayDeque(candidates)) }
        }
    }

    private fun payment(id: String) {
        awaitingPayment = true
        val current = nextTicket(timeout = false)
        try {
            store.pay(id) { reply -> runtime.dispatch callback@{
                if (!accept(current)) {
                    // A delayed success after recovery is reconciled via an authoritative
                    // owned query, never by granting a stale payment callback.
                    if (!closed && recoveredPayment == current && reply.value is GalaxyPayment.Paid) {
                        recoveredPayment = null
                        refreshWanted = true
                        if (!busy) refresh()
                    }
                    return@callback
                }
                awaitingPayment = false
                when (val result = reply.value) {
                    GalaxyPayment.Canceled -> { requestedProduct = null; emit(BillingEvent.Canceled); finish() }
                    GalaxyPayment.AlreadyOwned -> { refreshWanted = true; finish() }
                    is GalaxyPayment.Paid -> {
                        if (result.receipt.itemId != id || !validReceipt(result.receipt)) fail(BillingError.VERIFICATION_FAILED)
                        else verifyNext(ArrayDeque(listOf(result.receipt)))
                    }
                    null -> fail(reply.error ?: BillingError.PURCHASE_FAILED, reply.code)
                }
            } }
        } catch (_: Exception) { if (accept(current)) fail(BillingError.PURCHASE_FAILED) }
    }

    private fun verifyNext(queue: ArrayDeque<GalaxyUnlock>) {
        if (closed || !busy) return
        val receipt = queue.pollFirst() ?: run { finish(); return }
        work({ verify(receipt) }) { result ->
            val claim = result.getOrNull()
            when {
                claim == null -> {
                    restoreFailed = true
                    val error = if (result.exceptionOrNull() is IllegalArgumentException)
                        BillingError.VERIFICATION_FAILED else BillingError.NETWORK
                    if (error == BillingError.VERIFICATION_FAILED) revoke(receipt)
                    emit(BillingEvent.Error(error))
                    persist { verifyNext(queue) }
                }
                claim.acknowledged == "Y" -> saveGrant(receipt, queue)
                else -> acknowledge(receipt, queue)
            }
        }
    }

    private fun acknowledge(receipt: GalaxyUnlock, queue: ArrayDeque<GalaxyUnlock>) {
        request<Boolean>({ store.acknowledge(receipt.purchaseId, it) }) { reply ->
            if (reply.error != null || reply.value != true) {
                restoreFailed = true
                emit(BillingEvent.Error(BillingError.ACKNOWLEDGEMENT_FAILED, reply.code))
                verifyNext(queue)
            } else work({ verify(receipt) }) { result ->
                // Acknowledgement status 4 may also mean consumed. Verify again.
                if (result.getOrNull()?.acknowledged == "Y") saveGrant(receipt, queue)
                else {
                    restoreFailed = true
                    if (result.exceptionOrNull() is IllegalArgumentException) revoke(receipt)
                    emit(BillingEvent.Error(BillingError.ACKNOWLEDGEMENT_FAILED))
                    persist { verifyNext(queue) }
                }
            }
        }
    }

    private fun saveGrant(receipt: GalaxyUnlock, queue: ArrayDeque<GalaxyUnlock>) {
        val candidate = receipts.toMutableMap().apply { put(receipt.purchaseId, receipt) }
        work({ cache.write(candidate.values) }) { result ->
            if (result.getOrDefault(false)) {
                receipts[receipt.purchaseId] = receipt
                publish()
                if (requestedProduct == receipt.itemId) {
                    requestedProduct = null
                    emit(BillingEvent.PurchaseCompleted(receipt.itemId))
                }
            } else { restoreFailed = true; emit(BillingEvent.Error(BillingError.CACHE_WRITE_FAILED)) }
            verifyNext(queue)
        }
    }

    private fun revoke(receipt: GalaxyUnlock) {
        val before = ownedIds()
        receipts.remove(receipt.purchaseId)
        val removed = before - ownedIds()
        if (removed.isNotEmpty()) emit(BillingEvent.Revoked(removed))
        publish()
    }

    private fun persist(next: () -> Unit) {
        val current = receipts.values.toList()
        work({ cache.write(current) }) { result ->
            if (!result.getOrDefault(false)) { restoreFailed = true; emit(BillingEvent.Error(BillingError.CACHE_WRITE_FAILED)) }
            next()
        }
    }

    private fun <T> request(send: ((GalaxyReply<T>) -> Unit) -> Unit, next: (GalaxyReply<T>) -> Unit) {
        val current = nextTicket(timeout = true)
        try { send { reply -> runtime.dispatch { if (accept(current)) next(reply) } } }
        catch (_: Exception) { if (accept(current)) fail(BillingError.UNAVAILABLE) }
    }

    private fun <T> work(action: () -> T, next: (Result<T>) -> Unit) {
        val current = nextTicket(timeout = false)
        runtime.work {
            val result = runCatching(action)
            runtime.dispatch { if (accept(current)) next(result) }
        }
    }

    private fun nextTicket(timeout: Boolean): Long {
        timer?.cancel(); timer = null
        val current = ++ticket
        if (timeout) timer = runtime.after(30_000) { if (accept(current)) fail(BillingError.NETWORK) }
        return current
    }

    /** Consume each callback exactly once, including synchronous store callbacks. */
    private fun accept(current: Long): Boolean {
        if (closed || !busy || current != ticket) return false
        ticket++
        timer?.cancel(); timer = null
        return true
    }

    private fun fail(error: BillingError, code: Int? = null) {
        restoreFailed = true
        requestedProduct = null
        awaitingPayment = false
        connection = BillingConnection.UNAVAILABLE
        emit(BillingEvent.Error(error, code))
        finish()
    }

    private fun finish() {
        if (closed) return
        timer?.cancel(); timer = null
        busy = false
        publish()
        if (refreshWanted) refresh()
        else if (restoreAwaiting) {
            restoreAwaiting = false
            if (!restoreFailed) emit(BillingEvent.RestoreCompleted(ownedIds(), emptySet()))
        }
    }

    private fun validReceipt(receipt: GalaxyUnlock) = receipt.itemId in StyleProducts.allProductIds &&
        receipt.purchaseId.isNotBlank() && receipt.paymentId.isNotBlank() &&
        receipt.purchaseId.length <= 512 && receipt.paymentId.length <= 512
    private fun ownedIds() = receipts.values.map { it.itemId }.toSet()
    private fun snapshot() = BillingSnapshot(connection, products.toMap(), ownedIds(), busy = busy, verificationConfigured = configured, entitlementsLoaded = !configured || loaded)
    private fun publish() { val updated = snapshot(); if (state != updated) { state = updated; if (!closed) onState(updated) } }
    private fun emit(event: BillingEvent) { if (!closed) onEvent(event) }

    fun close() {
        if (closed) return
        // Queued after any in-flight write, this prevents a stale grant from surviving closure.
        if (loaded) { val current = receipts.values.toList(); runtime.work { cache.write(current) } }
        closed = true
        ticket++
        timer?.cancel(); timer = null
        busy = false
        connection = BillingConnection.CLOSED
        state = snapshot()
    }
}
