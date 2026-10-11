package com.team.phytoy.camera

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/** Test APK only: drives native purchase views using snapshots; never enters either store's checkout. */
class PurchaseUiInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        var activity: Activity? = null
        try {
            check(Build.MODEL.contains("sdk_gphone") || Build.FINGERPRINT.contains("generic")) {
                "UI fixture is restricted to a dedicated emulator"
            }
            val host = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            activity = host
            val all = StyleProducts.ALL_CAMERAS_PRODUCT_ID
            val digital = "camera_digital_01"
            var purchasedId: String? = null
            lateinit var overlay: CameraPurchaseOverlay
            val ready = BillingSnapshot(BillingConnection.READY,
                StyleProducts.emptyProducts().mapValues { (id, product) ->
                    product.copy(formattedPrice = if (id == all) "€19,99" else "€5,99", currencyCode = "EUR", eligible = true)
                }, verificationConfigured = true)
            runOnMainSync {
                val root = FrameLayout(host)
                host.setContentView(root)
                overlay = CameraPurchaseOverlay(host, {}, {}, { purchasedId = it }, {}, {})
                root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
                overlay.show(CameraStyle.DIGITAL_01, CameraPurchaseUi(ready))
            }
            fun awaitFrame() {
                val frame = java.util.concurrent.CountDownLatch(1)
                runOnMainSync {
                    val choreographer = android.view.Choreographer.getInstance()
                    choreographer.postFrameCallback { choreographer.postFrameCallback { frame.countDown() } }
                }
                check(frame.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "UI did not render" }
                waitForIdleSync()
            }
            awaitFrame()
            val checks = mutableListOf<String>()
            fun checkUi(name: String, block: () -> Unit) {
                runOnMainSync(block); awaitFrame(); checks += name
            }
            fun buy() = overlay.findViewById<Button>(R.id.purchase_buy)
            fun assertLabel(price: String) { check(buy().text.contains(price)); check(buy().isEnabled) }
            fun shot(name: String) {
                val bitmap = uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
                val file = java.io.File(targetContext.getExternalFilesDir(null), "iap-ui-$name.png")
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            }
            checkUi("default lifetime price") { assertLabel("€19,99") }
            checkUi("single selection and exact purchase id") {
                overlay.findViewById<View>(R.id.purchase_plan_single).performClick()
                assertLabel("€5,99"); buy().performClick(); check(purchasedId == digital)
            }
            checkUi("lifetime selection and exact purchase id") {
                overlay.findViewById<View>(R.id.purchase_plan_all).performClick()
                assertLabel("€19,99"); buy().performClick(); check(purchasedId == all)
            }
            checkUi("radio accessibility") {
                val allNode = overlay.findViewById<View>(R.id.purchase_plan_all).createAccessibilityNodeInfo()
                val singleNode = overlay.findViewById<View>(R.id.purchase_plan_single).createAccessibilityNodeInfo()
                check(allNode.isCheckable && allNode.isChecked && !singleNode.isChecked)
                check(allNode.contentDescription.contains("€19,99"))
            }
            // Scroll purchase plans into view for a reviewable screenshot.
            checkUi("purchase plans reachable in scroll") {
                val card = overlay.findViewById<View>(R.id.purchase_plan_all)
                var parent = card.parent
                while (parent !is ScrollView) parent = parent.parent
                val rectangle = android.graphics.Rect()
                card.getDrawingRect(rectangle)
                (parent as ScrollView).offsetDescendantRectToMyCoords(card, rectangle)
                parent.scrollTo(0, rectangle.top + parent.scrollY)
                check(card.getGlobalVisibleRect(android.graphics.Rect())) { "Purchase plan is not reachable" }
            }
            shot("plans-${targetContext.resources.configuration.fontScale}")
            checkUi("existing single cannot be repurchased, upgrade remains available") {
                overlay.update(CameraPurchaseUi(ready.copy(ownedProductIds = setOf(digital))))
                check(!overlay.findViewById<View>(R.id.purchase_plan_single).isEnabled)
                assertLabel("€19,99")
            }
            checkUi("lifetime ownership hides all purchase controls") {
                overlay.update(CameraPurchaseUi(ready.copy(ownedProductIds = setOf(all))))
                check(buy().visibility == View.GONE)
                check(overlay.findViewById<Button>(R.id.purchase_preview).isShown)
                check(overlay.findViewById<Button>(R.id.purchase_restore).isEnabled)
            }
            shot("owned-${targetContext.resources.configuration.fontScale}")
            checkUi("free camera has no single purchase") {
                overlay.show(CameraStyle.HARINEZUMI_2PP, CameraPurchaseUi(ready))
                check(overlay.findViewById<View>(R.id.purchase_plan_single).visibility == View.GONE)
                assertLabel("€19,99")
            }
            checkUi("pending lifetime blocks checkout without unlocking") {
                overlay.show(CameraStyle.DIGITAL_01, CameraPurchaseUi(ready.copy(pendingProductIds = setOf(all))))
                check(!buy().isEnabled && buy().visibility == View.VISIBLE)
            }
            checkUi("busy checkout freezes both choices") {
                overlay.update(CameraPurchaseUi(ready.copy(busy = true)))
                check(!buy().isEnabled)
                check(!overlay.findViewById<View>(R.id.purchase_plan_all).isEnabled)
                check(!overlay.findViewById<View>(R.id.purchase_plan_single).isEnabled)
            }
            checkUi("missing all sku preserves available single") {
                overlay.update(CameraPurchaseUi(ready.copy(products = ready.products - all)))
                check(!buy().isEnabled)
                overlay.findViewById<View>(R.id.purchase_plan_single).performClick(); assertLabel("€5,99")
            }
            checkUi("no invented fallback price") {
                overlay.update(CameraPurchaseUi())
                check(!buy().isEnabled)
                check(!buy().text.contains("19.99") && !buy().text.contains("5.99"))
                check(overlay.findViewById<View>(R.id.purchase_retry).visibility == View.VISIBLE)
            }
            checkUi("all camera detail pages and wrapping") {
                for (style in CameraStyle.entries) {
                    overlay.show(style, CameraPurchaseUi(ready))
                    overlay.measure(View.MeasureSpec.makeMeasureSpec(host.window.decorView.width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(host.window.decorView.height, View.MeasureSpec.EXACTLY))
                    overlay.layout(0, 0, overlay.measuredWidth, overlay.measuredHeight)
                    assertLabel("€19,99")
                    check(buy().height >= (48 * host.resources.displayMetrics.density).toInt())
                    fun inspect(view: View) {
                        if (view.visibility == View.GONE) return
                        if (view is TextView && view.text.isNotEmpty() && view.layout != null) {
                            val available = view.width - view.paddingLeft - view.paddingRight
                            for (line in 0 until view.layout.lineCount) {
                                check(view.layout.getEllipsisCount(line) == 0) { "Truncated purchase copy" }
                                check(view.layout.getLineWidth(line) <= available + 2) { "Clipped purchase copy: ${view.text}" }
                            }
                        }
                        if (view is ViewGroup) for (i in 0 until view.childCount) inspect(view.getChildAt(i))
                    }
                    // Hero and style artwork have their own design validation. Inspect the new decisions.
                    inspect(overlay.findViewById(R.id.purchase_plan_all))
                    inspect(overlay.findViewById(R.id.purchase_plan_single))
                    inspect(buy())
                }
            }
            result.putString("stream", JSONObject().put("passed", true).put("fontScale", targetContext.resources.configuration.fontScale)
                .put("checks", org.json.JSONArray(checks)).put("realPaymentTested", false).toString())
            finish(Activity.RESULT_OK, result)
        } catch (failure: Throwable) {
            result.putString("stream", "Purchase UI validation failed: ${failure.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, result)
        } finally { activity?.let { host -> runOnMainSync { host.finish() } } }
    }
}
