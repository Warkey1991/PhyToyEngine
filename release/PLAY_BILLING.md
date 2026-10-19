# Google Play camera unlocks

## Play Console configuration (2026-10-18)

Select the Google Play app with application ID `com.ycolor.team.phytoy.camera.android.gpapp`, then open **Monetize with Play > Products > One-time products**. Create the products below; product IDs must exactly match the app code. These are permanent non-consumable unlocks, not subscriptions.

For every product, create a purchase option with the exact ID **`unlock`**, select **Buy**, disable multi-quantity, set the applicable content/service classification and tax settings accurately, configure supported regions and regional prices, and **activate** the purchase option. This app selects the `unlock` option without an offer ID, rental or pre-order; keep a standard Buy option available. Draft/inactive options are not returned by Play Billing.

Copy-ready English product metadata (each description is under 200 characters):

| Product ID | Name | Description | US target price |
| --- | --- | --- | --- |
| `camera_digital_01` | DIGITAL 01 Camera Unlock | Permanently unlock photo capture with DIGITAL 01 in Tovi Camera. Warm highlights, cool shadows and subtle lens character. One-time purchase. Includes this camera style only. | USD 5.99 |
| `camera_plastic_82` | PLASTIC 82 Camera Unlock | Permanently unlock photo capture with PLASTIC 82 in Tovi Camera. Square photos with soft edges and plastic-camera character. One-time purchase. Includes this camera style only. | USD 5.99 |
| `camera_street_84` | STREET 84 Camera Unlock | Permanently unlock photo capture with STREET 84 in Tovi Camera. Punchy street colors, a 2:3 frame and dark corners. One-time purchase. Includes this camera style only. | USD 5.99 |
| `camera_fisheye_05` | FISHEYE 05 Camera Unlock | Permanently unlock photo capture with FISHEYE 05 in Tovi Camera. Square photos with fisheye distortion and circular light falloff. One-time purchase. Includes this camera style only. | USD 5.99 |
| `camera_all_lifetime` | All Cameras Lifetime Pass | Permanently unlock all current and future camera styles in Tovi Camera. One-time purchase. No subscription. Previous individual camera purchases are not deducted from the pass price. | USD 19.99 |

DH 2++ color and monochrome are free; do not create paid products for them. Restore uses the same Google Play account. The shared `IAP_PRODUCT_REFERENCE.csv` currently contains Galaxy-specific Samsung-account descriptions; use the Play metadata above for this channel.

After saving, use an internal test release and license tester accounts to verify purchase, cancellation, pending payment and restore. Being an internal tester alone does not make purchases free; use license-testing payment methods. Console product setup does not enable billing in a build missing this project's receipt-verification key. During this local check, no key was present in the project/user Gradle properties or the billing-key environment variable; add `phytoyPlayBillingPublicKey` before rebuilding the billing-enabled candidate.

References: [one-time product configuration](https://support.google.com/googleplay/android-developer/answer/16430488?hl=en), [license testing](https://developer.android.com/google/play/billing/test).

## Client integration and release checks

The first two camera styles (DH 2++ color and monochrome) are free. Four individual styles are **non-consumable, one-time purchases** at a requested US base price of **USD 5.99 each**. A fifth product is the **USD 19.99 All Cameras Lifetime Pass**, including current and future cameras in this app. None are subscriptions or rentals. Configure prices and tax treatment in Play Console; the app uses Google's localized `formattedPrice` and never substitutes a hard-coded dollar price.

| Camera | Permanent product ID | Purchase option | US target price |
| --- | --- | --- | --- |
| DIGITAL 01 | `camera_digital_01` | `unlock`, permanent BUY | USD 5.99 |
| PLASTIC 82 | `camera_plastic_82` | `unlock`, permanent BUY | USD 5.99 |
| STREET 84 | `camera_street_84` | `unlock`, permanent BUY | USD 5.99 |
| FISHEYE 05 | `camera_fisheye_05` | `unlock`, permanent BUY | USD 5.99 |
| All Cameras Lifetime Pass | `camera_all_lifetime` | `unlock`, permanent BUY | USD 19.99 |

Create these products under the app's final permanent package ID. Activate the products, their `unlock` purchase options and the desired countries. Disable multi-quantity purchases; each permanent camera unlock is quantity one. Do not configure rental/preorder options or additional buy offers without updating and testing the app's selection policy.

The customer taps a locked style to try its live viewfinder immediately. Explicit Unlock or the locked shutter opens its introduction and exact localized Play price. Trial styles cannot save photos or become the next-launch camera. Only an explicit tap on the purchase button opens Google's payment sheet. The user explicitly selects a lifetime pack or single camera before opening checkout. Cancellation preserves existing purchases. Pending payment does not grant access. Verified PURCHASED transactions are acknowledged, durably cached and unlocked; purchases are never consumed. Settings and each introduction contain Restore purchases. Restoring uses the current Google Play account. Reinstalling/clearing data requires a restore; confirmed local receipts support offline photography. An authoritative successful query removes absent/refunded unlocks; temporary service failures preserve cached access.

This candidate uses Billing Library 9.1.0. Configure the application's Play licensing **public** RSA key using Gradle property `phytoyPlayBillingPublicKey` or environment variable `PHYTOY_PLAY_BILLING_PUBLIC_KEY`. This is a public verification key, not a service-account credential or signing secret. An empty/invalid key fails closed: free cameras and previews work, paid photography and purchases are unavailable. Store production builds should add `-PphytoyRequireBillingConfigured=true` along with the existing signing/application-ID release gates. Never embed service-account keys in the app.

Play App Signing does not replace this client's receipt-verification key. Google documents that the licensing/in-app billing key pair is separate from the application signing key: [key distinction](https://developer.android.com/google/play/licensing/client-side-verification#public-key). Publisher/contact inputs are now configured as YColorTeam, yjuan9865@gmail.com and https://sites.google.com/view/ycolorteam. The provided web page was reachable during browser inspection, but its generic policy describes advertising and Firebase integrations absent from this app; align its ToviCam policy with [privacy-policy.html](privacy-policy.html) before publication. The billing public key is still absent; without it, a signed candidate remains billing-disabled.

Device-side RSA verification rejects invalid signed receipts, wrong package/product and incomplete payment states. It does not prove current account ownership or prevent replay/tampering on a compromised device, and refunds cannot be detected while offline. The verifier interface supports a future trusted server. Google's recommended production design verifies purchase tokens on a secure backend, acknowledges there and handles RTDN/voided purchases. That backend is **not deployed or claimed complete** in this candidate; decide and implement it before treating high-value client unlocks as fraud-resistant. The receipt cache intentionally retains offline access, with the corresponding revocation delay.

No live payment has been made by the implementation agent. Client unit tests and emulator checks cannot certify Play payment success. Complete this release gate through the Play internal test track with license testers and test payment methods:

- Query all five products and verify local currencies/US target prices; unavailable products cannot be purchased.
- Purchase each camera and confirm only that camera unlocks; finish acknowledgment, kill/relaunch, and capture offline.
- Cancel the Play sheet; verify no unlock and no automatic photo capture.
- Use delayed payment approval/rejection; ensure PENDING remains locked, then grants only after confirmation.
- Disconnect before verification/acknowledgment and retry/foreground; no premature unlock or duplicate charge.
- Reinstall/clear local data and Restore using the same account; check different accounts and empty purchase lists.
- Refund/revoke in Console, foreground/restore, and verify access is removed after an authoritative query.
- Check expired/stale product details, duplicate callbacks, unavailable Play services, wrong package/public key and write failures.

Keep the privacy policy, Data safety worksheet, SDK notices and store listing synchronized. Camera settings reset does not clear purchases. Photos already saved remain viewable even when a style is locked.

Sources: [integration](https://developer.android.com/google/play/billing/integrate), [one-time purchase options](https://developer.android.com/google/play/billing/one-time-product-multi-purchase-options-offers), [security](https://developer.android.com/google/play/billing/security), [license testing](https://developer.android.com/google/play/billing/test).

The lifetime entitlement remains an independent product ID in the verified ledger/cache, so new CameraStyle entries inherit access automatically. Refunding lifetime preserves valid individual purchases; refunding an individual does not limit an active lifetime. Four individual purchases do not imply lifetime. Pending lifetime blocks overlapping single checkouts, and a pending single blocks a lifetime checkout. Previously bought singles do not reduce the pack price; the UI discloses this before upgrade. Missing one SKU does not disable the other available choices.
