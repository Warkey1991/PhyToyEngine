package com.phytoy.sample

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class CachedPlayPurchase(val proof: SignedPlayPurchase, val acknowledgedByApp: Boolean)

/** Private, backup-excluded purchase proofs. A cache is not an authoritative refund/account database. */
internal class PlayPurchaseCache(context: Context, private val verifierIdentity: String) {
    private val preferences = context.applicationContext
        .getSharedPreferences("phytoy_play_purchases_v1", Context.MODE_PRIVATE)

    fun read(): List<CachedPlayPurchase> {
        if (verifierIdentity.isBlank() || preferences.getString("verifier", null) != verifierIdentity) return emptyList()
        val array = runCatching { JSONArray(preferences.getString("receipts", "[]")) }.getOrNull()
            ?: return emptyList()
        return (0 until minOf(array.length(), 16)).mapNotNull { index ->
            runCatching {
                val item = array.getJSONObject(index)
                CachedPlayPurchase(
                    SignedPlayPurchase(item.getString("json"), item.getString("signature")),
                    item.getBoolean("acknowledged"),
                )
            }.getOrNull()
        }
    }

    /** Called on the controller's serial worker to preserve grant/revocation write order. */
    fun write(receipts: List<CachedPlayPurchase>): Boolean = runCatching {
        val array = JSONArray()
        receipts.forEach { receipt ->
            array.put(JSONObject().apply {
                put("json", receipt.proof.signedJson)
                put("signature", receipt.proof.signature)
                put("acknowledged", receipt.acknowledgedByApp)
            })
        }
        preferences.edit().putString("verifier", verifierIdentity)
            .putString("receipts", array.toString()).commit()
    }.getOrDefault(false)
}
