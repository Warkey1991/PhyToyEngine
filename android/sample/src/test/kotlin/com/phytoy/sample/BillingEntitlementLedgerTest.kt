package com.phytoy.sample

import org.junit.Assert.*
import org.junit.Test

class BillingEntitlementLedgerTest {
    private val digital = "camera_digital_01"
    private val plastic = "camera_plastic_82"
    private fun ledger() = BillingEntitlementLedger(setOf(digital, plastic))
    private fun purchased(token: String = "receipt-1", product: String = digital, acknowledged: Boolean = true) =
        BillingEntitlementLedger.Fact(token, setOf(product), BillingEntitlementLedger.Status.PURCHASED, acknowledged)

    @Test fun pendingPaymentNeverUnlocks() {
        val ledger = ledger()
        val pending = purchased().copy(status = BillingEntitlementLedger.Status.PENDING)
        ledger.updatePending(pending)
        assertFalse(ledger.grant(pending, signatureVerified = true))
        assertEquals(setOf(digital), ledger.pendingProducts)
        assertTrue(ledger.ownedProducts.isEmpty())
    }

    @Test fun acknowledgementFailureCannotUnlock() {
        val ledger = ledger()
        assertFalse(ledger.grant(purchased(acknowledged = false), signatureVerified = true))
        assertTrue(ledger.ownedProducts.isEmpty())
    }

    @Test fun invalidSignatureCannotUnlock() {
        val ledger = ledger()
        assertFalse(ledger.grant(purchased(), signatureVerified = false))
        assertTrue(ledger.ownedProducts.isEmpty())
    }

    @Test fun permanentPurchasesAreIndependent() {
        val ledger = ledger()
        assertTrue(ledger.grant(purchased(), true))
        assertTrue(ledger.grant(purchased("receipt-2", plastic), true))
        assertEquals(setOf(digital, plastic), ledger.ownedProducts)
    }

    @Test fun successfulOwnershipQueryRemovesRefundedPurchaseOnly() {
        val ledger = ledger()
        ledger.grant(purchased(), true)
        val retained = purchased("receipt-2", plastic)
        ledger.grant(retained, true)
        ledger.reconcile(listOf(retained))
        assertEquals(setOf(plastic), ledger.ownedProducts)
        assertEquals(setOf("receipt-2"), ledger.grantedTokens)
    }

    @Test fun replacementPendingPaymentDoesNotKeepAnOldGrant() {
        val ledger = ledger()
        ledger.grant(purchased(), true)
        ledger.reconcile(listOf(purchased("replacement").copy(status = BillingEntitlementLedger.Status.PENDING)))
        assertTrue(ledger.ownedProducts.isEmpty())
        assertEquals(setOf(digital), ledger.pendingProducts)
    }

    @Test fun completionClearsPendingAndRepeatedDeliveryIsIdempotent() {
        val ledger = ledger()
        ledger.updatePending(purchased().copy(status = BillingEntitlementLedger.Status.PENDING))
        ledger.grant(purchased(), true)
        ledger.grant(purchased(), true)
        assertTrue(ledger.pendingProducts.isEmpty())
        assertEquals(setOf(digital), ledger.ownedProducts)
        assertEquals(1, ledger.grantedTokens.size)
    }

    @Test fun unknownProductAndBlankTokensAreRejected() {
        val ledger = ledger()
        assertFalse(ledger.grant(purchased(product = "unrelated_subscription"), true))
        assertFalse(ledger.grant(purchased(token = ""), true))
        assertTrue(ledger.ownedProducts.isEmpty())
    }

    @Test fun explicitVerificationRevocationLeavesOtherStyleUsable() {
        val ledger = ledger()
        ledger.grant(purchased(), true)
        ledger.grant(purchased("receipt-2", plastic), true)
        ledger.revoke("receipt-1")
        assertEquals(setOf(plastic), ledger.ownedProducts)
    }
}
