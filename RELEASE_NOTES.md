# Road Conquest 1.0 Beta 8

- Use the public display name **Road Conquest** and Android application ID `com.roadconquest.app` for the Play release.
- Show the exact `versionName` and `versionCode` in **Settings → About**.
- Build and publish a signed Android App Bundle (AAB) for Google Play while retaining the signed APK for direct installs.
- Add hosted privacy-policy and external account-deletion pages on the existing account service and link both from Settings → Data and privacy.
- Show a prominent location-use disclosure before the first Android location permission request, including background use, road matching, and optional verified scoring.
- Reuse the immutable high-accuracy location request and avoid a recurring accepted-fix list resize without changing GPS cadence, filtering, matching, or accuracy.
- Avoid reparsing account endpoint configuration several times during one Settings refresh.
- Organize achievements into separate **Road Conquest**, **Mileage**, **Exploration**, **Ad Rewards**, and **Bonus** categories with category buttons. Ad achievements have their own Ad Rewards category; battery achievements are under Bonus.
- Move **Delete data on this device** into **Settings → Data and privacy** and centralize the local reset path.
- Keep the explored-place overlay system: countries render blue, states/regions purple, and towns green; only one category can be enabled at once, overlays stay anchored to the map, sit below fog, and can be tapped for population and area when available.
- Keep verified-drive precise GPS sharing opt-in instead of enabling it automatically when an account is created.
- Harden device-data deletion so tracking/verified uploads stay stopped while deletion is pending, confirmed deletion work can finish if Settings closes, and interrupted cleanup is retried safely on the next launch.
- Preserve place candidates seen while the exact zero-point starting location is still resolving, without awarding discovery points before the baseline finishes.
- Keep the zero-point baseline tied to a fresh live/current fix and prevent stale or mock fixes from becoming progression evidence.
- Avoid inventing short road connections across multi-minute moving GPS outages; longer reconnection now requires stationary evidence such as a red-light/parking anchor.
- Keep pending road rendering efficient by querying unmatched evidence plus nearby context rather than scanning all historical points in the viewport.
- Improve International Date Line handling for road bounds, viewport queries, grouping, local matching, and sparse place-candidate coordinates.
- Prevent older cached location fixes from replacing a newer live car-marker position.
- Harden rapid fog zoom/rotation fallback behavior, especially on Android 12, while retaining the full-world fallback that prevents white map gaps.
- Prevent stale pre-reset background work, achievement state, progression writes, overlays, exports, purchases, and verified-drive work from recreating deleted device data.
- Clean up interrupted private export snapshots and keep export snapshot lifecycle serialized.
- Keep account/session clearing and privacy-related state changes durable across abrupt process death.
- Keep CI from treating an already-published version as a release failure; existing release tags are never overwritten or retargeted.
- Retain the existing Beta 7 account security, leaderboard verification, native map rendering, road matching, overlay, and no-migration behavior unless changed above.

Android version code is 34. Android 12 or newer is required.
