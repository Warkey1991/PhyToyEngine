# Galaxy Store camera purchases

Package: `com.ycolor.team.phytoy.camera.android.galaxyapp`. Version 0.17.8 / 26, Samsung IAP 6.5.2. Purchases in the Play edition do not transfer.

The implementation supports five permanent **Item** products. DH 2++ color and mono remain free.

Seller Portal English titles and descriptions are maintained in [IAP_PRODUCT_REFERENCE.csv](IAP_PRODUCT_REFERENCE.csv), in the `english_name` and `english_description` columns. Use the exact `product_id` for the product number. These copy fields fit the screenshot's 130-character title and 1,000-character description limits.

| Product ID | US base price | Entitlement |
| --- | ---: | --- |
| `camera_digital_01` | $5.99 | DIGITAL 01 only |
| `camera_plastic_82` | $5.99 | PLASTIC 82 only |
| `camera_street_84` | $5.99 | STREET 84 only |
| `camera_fisheye_05` | $5.99 | FISHEYE 05 only |
| `camera_all_lifetime` | $19.99 | All current and future cameras in this app |

Seller Portal controls regional prices and activation. The application uses exact localized store prices and does not substitute a dollar amount. Samsung retired creation of the old non-consumable product type; register permanent Item purchases and acknowledge them, never consume them.

Set `phytoyGalaxyBillingConfigured=true` after configuring the five IDs for the final package. The local ignored Gradle configuration currently enables it. Production receipts only can grant access. User confirmed the five products and test account are configured on October 11, 2026; their actual IDs/prices still require a device query.

`GalaxyBillingSession` serializes store operations through the production SDK adapter. Every response is consumed once. Network requests have bounded timeouts; the payment sheet has no arbitrary 60-second timeout. If the app returns to the foreground without a payment result, a delayed owned query recovers the transaction. Delayed callbacks cannot grant stale receipts. Verification and durable cache writes run on a serial worker, away from the camera thread.

Receipts are verified via Samsung’s fixed HTTPS endpoint: package, item, payment, order, permanent Item type, status, production mode and non-consumed status. Acknowledgement is confirmed by rechecking the receipt before durable caching and granting access. Receipt service system failures or incomplete responses preserve existing verified cache; explicit cancellation, missing orders or invalid receipts do not grant access. Android Keystore authenticates the offline cache. Successful owned queries revoke absent purchases. No independently operated publisher verification backend is deployed.

Lifetime ownership is saved as `camera_all_lifetime`, not a list of today's styles. New cameras use the same centralized entitlement policy automatically. Four individual purchases do not become lifetime ownership. Refunding lifetime preserves valid singles; refunding a single leaves an active lifetime usable. Existing single prices are not credited against the lifetime price, and the upgrade screen discloses this.

## Verification and real-store acceptance

The JVM transaction fixture covers purchases, restore, offline access, refund combinations, future camera access, cancellation, delayed/duplicate callbacks, acknowledgement/cache failure, network recovery and shutdown. The Android UI fixture runs only on a dedicated emulator, uses isolated snapshots, checks actual native layouts and never opens checkout. These tests are not evidence of a real store order.

Complete [the device acceptance record](IAP_DEVICE_ACCEPTANCE.md) using the signed Galaxy candidate, matching package/certificate, current Galaxy Store and configured account. Do not mark real payment, reinstall restore or refund passed without an actual store order and observed result. The user will perform real-device acceptance separately; only client implementation and automated checks were requested for completion.

For production-mode **Closed Beta** testing, registered license testers are not charged. Production mode outside Closed Beta can charge even a license tester. Confirm both the distribution track and the final sheet before proceeding. TEST-mode receipts are deliberately rejected by this production candidate.

Supply the publisher/privacy/contact configuration and complete applicable Samsung account and signing registration requirements before publishing. These are separate from source implementation and static packaging checks.

Sources: [programming guide](https://developer.samsung.com/iap/programming-guide.html), [operation modes](https://developer.samsung.com/iap/test-guide/iap-operation-modes.html), [receipt API and errors](https://developer.samsung.com/iap/programming-guide/samsung-iap-server-api.html).
