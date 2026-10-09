# Road Conquest 1.0 Beta 26

- Reduce unnecessary work on map refreshes: do not repeatedly upload the same empty provisional GPS-route GeoJSON to MapLibre's native map source. Preserve every real provisional-route update and clear the route when it becomes empty or expires.
- Skip conversion of an empty provisional-road list into temporary road geometry objects.
- Prepare the two equivalent pending-road SQL statement templates once instead of reassembling SQL text and column lists every viewport refresh. Their parameter bindings and query results remain unchanged.
- These are performance-only changes carried over from reviewed PR #58 onto the Beta 25 baseline. Actual battery-life improvement is device- and usage-dependent and has not been measured.
- Keep Beta 25's improved historical turn connections, Beta 24's supported junction stitches, Beta 23's GPS-callback recovery, Beta 22's matching retries, all stored road progress, GPS accuracy, fog and road drawing quality, achievements, verified scoring, and ads/purchases unchanged.
- Preserve the existing app ID, permanent upload signing certificate, signed APK/AAB release workflow, and complete embedded native MapLibre debug symbols plus R8 mapping.

Android version code is 52. Android 12 or newer is required. Install the signed APK over your existing installation to preserve local data; do not uninstall or clear app storage.

For Google Play, use only the signed `RoadConquest-1.0-beta.26.aab` from GitHub Releases. Do not upload the unsigned GitHub Actions intermediate.
