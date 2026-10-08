# Road Conquest 1.0 Beta 19

- Fix Beta 18's immediate startup crash. R8 had removed the public constructor that Room uses to instantiate the WorkManager database introduced by the ad SDK. Preserve that constructor without disabling app shrinking or removing ads.
- Require cold-start runtime checks of the actual minified release APK on Android 12 and 17 before publication, in addition to the existing unit, lint, server, native map, packaging, and signing checks.
- Retain all Beta 18 ads, privacy controls, reward protection, and Beta 17 map/tracking optimizations.

Android version code is 45. Android 12 or newer is required. Install the signed APK over the existing app to keep local driving data; do not uninstall or clear app storage.

This hotfix is published from the review branch and merged into main after verification. Live ad fill and AdMob/Play account settings remain separate publisher checks. If verified scoring is enabled on your service, allow version code 45.
