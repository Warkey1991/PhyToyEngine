package com.team.phytoy.camera

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.samsung.android.sdk.iap.lib.constants.HelperDefine
import com.samsung.android.sdk.iap.lib.helper.IapHelper
import com.samsung.android.sdk.iap.lib.vo.ErrorVo
import java.util.concurrent.Executors

/** Production Samsung SDK adapter. Permanent Items are acknowledged and never consumed. */
internal class GalaxyBillingController(
    context: Context,
    var onStateChanged: (BillingSnapshot) -> Unit = {},
    var onEvent: (BillingEvent) -> Unit = {},
) : CameraBillingController {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val helper by lazy { IapHelper.getInstance(app).apply {
        setOperationMode(HelperDefine.OperationMode.OPERATION_MODE_PRODUCTION)
        setShowErrorDialog(false)
    } }
    private val cache = GalaxyPurchaseCache(app)
    private val verifier = GalaxyReceiptVerifier(app.packageName)
    private val session = GalaxyBillingSession(BuildConfig.GALAXY_BILLING_CONFIGURED,
        object : GalaxyStore {
            override fun products(ids: Set<String>, done: (GalaxyReply<List<BillingProduct>>) -> Unit) {
                helper.getProductsDetails(ids.joinToString(",")) { error, details ->
                    Log.i("ToviBilling", "Galaxy catalog code=${error.errorCode} requested=${ids.sorted()}")
                    if (error.errorCode != IapHelper.IAP_ERROR_NONE) done(failure(error))
                    else done(GalaxyReply(value = ids.map { id ->
                        val product = details.orEmpty().firstOrNull { it.itemId == id }
                        val eligible = product != null && product.type.equals("item", true) &&
                            !product.itemPriceString.isNullOrBlank() && !product.currencyCode.isNullOrBlank()
                        Log.i("ToviBilling", "Galaxy product=$id eligible=$eligible price=${product?.itemPriceString.orEmpty()} currency=${product?.currencyCode.orEmpty()}")
                        BillingProduct(StyleProducts.styleForProduct(id), id,
                            formattedPrice = product?.itemPriceString?.takeIf { eligible },
                            currencyCode = product?.currencyCode?.takeIf { eligible }, eligible = eligible)
                    }))
                }
            }
            override fun owned(done: (GalaxyReply<List<GalaxyUnlock>>) -> Unit) {
                val sent = helper.getOwnedList(HelperDefine.PRODUCT_TYPE_ITEM) { error, owned ->
                    if (error.errorCode != IapHelper.IAP_ERROR_NONE) done(failure(error))
                    else done(GalaxyReply(value = owned.orEmpty().filter { it.type.equals("item", true) }
                        .map { GalaxyUnlock(it.purchaseId.orEmpty(), it.itemId.orEmpty(), it.paymentId.orEmpty()) }))
                }
                if (!sent) done(GalaxyReply(error = BillingError.UNAVAILABLE))
            }
            override fun pay(id: String, done: (GalaxyReply<GalaxyPayment>) -> Unit) {
                val sent = helper.startPayment(id) { error, purchase ->
                    when (error.errorCode) {
                        IapHelper.IAP_PAYMENT_IS_CANCELED -> done(GalaxyReply(GalaxyPayment.Canceled))
                        IapHelper.IAP_ERROR_ALREADY_PURCHASED -> done(GalaxyReply(GalaxyPayment.AlreadyOwned))
                        IapHelper.IAP_ERROR_NONE -> {
                            if (purchase == null || !purchase.type.equals("item", true))
                                done(GalaxyReply(error = BillingError.VERIFICATION_FAILED))
                            else done(GalaxyReply(GalaxyPayment.Paid(GalaxyUnlock(
                                purchase.purchaseId.orEmpty(), purchase.itemId.orEmpty(), purchase.paymentId.orEmpty()))))
                        }
                        else -> done(failure(error))
                    }
                }
                if (!sent) done(GalaxyReply(error = BillingError.PURCHASE_FAILED))
            }
            override fun acknowledge(purchaseId: String, done: (GalaxyReply<Boolean>) -> Unit) {
                val sent = helper.acknowledgePurchases(purchaseId) { error, acknowledgements ->
                    if (error.errorCode != IapHelper.IAP_ERROR_NONE) done(failure(error))
                    else done(GalaxyReply(value = acknowledgements.orEmpty().any {
                        it.purchaseId == purchaseId && it.statusCode in setOf(0, 4)
                    }))
                }
                if (!sent) done(GalaxyReply(error = BillingError.ACKNOWLEDGEMENT_FAILED))
            }
        }, object : GalaxyReceiptStore {
            override fun read() = cache.read()
            override fun write(receipts: Collection<GalaxyUnlock>) = cache.write(receipts)
        }, verifier::verify, object : GalaxyRuntime {
            override fun dispatch(block: () -> Unit) {
                if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
            }
            override fun work(block: () -> Unit) { worker.execute(block) }
            override fun after(millis: Long, block: () -> Unit): GalaxyTimer {
                val task = Runnable(block)
                handler.postDelayed(task, millis)
                return GalaxyTimer { handler.removeCallbacks(task) }
            }
        }, {
            Log.i("ToviBilling", "Galaxy state=${it.connection} busy=${it.busy} loaded=${it.entitlementsLoaded} owned=${it.ownedProductIds.sorted()}")
            onStateChanged(it)
        }, {
            // Public SKU/status only; never log purchase tokens, receipts or account details.
            val message = if (it is BillingEvent.Error) "Error reason=${it.reason} code=${it.responseCode}" else it.toString()
            Log.i("ToviBilling", "Galaxy event=$message")
            onEvent(it)
        })

    init { checkMainThread(); session.start() }
    override fun currentState() = session.state
    override fun isUnlocked(style: CameraStyle) = session.state.isUnlocked(style)
    override fun canPurchase(productId: String) = session.state.canPurchase(productId)
    override fun onResume() { checkMainThread(); session.resume() }
    override fun onPause() { checkMainThread(); session.pause() }
    override fun restorePurchases() { checkMainThread(); session.restore() }
    override fun purchase(activity: Activity, productId: String) {
        checkMainThread()
        if (activity.isFinishing || activity.isDestroyed) return
        session.purchase(productId)
    }
    override fun close() {
        checkMainThread()
        session.close()
        handler.removeCallbacksAndMessages(null)
        worker.shutdown()
        onStateChanged = {}; onEvent = {}
    }
    private fun checkMainThread() { check(Looper.myLooper() == Looper.getMainLooper()) }
    private fun <T> failure(error: ErrorVo): GalaxyReply<T> = GalaxyReply(error = when (error.errorCode) {
        IapHelper.IAP_ERROR_NETWORK_NOT_AVAILABLE, IapHelper.IAP_ERROR_IOEXCEPTION_ERROR,
        IapHelper.IAP_ERROR_SOCKET_TIMEOUT, IapHelper.IAP_ERROR_CONNECT_TIMEOUT -> BillingError.NETWORK
        IapHelper.IAP_ERROR_PRODUCT_DOES_NOT_EXIST, IapHelper.IAP_ERROR_NOT_EXIST_LOCAL_PRICE -> BillingError.PRODUCT_UNAVAILABLE
        else -> BillingError.UNAVAILABLE
    }, code = error.errorCode)
}
