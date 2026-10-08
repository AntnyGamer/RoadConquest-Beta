# Road Conquest 1.0 Beta 20

- Fix Android App Bundle packaging that can make Beta 19 fail Google Play upload validation. Native debug-symbol packaging now omits forbidden ZIP directory entries.
- Reject invalid bundle ZIP directory entries before and after signing; verify that the upload AAB is signed by the permanent Play upload key.
- Label intermediate CI bundles as UNSIGNED / DO NOT UPLOAD so the Play-ready AAB is unambiguous.
- Retain Beta 19's release-startup fix, Android emulator checks, ads, privacy controls, and mapping/tracking functionality without changing app features.

Android version code is 46. Android 12 or newer is required. Install the signed APK over the existing app to preserve local driving data; do not uninstall or clear app storage.

Upload only the signed `RoadConquest-1.0-beta.20.aab` from GitHub Releases to Play Console. The upload signing key and application ID remain unchanged. If verified scoring is enabled on your account service, allow version code 46.
