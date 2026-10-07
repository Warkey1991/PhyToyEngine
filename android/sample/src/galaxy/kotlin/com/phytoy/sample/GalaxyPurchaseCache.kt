package com.phytoy.sample

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/** Only verified, acknowledged purchases enter the cache; Android Keystore authenticates its contents. */
internal class GalaxyPurchaseCache(context: Context) {
    private val packageName = context.packageName
    private val preferences = context.applicationContext.getSharedPreferences("phytoy_galaxy_purchases_v1", Context.MODE_PRIVATE)
    private val keyAlias = "$packageName.galaxy.receipt.cache.v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setDigests(KeyProperties.DIGEST_SHA256).build())
            generateKey()
        }
    }

    private fun mac(body: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(key())
        doFinal((packageName + "\n" + body).toByteArray(Charsets.UTF_8))
    }

    fun read(): List<GalaxyUnlock> = runCatching {
        val body = preferences.getString("receipts", null) ?: return emptyList()
        if (body.length > 16_384) return emptyList()
        val signature = preferences.getString("mac", null) ?: return emptyList()
        if (!MessageDigest.isEqual(mac(body), Base64.getDecoder().decode(signature))) return emptyList()
        val array = JSONArray(body)
        (0 until minOf(array.length(), 16)).mapNotNull { index ->
            val item = array.getJSONObject(index)
            val receipt = GalaxyUnlock(item.getString("purchase"), item.getString("item"), item.getString("payment"))
            receipt.takeIf { it.purchaseId.isNotBlank() && it.paymentId.isNotBlank() &&
                it.itemId in StyleProducts.productIds.values }
        }
    }.getOrDefault(emptyList())

    /** Serial worker only. Grant access after this durable commit, never before. */
    fun write(receipts: Collection<GalaxyUnlock>): Boolean = runCatching {
        val array = JSONArray()
        receipts.forEach { receipt -> array.put(JSONObject().apply {
            put("purchase", receipt.purchaseId); put("item", receipt.itemId); put("payment", receipt.paymentId)
        }) }
        val body = array.toString()
        preferences.edit().putString("receipts", body)
            .putString("mac", Base64.getEncoder().encodeToString(mac(body))).commit()
    }.getOrDefault(false)
}
