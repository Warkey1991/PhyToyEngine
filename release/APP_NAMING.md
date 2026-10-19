# App naming decision

## Samsung full edition

On 2026-10-19 the user delegated naming for the new no-IAP Samsung edition. Its display brand is **Pomi Camera** (11 characters). Proposed English Galaxy Store title: **Pomi - Retro Digital Camera** (27 characters). The short, playful brand suits the yellow toy-camera icon. These are creative naming choices; no measured keyword-volume or trademark-availability claim is made.

All 11 locale overlays use Pomi Camera for the launcher, About, camera permission and accessibility text. New-photo software metadata, the support-email subject and packaged artifact names follow this brand. The icon remains the user-selected original yellow camera with its former wordmark removed. The application ID remains `com.ycolor.team.phytoy.camera.android.galaxyfull` so the renamed build updates the preceding full-edition APK.

## Original store editions

Current launcher name: **Tovi Camera**, as configured in the app resources. Google Play title recommendation updated on 2026-10-18 following the user's ASO request: **Tovi: Retro Digital Camera**.

| Surface | Name | Characters |
| --- | --- | --- |
| Brand and launcher label | Tovi Camera | 11 |
| Google Play title, English | Tovi: Retro Digital Camera | 26 |
| Google Play title, Simplified Chinese | Tovi Camera - 复古数码相机 | 20 |
| Galaxy Store title, English | ToviCam: Retro Digital Camera | 29 |
| Galaxy Store title, Simplified Chinese | ToviCam - 复古数码相机 | 16 |

The English Play title uses Tovi as the brand identifier and Retro Digital Camera as a clear category phrase. This avoids repeating Camera in a 30-character title while covering the app's retro digital-camera positioning. The short description communicates the differentiator: lens and sensor simulation rather than preset color filters. The title does not promise film chemistry, hardware-calibrated CCD replication or photo-editing features.

The Galaxy Store entries above remain the earlier listing draft; this ASO update applies to Google Play. The proposed brand story draws on Toy, Vintage and Camera. This is a naming concept rather than a dictionary definition.

[Google Play's metadata policy](https://support.google.com/googleplay/android-developer/answer/9898842?hl=en) limits titles to 30 characters and requires accurate, descriptive metadata. Naming is based on product fit, not measured search volume or a guaranteed ranking.

Public indexed searches on 2026-10-02 did not surface a clear matching ToviCam app. This is a limited public search, not confirmation of trademark availability or an exhaustive app-store search.

This title update changes listing drafts only. Launcher strings currently use Tovi Camera; existing artwork and photo software metadata should be checked against that name before submission. The PhyToy engine name, technical identifiers and Pictures/PhyToy album remain for compatibility with existing photos and app data.

The localized titles and descriptions are maintained in [google-play-listing.md](google-play-listing.md).

The user confirmed these permanent Android application IDs on 2026-10-06:

| Store | Application ID |
| --- | --- |
| Google Play | `com.ycolor.team.phytoy.camera.android.gpapp` |
| Galaxy Store | `com.ycolor.team.phytoy.camera.android.galaxyapp` |

Separate package IDs keep store updates and purchases in their own channel; restoration uses the original account in the original store, with no automatic purchase transfer. Store-title ASO does not require changing these application IDs. The overseas Galaxy Store copy is maintained in [galaxy-store-listing.md](galaxy-store-listing.md).
