package com.phytoy.sample

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

internal data class SignedPlayPurchase(val signedJson: String, val signature: String)

/**
 * Fresh verification runs on the controller's worker, so a server-backed implementation
 * may block here. Cached verification must be local and must never grant an unknown proof.
 * A backend must also validate package/product/token/account and handle voided purchases.
 */
internal interface PlayPurchaseVerifier {
    val configured: Boolean
    val cacheIdentity: String
    fun verify(purchase: SignedPlayPurchase): Boolean
    fun verifyCached(purchase: SignedPlayPurchase): Boolean
}

/**
 * Google Play's signed purchase JSON uses SHA1withRSA. This checks the signature,
 * not account ownership, token replay or current refund status. Device-side checks
 * and the local cache can be bypassed on a compromised device; this is not server verification.
 */
internal class RsaPlayPurchaseVerifier(base64PublicKey: String) : PlayPurchaseVerifier {
    private val publicKey = runCatching {
        KeyFactory.getInstance("RSA").generatePublic(
            X509EncodedKeySpec(Base64.getDecoder().decode(base64PublicKey.filterNot(Char::isWhitespace))),
        )
    }.getOrNull()
    override val configured: Boolean get() = publicKey != null
    override val cacheIdentity: String = publicKey?.encoded?.let { encoded ->
        MessageDigest.getInstance("SHA-256").digest(encoded).joinToString("") { "%02x".format(it) }
    }.orEmpty()

    override fun verify(purchase: SignedPlayPurchase): Boolean {
        val key = publicKey ?: return false
        if (purchase.signedJson.isBlank() || purchase.signature.isBlank()) return false
        return runCatching {
            Signature.getInstance("SHA1withRSA").run {
                initVerify(key)
                update(purchase.signedJson.toByteArray(Charsets.UTF_8))
                verify(Base64.getDecoder().decode(purchase.signature))
            }
        }.getOrDefault(false)
    }

    override fun verifyCached(purchase: SignedPlayPurchase): Boolean = verify(purchase)
}
