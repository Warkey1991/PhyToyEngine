package com.phytoy.sample

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

/**
 * Samsung's documented public receipt endpoint. Never trust PurchaseVo.verifyUrl or a caller's URL.
 * TLS receipt checks protect ordinary client transactions; they are not a trusted publisher backend.
 * https://developer.samsung.com/iap/programming-guide/samsung-iap-server-api.html
 */
internal class GalaxyReceiptVerifier(private val packageName: String) {
    fun verify(unlock: GalaxyUnlock): GalaxyReceiptClaims {
        require(unlock.purchaseId.isNotBlank() && unlock.purchaseId.length <= 512)
        val url = URL("https://iap.samsungapps.com/iap/v6/receipt?purchaseID=" +
            URLEncoder.encode(unlock.purchaseId, "UTF-8"))
        val connection = url.openConnection() as HttpsURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        try {
            check(connection.responseCode == 200) { "Receipt service unavailable" }
            val payload = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= 32_768) { "Receipt response exceeds limit" }
                    output.write(buffer, 0, count)
                }
                JSONObject(output.toString("UTF-8"))
            }
            val claims = GalaxyReceiptClaims(
                payload.optString("packageName"), payload.optString("itemId"),
                payload.optString("paymentId"), payload.optString("orderId"),
                payload.optString("itemType"), payload.optString("status"), payload.optString("mode"),
                payload.optString("consumeYN"), payload.optString("acknowledgeYN"),
            )
            require(claims.accepts(packageName, unlock.itemId, unlock.paymentId)) { "Receipt rejected" }
            return claims
        } finally {
            connection.disconnect()
        }
    }
}
