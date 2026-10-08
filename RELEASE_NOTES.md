# Road Conquest 1.0 Beta 23

- Recover from stalled high-accuracy Android location delivery while the foreground tracking service remains active: check for two minutes without usable GPS callbacks and retry the location-provider registration with a five-minute cooldown.
- Ignore normal GPS updates, deliberately paused tracking and disabled Android Location when deciding whether to recover; stop the lightweight check when tracking stops. Do not change driving-speed filters, mileage, fog rendering, matcher confidence thresholds, or map geometry acceptance.
- Preserve the Beta 22 fairness improvement for retrying older unmatched turns. Record app version and version code in future driving-data exports so GPS gaps can be correlated with the exact installed build.
- Retain existing local history, signed-release identity, startup-crash repair, and complete native debug symbols and R8 mapping embedded in the Play bundle.

**Limit:** No update can reconstruct roads for a period with no stored GPS evidence. Android can also stop or restrict an entire foreground service, in which case this in-service recovery check cannot run; grant precise all-the-time location and unrestricted battery use for reliable background tracking.

Android version code is 49. Android 12 or newer is required. Install the signed APK over the previous version to retain local driving history. Do not uninstall or clear app data.

For Google Play, upload only the signed `RoadConquest-1.0-beta.23.aab` from GitHub Releases. Its full native symbols and R8 mapping are embedded. The app ID and permanent signing key are unchanged.
