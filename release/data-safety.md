# Data safety worksheet — current offline build

Audit date: 2026-10-01. Re-audit if dependencies, manifest permissions or functionality change. This is a preparation worksheet; complete and verify the actual Play Console form against the uploaded artifact.

| Data | Access and purpose | Retention / deletion | Transmission |
| --- | --- | --- | --- |
| Live camera frames | Foreground viewfinder and user-triggered still capture | Session memory released on close | None |
| Saved JPEG photographs | User-created pictures in Pictures/PhyToy | User deletes through the system gallery; survive uninstall | None initiated by this app |
| Capture metadata | Date/time, exposure, ISO, focal length where available, lens direction and style | Stored inside the JPEG; deleted with photo | None |
| Default/last style, quality, grid, sound, haptics, review and permission state | Local app preferences | Clear app storage or uninstall | None |
| Photo URI/name/size/style/date index | Local photo list and review of pictures created by this app | Clear app storage or uninstall; original JPEGs remain | None |
| Runtime diagnostics | Local Android/device logs for failures and performance | Managed by Android; never automatically uploaded | None |

The manifest has **no INTERNET, advertising ID, microphone or location permission**. CAMERA is requested at runtime for expected foreground capture. Android 10+ uses MediaStore for its own photographs without READ_MEDIA_IMAGES or a broad library permission prompt. WRITE_EXTERNAL_STORAGE is restricted to Android 9/API 28 and earlier; Android can imply legacy READ_EXTERNAL_STORAGE from this permission. The app limits its photo list to its own PhyToy album and does not browse unrelated pictures. The app contains the in-repository engine SDK, not an ad/analytics/crash-upload SDK. Backup is disabled for private app data.

Based on the current code and dependencies, propose “no data collected” and “no data shared” for the Play Data safety form. Google's definition excludes data accessed and processed only on the device. This does not remove the privacy-policy requirement. Do not claim encryption in transit for a transmission feature that does not exist, and do not claim server/account deletion for an app without an account. [Official Data safety definitions](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en).

The app contains offline privacy text. The publisher must supply a privacy contact and make the same policy available at a stable public URL accessible without login or geographic restrictions. Use the prepared policy source, complete its publisher/contact fields, and verify the URL before submitting. [Official User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311?hl=en).
