# Road Conquest 1.0 Beta 20

- Rebuild the Play-upload AAB without post-build ZIP modifications. Beta 19's large, modified AAB passed JAR-signature checks, but Play Console rejected it; the exact cause remains unconfirmed.
- Retain MapLibre's complete native crash symbols as a separately downloadable archive instead of embedding them in the AAB. This substantially reduces the Play-upload AAB size without changing the runtime app or losing symbol files.
- Verify the Gradle-produced AAB and the final signed release AAB with checksum-pinned Google bundletool. Confirm the actual AAB JAR signature and its permanently registered Play upload certificate.
- Label intermediate CI bundles as UNSIGNED / DO NOT UPLOAD. Preserve Beta 19's runtime stability improvements, ads, privacy, map/tracking features, and existing upload key.

Android version code is 46. Android 12 or newer is required. Install the signed APK over the existing app to retain local driving data; do not uninstall or clear app storage.

Upload only `RoadConquest-1.0-beta.20.aab` from GitHub Releases to Play Console. Then manually upload `RoadConquest-1.0-beta.20-native-debug-symbols.zip` under the version's App bundle explorer → Downloads → Assets to retain full native crash symbolication. The app signing key and application ID remain unchanged. If verified scoring is enabled on your service, allow version code 46.
