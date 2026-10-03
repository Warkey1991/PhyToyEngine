package com.phytoy.sample

import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class PlayPurchaseVerifierTest {
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val publicKey = Base64.getEncoder().encodeToString(keys.public.encoded)
    private val body = "{\"packageName\":\"com.phytoy.sample\",\"productId\":\"camera_digital_01\"}"
    private fun proof(json: String = body): SignedPlayPurchase {
        val signature = Signature.getInstance("SHA1withRSA").run {
            initSign(keys.private)
            update(json.toByteArray(Charsets.UTF_8))
            sign()
        }
        return SignedPlayPurchase(json, Base64.getEncoder().encodeToString(signature))
    }

    @Test fun validSignedProofCanBeVerifiedOnlineAndFromOfflineCache() {
        val verifier = RsaPlayPurchaseVerifier(publicKey)
        val proof = proof()
        assertTrue(verifier.configured)
        assertTrue(verifier.verify(proof))
        assertTrue(verifier.verifyCached(proof))
    }

    @Test fun changedPurchaseJsonIsRejected() {
        assertFalse(RsaPlayPurchaseVerifier(publicKey).verify(proof().copy(signedJson = body.replace("digital", "plastic"))))
    }

    @Test fun changedSignatureIsRejected() {
        val proof = proof()
        val bytes = Base64.getDecoder().decode(proof.signature)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        assertFalse(RsaPlayPurchaseVerifier(publicKey).verify(proof.copy(signature = Base64.getEncoder().encodeToString(bytes))))
    }

    @Test fun missingAndMalformedKeyFailClosed() {
        for (key in listOf("", "not-a-key", "YWJj")) {
            val verifier = RsaPlayPurchaseVerifier(key)
            assertFalse(verifier.configured)
            assertFalse(verifier.verify(proof()))
            assertFalse(verifier.verifyCached(proof()))
        }
    }

    @Test fun blankOrMalformedSignatureDoesNotThrowOrUnlock() {
        val verifier = RsaPlayPurchaseVerifier(publicKey)
        assertFalse(verifier.verify(SignedPlayPurchase(body, "")))
        assertFalse(verifier.verify(SignedPlayPurchase(body, "invalid!")))
        assertFalse(verifier.verify(proof().copy(signedJson = "")))
    }

    @Test fun receiptForAnotherSigningKeyIsRejected() {
        val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val verifier = RsaPlayPurchaseVerifier(Base64.getEncoder().encodeToString(other.public.encoded))
        assertFalse(verifier.verify(proof()))
        assertNotEquals(RsaPlayPurchaseVerifier(publicKey).cacheIdentity, verifier.cacheIdentity)
    }
}
