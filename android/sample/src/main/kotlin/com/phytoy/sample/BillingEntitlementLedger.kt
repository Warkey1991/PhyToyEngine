package com.phytoy.sample

/** Pure entitlement policy: purchased, verified and acknowledged are all required. */
internal class BillingEntitlementLedger(private val knownProducts: Set<String>) {
    enum class Status { UNSPECIFIED, PENDING, PURCHASED }
    data class Fact(val token: String, val products: Set<String>, val status: Status, val acknowledged: Boolean)

    private val grants = mutableMapOf<String, Set<String>>()
    private var pending = emptySet<String>()
    val ownedProducts: Set<String> get() = grants.values.flatten().toSet()
    val pendingProducts: Set<String> get() = pending.toSet()
    val grantedTokens: Set<String> get() = grants.keys.toSet()

    /** Only an OK authoritative query may remove cached grants. Failed queries never call this. */
    fun reconcile(facts: List<Fact>) {
        val live = facts.filter { it.status == Status.PURCHASED }.map { it.token }.toSet()
        grants.keys.retainAll(live)
        pending = facts.filter { it.status == Status.PENDING }.flatMap { it.products }
            .filter { it in knownProducts }.toSet()
    }

    fun updatePending(fact: Fact) {
        val products = fact.products.intersect(knownProducts)
        pending = if (fact.status == Status.PENDING) pending + products else pending - products
    }

    fun grant(fact: Fact, signatureVerified: Boolean): Boolean {
        if (!signatureVerified || fact.status != Status.PURCHASED || !fact.acknowledged || fact.token.isBlank()) {
            return false
        }
        val products = fact.products.intersect(knownProducts)
        if (products.isEmpty()) return false
        grants[fact.token] = products
        pending = pending - products
        return true
    }

    fun revoke(token: String) { grants.remove(token) }
}
