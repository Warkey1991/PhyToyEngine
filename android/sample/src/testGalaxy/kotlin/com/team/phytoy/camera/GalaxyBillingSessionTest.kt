package com.team.phytoy.camera

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

/** Simulated SDK lifecycle tests; these are not evidence of a real Galaxy Store payment. */
class GalaxyBillingSessionTest {
    private val all = StyleProducts.ALL_CAMERAS_PRODUCT_ID
    private val single = "camera_digital_01"
    private fun receipt(id: String = all, token: String = id) = GalaxyUnlock(token, id, "payment-$token")

    private class Clock : GalaxyRuntime {
        private data class Task(val at: Long, val block: () -> Unit, var canceled: Boolean = false)
        private val tasks = mutableListOf<Task>()
        val jobs = java.util.ArrayDeque<() -> Unit>()
        var holdWork = false
        var now = 0L
        override fun dispatch(block: () -> Unit) = block()
        override fun work(block: () -> Unit) { if (holdWork) jobs.add(block) else block() }
        override fun after(millis: Long, block: () -> Unit): GalaxyTimer {
            val task = Task(now + millis, block); tasks += task
            return GalaxyTimer { task.canceled = true }
        }
        fun advance(millis: Long) {
            val target = now + millis
            while (true) {
                val task = tasks.filter { !it.canceled && it.at <= target }.minByOrNull { it.at } ?: break
                tasks.remove(task); now = task.at; task.block()
            }
            now = target
        }
        fun drain() { while (jobs.isNotEmpty()) jobs.removeFirst()() }
    }
    private class Cache : GalaxyReceiptStore {
        var saved = emptyList<GalaxyUnlock>()
        var writable = true
        override fun read() = saved
        override fun write(receipts: Collection<GalaxyUnlock>): Boolean {
            if (writable) saved = receipts.toList()
            return writable
        }
    }
    private class Store : GalaxyStore {
        var catalog = StyleProducts.emptyProducts().values.map {
            it.copy(formattedPrice = if (it.productId == StyleProducts.ALL_CAMERAS_PRODUCT_ID) "€19,99" else "€5,99", currencyCode = "EUR", eligible = true)
        }
        var owned = emptyList<GalaxyUnlock>()
        var ownedError: BillingError? = null
        var productError: BillingError? = null
        var holdProducts = false
        var productsCallback: ((GalaxyReply<List<BillingProduct>>) -> Unit)? = null
        var payment: ((GalaxyReply<GalaxyPayment>) -> Unit)? = null
        var paidIds = mutableListOf<String>()
        var ackSuccess = true
        var ackSetsClaim = true
        val acknowledged = mutableSetOf<String>()
        var queried = emptySet<String>()
        var ownedQueries = 0
        override fun products(ids: Set<String>, done: (GalaxyReply<List<BillingProduct>>) -> Unit) {
            queried = ids
            if (holdProducts) productsCallback = done
            else done(GalaxyReply(catalog.filter { it.productId in ids }, productError))
        }
        override fun owned(done: (GalaxyReply<List<GalaxyUnlock>>) -> Unit) {
            ownedQueries++
            done(GalaxyReply(if (ownedError == null) owned else null, ownedError))
        }
        override fun pay(id: String, done: (GalaxyReply<GalaxyPayment>) -> Unit) { paidIds += id; payment = done }
        override fun acknowledge(purchaseId: String, done: (GalaxyReply<Boolean>) -> Unit) {
            if (ackSuccess && ackSetsClaim) acknowledged += purchaseId
            done(GalaxyReply(ackSuccess))
        }
    }
    private inner class Fixture(initial: List<GalaxyUnlock> = emptyList(), configured: Boolean = true) {
        val clock = Clock()
        val store = Store()
        val cache = Cache().apply { saved = initial }
        val events = mutableListOf<BillingEvent>()
        var verifyError: Exception? = null
        val rejected = mutableSetOf<String>()
        val session = GalaxyBillingSession(configured, store, cache, { receipt ->
            verifyError?.let { throw it }
            require(receipt.purchaseId !in rejected)
            GalaxyReceiptClaims("app", receipt.itemId, receipt.paymentId, "order", "Item", "success", "PRODUCTION", "N",
                if (receipt.purchaseId in store.acknowledged) "Y" else "N")
        }, clock, {}, { events += it })
        init { session.start() }
        fun ready() { session.resume(); assertFalse(session.state.busy) }
        fun buy(id: String = all) { ready(); session.purchase(id); assertEquals(id, store.paidIds.last()) }
        fun success(receipt: GalaxyUnlock = receipt()) { store.owned = store.owned + receipt; store.payment!!(GalaxyReply(GalaxyPayment.Paid(receipt))) }
    }

    @Test fun refreshQueriesAllFiveLocalizedProducts() {
        val f = Fixture(); f.ready()
        assertEquals(StyleProducts.allProductIds, f.store.queried)
        assertEquals("€19,99", f.session.state.products.getValue(all).formattedPrice)
    }
    @Test fun allCameraPaymentAcknowledgesCachesAndUnlocksEverything() {
        val f = Fixture(); f.buy(); f.success()
        assertTrue(f.session.state.allCamerasUnlocked)
        assertEquals(CameraStyle.entries.toSet(), f.session.state.unlockedStyles)
        assertEquals(listOf(receipt()), f.cache.saved)
        assertEquals(listOf(BillingEvent.PurchaseCompleted(all)), f.events.filterIsInstance<BillingEvent.PurchaseCompleted>())
    }
    @Test fun allFourSinglesUseTheirOwnSkuAndPrice() {
        StyleProducts.productIds.forEach { (style, id) ->
            val f = Fixture(); f.buy(id); f.success(receipt(id))
            assertTrue(f.session.state.isUnlocked(style)); assertFalse(f.session.state.allCamerasUnlocked)
            assertEquals("€5,99", f.session.state.products.getValue(id).formattedPrice)
        }
    }
    @Test fun cancellationGrantsNothingAndKeepsExistingSingle() {
        val saved = receipt(single)
        val f = Fixture(listOf(saved)); f.store.owned = listOf(saved); f.buy()
        f.store.payment!!(GalaxyReply(GalaxyPayment.Canceled))
        assertEquals(setOf(single), f.session.state.ownedProductIds)
        assertFalse(f.session.state.busy); assertTrue(BillingEvent.Canceled in f.events)
    }
    @Test fun duplicateSuccessCallbackIsIdempotentWithoutAnImmediateOwnershipRace() {
        val f = Fixture(); f.buy(); val callback = f.store.payment!!
        f.success(); val queries = f.store.ownedQueries
        callback(GalaxyReply(GalaxyPayment.Paid(receipt())))
        assertEquals(queries, f.store.ownedQueries)
        assertEquals(1, f.events.filterIsInstance<BillingEvent.PurchaseCompleted>().size)
    }
    @Test fun paymentUiCanStayOpenLongerThanSixtySeconds() {
        val f = Fixture(); f.buy(); f.session.pause(); f.clock.advance(180_000); f.success()
        assertTrue(f.session.state.allCamerasUnlocked)
        assertTrue(f.events.none { it is BillingEvent.Error })
    }
    @Test fun foregroundMissingCallbackRecoversOwnership() {
        val f = Fixture(); f.buy(); f.store.owned = listOf(receipt())
        f.session.resume(); f.clock.advance(30_000)
        assertTrue(f.session.state.allCamerasUnlocked); assertFalse(f.session.state.busy)
    }
    @Test fun latePaidCallbackAfterRecoveryTriggersAnotherOwnedQuery() {
        val f = Fixture(); f.buy(); f.session.resume(); f.clock.advance(30_000)
        assertFalse(f.session.state.allCamerasUnlocked)
        f.success()
        assertTrue(f.session.state.allCamerasUnlocked)
    }
    @Test fun pausedStorePaymentDoesNotTriggerForegroundRecoveryTimer() {
        val f = Fixture(); f.buy(); f.session.resume(); f.session.pause(); f.clock.advance(60_000)
        assertTrue(f.session.state.busy); assertEquals(1, f.store.ownedQueries)
    }
    @Test fun restoreAfterReinstallFindsLifetimeWithoutLocalCache() {
        val f = Fixture(); f.store.owned = listOf(receipt()); f.session.restore()
        assertTrue(f.session.state.allCamerasUnlocked)
        assertEquals(setOf(all), f.events.filterIsInstance<BillingEvent.RestoreCompleted>().single().ownedProductIds)
    }
    @Test fun existingVerifiedLifetimeIsUsableOffline() {
        val f = Fixture(listOf(receipt())); f.store.ownedError = BillingError.NETWORK; f.session.resume()
        assertTrue(f.session.state.allCamerasUnlocked)
        assertEquals(listOf(receipt()), f.cache.saved)
    }
    @Test fun authoritativeLifetimeRefundRetainsPurchasedSingle() {
        val full = receipt(); val digital = receipt(single)
        val f = Fixture(listOf(full, digital)); f.store.owned = listOf(digital); f.session.restore()
        assertEquals(setOf(single), f.session.state.ownedProductIds)
        assertTrue(f.session.state.isUnlocked(CameraStyle.DIGITAL_01)); assertFalse(f.session.state.isUnlocked(CameraStyle.STREET_84))
        assertEquals(listOf(digital), f.cache.saved)
        assertEquals(setOf(all), f.events.filterIsInstance<BillingEvent.Revoked>().single().productIds)
    }
    @Test fun refundedSingleKeepsActiveLifetimeUsable() {
        val f = Fixture(listOf(receipt(), receipt(single))); f.store.owned = listOf(receipt()); f.ready()
        assertEquals(setOf(all), f.session.state.ownedProductIds); assertTrue(f.session.state.isUnlocked(CameraStyle.DIGITAL_01))
    }
    @Test fun negativeReceiptRevokesEvenWhenOwnedListStillContainsIt() {
        val f = Fixture(listOf(receipt())); f.store.owned = listOf(receipt()); f.rejected += receipt().purchaseId; f.session.restore()
        assertFalse(f.session.state.allCamerasUnlocked); assertTrue(f.cache.saved.isEmpty())
        assertTrue(f.events.none { it is BillingEvent.RestoreCompleted })
    }
    @Test fun invalidPaymentReceiptNeverGrants() {
        val f = Fixture(); f.buy(); f.rejected += receipt().purchaseId; f.success()
        assertFalse(f.session.state.allCamerasUnlocked); assertTrue(f.cache.saved.isEmpty())
    }
    @Test fun paymentReceiptForAnotherSkuNeverGrants() {
        val f = Fixture(); f.buy(); f.success(receipt(single))
        assertTrue(f.session.state.ownedProductIds.isEmpty())
    }
    @Test fun verificationNetworkFailureKeepsOldCacheButDoesNotGrantANewPurchase() {
        val f = Fixture(); f.buy(); f.verifyError = IOException("offline"); f.success()
        assertFalse(f.session.state.allCamerasUnlocked); assertTrue(f.cache.saved.isEmpty())
        f.verifyError = null; f.session.restore(); assertTrue(f.session.state.allCamerasUnlocked)
    }
    @Test fun acknowledgementFailureIsRecoveredByLaterRestore() {
        val f = Fixture(); f.buy(); f.store.ackSuccess = false; f.success()
        assertFalse(f.session.state.allCamerasUnlocked)
        f.store.ackSuccess = true; f.session.restore(); assertTrue(f.session.state.allCamerasUnlocked)
    }
    @Test fun acknowledgementSuccessWithoutConfirmedReceiptDoesNotGrant() {
        val f = Fixture(); f.buy(); f.store.ackSetsClaim = false; f.success()
        assertFalse(f.session.state.allCamerasUnlocked)
    }
    @Test fun durableCacheFailureCannotShowPurchaseSuccess() {
        val f = Fixture(); f.buy(); f.cache.writable = false; f.success()
        assertFalse(f.session.state.allCamerasUnlocked)
        assertTrue(f.events.none { it is BillingEvent.PurchaseCompleted })
        f.cache.writable = true; f.session.restore(); assertTrue(f.session.state.allCamerasUnlocked)
    }
    @Test fun alreadyOwnedPaymentReconcilesInsteadOfChargingAgain() {
        val f = Fixture(); f.buy(); f.store.owned = listOf(receipt()); f.store.payment!!(GalaxyReply(GalaxyPayment.AlreadyOwned))
        assertTrue(f.session.state.allCamerasUnlocked); assertEquals(1, f.store.paidIds.size)
    }
    @Test fun unavailableLifetimeDoesNotPreventASinglePurchase() {
        val f = Fixture(); f.store.catalog = f.store.catalog.filter { it.productId != all }; f.ready()
        assertFalse(f.session.state.canPurchase(all)); assertTrue(f.session.state.canPurchase(single))
    }
    @Test fun staleProductReplyAfterNetworkTimeoutCannotOpenPayment() {
        val f = Fixture(); f.ready(); f.store.holdProducts = true; f.session.purchase(all)
        val callback = f.store.productsCallback!!; f.clock.advance(30_000); callback(GalaxyReply(f.store.catalog))
        assertTrue(f.store.paidIds.isEmpty()); assertFalse(f.session.state.busy)
    }
    @Test fun closedSessionRejectsLatePaymentCallbacks() {
        val f = Fixture(); f.buy(); f.session.close(); f.success()
        assertFalse(f.session.state.allCamerasUnlocked); assertTrue(f.cache.saved.isEmpty())
    }
    @Test fun closingDuringCacheReadDoesNotEraseOfflineOwnership() {
        val f = Fixture(listOf(receipt())); f.clock.holdWork = true
        val session = GalaxyBillingSession(true, f.store, f.cache, { error("unused") }, f.clock, {}, {})
        session.start(); session.close(); f.clock.drain()
        assertEquals(listOf(receipt()), f.cache.saved)
    }
    @Test fun unconfiguredBuildRejectsAllPurchasesAndDoesNotQueryStore() {
        val f = Fixture(configured = false); f.session.purchase(all); f.session.restore(); f.session.resume()
        assertTrue(f.store.paidIds.isEmpty()); assertEquals(0, f.store.ownedQueries)
        assertTrue(f.events.all { it == BillingEvent.Error(BillingError.NOT_CONFIGURED) })
    }
    @Test fun receiptServiceSystemErrorPreservesExistingLifetime() {
        val f = Fixture(listOf(receipt())); f.store.owned = listOf(receipt())
        f.verifyError = IOException("system error"); f.session.restore()
        assertTrue(f.session.state.allCamerasUnlocked)
        assertEquals(listOf(receipt()), f.cache.saved)
        assertTrue(f.events.none { it is BillingEvent.RestoreCompleted || it is BillingEvent.Revoked })
    }
    @Test fun closeDuringInFlightGrantOverwritesStaleCacheWrite() {
        val f = Fixture(); f.buy(); f.store.acknowledged += receipt().purchaseId
        f.clock.holdWork = true; f.success(); f.clock.jobs.removeFirst()()
        // Verification has queued the durable grant, but the UI has not received it.
        f.session.close(); f.clock.drain()
        assertTrue(f.cache.saved.isEmpty()); assertFalse(f.session.state.allCamerasUnlocked)
    }
    @Test fun priceFailureStillRestoresAnOwnedLifetimeProduct() {
        val f = Fixture(); f.store.productError = BillingError.PRODUCT_UNAVAILABLE
        f.store.owned = listOf(receipt()); f.session.restore()
        assertTrue(f.session.state.allCamerasUnlocked)
        assertEquals(1, f.events.filterIsInstance<BillingEvent.RestoreCompleted>().size)
    }
    @Test fun overlappingPurchaseRequestCannotLaunchASecondPayment() {
        val f = Fixture(); f.buy(); f.session.purchase(single)
        assertEquals(listOf(all), f.store.paidIds)
    }

    @Test fun offlineReceiptLoadingDoesNotMisreportResolvedAccess() {
        val f = Fixture(listOf(receipt())); f.clock.holdWork = true
        val session = GalaxyBillingSession(true, f.store, f.cache, { error("unused") }, f.clock, {}, {})
        session.start(); assertFalse(session.state.entitlementsLoaded)
        f.clock.drain(); assertTrue(session.state.entitlementsLoaded)
        assertTrue(session.state.allCamerasUnlocked)
    }

    @Test fun changedReceiptIdentityCannotPreserveAnOldLifetimeGrant() {
        val full = receipt(); val f = Fixture(listOf(full))
        f.store.owned = listOf(full.copy(itemId = single, paymentId = "different-payment"))
        f.verifyError = IOException("offline"); f.session.restore()
        assertFalse(f.session.state.allCamerasUnlocked); assertTrue(f.cache.saved.isEmpty())
    }

}
