# Road Conquest 1.0 Beta 18

- Add anchored adaptive AdMob banners in separate footers on Garage, Achievements, Leaderboards, and Settings. Keep the driving map and account forms clear; do not show automatic full-screen ads.
- Add voluntary rewarded ads in the Garage. Load on request, show the configured point amount before playback, and grant points only on the SDK-confirmed reward callback.
- Persist the point reward and completed-view counter atomically, reject duplicate callbacks, and reject late rewards from a history session deleted by the user. Completed ads also unlock the existing ad achievements.
- Gate ad initialization and requests with Google UMP, expose applicable privacy choices, use Google demo IDs in debug builds, and clean up ad views when their screen closes.
- Include the updated advertising privacy-policy source and retain all Beta 17 battery/rendering and road-matching changes.
- Publish signed APK/AAB artifacts, mapping, native symbols, exact project source, checksums, and the tested account-service bundle from the same branch. Main remains unmerged.

Android version code is 44. Android 12 or newer is required.

Publisher setup remains necessary in AdMob Privacy & messaging and app readiness review, and in Play Console's ads/Data safety declarations. Deploy the attached account-service bundle to update the public privacy page and allow version code 44 for verified scoring. Ad inventory and live consent messages depend on the publisher account; build tests do not verify live ad fill. Ads introduce SDK and network overhead.
