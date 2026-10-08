# Road Conquest 1.0 Beta 22

- Give older unresolved corner and road-matching gaps a chance to retry during spare matching batches, including GPS points that have no scheduled backoff. The freshest drive still gets the first matching batch.
- Use the existing indexed SQLite matching queue without an unnecessary sort. Add regression tests for competing older and newer gaps and the empty-queue case.
- Retain Beta 21's expanded approach/exit GPS context for turns, while preserving the ten-fix OSRM request limit, confidence and snap-distance safeguards, detour rejection, and separate-trip boundaries.
- Preserve all existing local road data, mileage, achievements, verified-scoring rules, and GPS sampling rates. Do not infer connections across ambiguous intersections.
- Retain minified-app startup checks, the established release signing identity, and fully embedded native MapLibre debug symbols plus R8 mapping in the Play bundle.

Android version code is 48. Android 12 or newer is required. Install the signed APK over the existing version to retain local history. Do not uninstall or clear app storage.

Upload only the signed `RoadConquest-1.0-beta.22.aab` from GitHub Releases to Google Play. The full native symbols and R8 mapping are embedded. The app ID and signing key are unchanged.
