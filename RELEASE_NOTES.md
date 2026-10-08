# Road Conquest 1.0 Beta 20

- Keep **complete unstripped native MapLibre debug symbols for all four ABIs embedded directly in the Play-upload AAB**, plus the R8/ProGuard mapping. No separate Play Console upload is needed.
- Losslessly compress native symbols with ZIP level 9, verify matching native-library build IDs, and compare embedded symbol bytes against their source files.
- Eliminate the duplicate standalone native debug-symbol ZIP from GitHub Releases; the full debug data remains inside the AAB.
- Verify the AAB structure using checksum-pinned official Google bundletool after embedding symbols and again after signing. Enforce an actual JAR signature and the same registered upload key.
- Clearly label unsigned CI intermediate bundles, retaining Beta 19's ads, runtime stability, map/tracking features, and privacy improvements unchanged.

Android version code is 46. Android 12 or newer is required. Install the signed APK over the existing version to retain local history. Do not uninstall or clear app storage.

Upload only the signed `RoadConquest-1.0-beta.20.aab` from GitHub Releases to Google Play. The full native symbols and R8 mapping are embedded. The existing app ID and signing key are unchanged. The previous Play invalid-signature rejection's root cause remains unconfirmed; the new validation checks must pass before publication.
