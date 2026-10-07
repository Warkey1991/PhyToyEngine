# Galaxy Store camera unlocks

Package: com.ycolor.team.phytoy.camera.android.galaxyapp. Samsung IAP 6.5.2 is isolated to the Galaxy edition. Purchases in the Play edition do not transfer.

Create four permanent **Item** products in Seller Portal matching [StyleProducts.kt](../android/sample/src/main/kotlin/com/phytoy/sample/StyleProducts.kt) exactly: `camera_digital_01`, `camera_plastic_82`, `camera_street_84`, `camera_fisheye_05`. DH 2++ color and mono are free. Requested pricing is USD 9.99 each; Seller Portal controls actual regional prices and activation. Samsung's old non-consumable creation type has been retired; use permanent Item plus acknowledgement.

Set phytoyGalaxyBillingConfigured=true only after configuring all four products for the final package. Missing configuration keeps purchase/paid capture disabled while free live trials work. The release uses production IAP; test receipts cannot unlock a production camera.

The client queries product/owned data, verifies purchase IDs with Samsung's fixed HTTPS receipt endpoint, checks package/product/payment/order/permanent type/status/mode, rejects consumed/test receipts, acknowledges and rechecks before granting. Android Keystore protects local offline entitlements. Successful owned queries revoke missing purchases; transient failures preserve verified cache. No independently operated verification server is deployed.

## Real store tests

On Samsung hardware with Galaxy Store and the final package/certificate, verify all four localized products/prices, success/acknowledgement, cancellation/pending/failure, network loss before/after payment, retries/duplicate callbacks, same-account reinstall/clear-data restoration, another-account behavior, offline cache, refund/revocation and trial capture gates. Check sign-in/unsupported-device errors, 200% font and TalkBack. Automated unit/emulator tests exclude real checkout; do not charge without an explicitly authorized test payment.

Galaxy requires commercial seller status for free and paid apps. Supply the privacy URL/contact and register the applicable Android Developer Verification package/signing identity. Samsung's September 2026 notice describes submission and regional enforcement requirements. Source code does not complete these account-side steps.

[Samsung IAP programming guide](https://developer.samsung.com/iap/programming-guide.html), [seller preparation](https://developer.samsung.com/galaxy-store/prepare.html), [FAQ](https://developer.samsung.com/galaxy-store/faq.html), [ADV notice](https://seller.samsungapps.com/notice/getNoticeDetail.as?csNoticeID=0000011990).
