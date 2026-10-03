# Google Play camera unlocks

The first two camera styles (DH 2++ color and monochrome) are free. The remaining four are independent **non-consumable, one-time purchases**. They are not subscriptions, rentals or a bundle. The requested US base price is **USD 9.99 per camera**. Configure prices and tax treatment in Play Console; the app uses Google's localized `formattedPrice` and never substitutes a hard-coded dollar price.

| Camera | Permanent product ID | Purchase option | US target price |
| --- | --- | --- | --- |
| DIGITAL 01 | `camera_digital_01` | `unlock`, permanent BUY | USD 9.99 |
| PLASTIC 82 | `camera_plastic_82` | `unlock`, permanent BUY | USD 9.99 |
| STREET 84 | `camera_street_84` | `unlock`, permanent BUY | USD 9.99 |
| FISHEYE 05 | `camera_fisheye_05` | `unlock`, permanent BUY | USD 9.99 |

Create these products under the app's final permanent package ID. Activate the products, their `unlock` purchase options and the desired countries. Disable multi-quantity purchases; each permanent camera unlock is quantity one. Do not configure rental/preorder options or additional buy offers without updating and testing the app's selection policy.

The customer taps a locked style to open its introduction, sees the exact local Play price, and can try its live viewfinder for free. Trial styles cannot save photos. Only an explicit tap on the purchase button opens Google's payment sheet. Cancellation leaves the camera locked. Pending payment does not grant access. Verified PURCHASED transactions are acknowledged, durably cached and unlocked; purchases are never consumed. Settings and each introduction contain Restore purchases. Restoring uses the current Google Play account. Reinstalling/clearing data requires a restore; confirmed local receipts support offline photography. An authoritative successful query removes absent/refunded unlocks; temporary service failures preserve cached access.

This candidate uses Billing Library 9.1.0. Configure the application's Play licensing **public** RSA key using Gradle property `phytoyPlayBillingPublicKey` or environment variable `PHYTOY_PLAY_BILLING_PUBLIC_KEY`. This is a public verification key, not a service-account credential or signing secret. An empty/invalid key fails closed: free cameras and previews work, paid photography and purchases are unavailable. Store production builds should add `-PphytoyRequireBillingConfigured=true` along with the existing signing/application-ID release gates. Never embed service-account keys in the app.

Device-side RSA verification rejects invalid signed receipts, wrong package/product and incomplete payment states. It does not prove current account ownership or prevent replay/tampering on a compromised device, and refunds cannot be detected while offline. The verifier interface supports a future trusted server. Google's recommended production design verifies purchase tokens on a secure backend, acknowledges there and handles RTDN/voided purchases. That backend is **not deployed or claimed complete** in this candidate; decide and implement it before treating high-value client unlocks as fraud-resistant. The receipt cache intentionally retains offline access, with the corresponding revocation delay.

No live payment has been made by the implementation agent. Client unit tests and emulator checks cannot certify Play payment success. Complete this release gate through the Play internal test track with license testers and test payment methods:

- Query all four products and verify local currencies/US target prices; unavailable products cannot be purchased.
- Purchase each camera and confirm only that camera unlocks; finish acknowledgment, kill/relaunch, and capture offline.
- Cancel the Play sheet; verify no unlock and no automatic photo capture.
- Use delayed payment approval/rejection; ensure PENDING remains locked, then grants only after confirmation.
- Disconnect before verification/acknowledgment and retry/foreground; no premature unlock or duplicate charge.
- Reinstall/clear local data and Restore using the same account; check different accounts and empty purchase lists.
- Refund/revoke in Console, foreground/restore, and verify access is removed after an authoritative query.
- Check expired/stale product details, duplicate callbacks, unavailable Play services, wrong package/public key and write failures.

Keep the privacy policy, Data safety worksheet, SDK notices and store listing synchronized. Camera settings reset does not clear purchases. Photos already saved remain viewable even when a style is locked.

Sources: [integration](https://developer.android.com/google/play/billing/integrate), [one-time purchase options](https://developer.android.com/google/play/billing/one-time-product-multi-purchase-options-offers), [security](https://developer.android.com/google/play/billing/security), [license testing](https://developer.android.com/google/play/billing/test).
