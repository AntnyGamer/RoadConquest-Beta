# Road Conquest 1.0 Beta 26

- Reduce redundant CPU/native-map work: avoid uploading the same empty provisional GPS-route GeoJSON to MapLibre on every viewport refresh. Keep immediate provisional-road updates and clear a previously visible trace once as it expires. Reset the flag on each new map style.
- Skip building provisional road-feature geometry when there are no pending driving fixes; cache the two equivalent parameterized SQLite pending-route query templates instead of rebuilding the same SQL and column list for each refresh.
- Carry forward Beta 25's confirmed-junction tangent refinement, Beta 24's short accepted-road visual turn connections, Beta 23's stalled GPS callback recovery and Beta 22's fair retries of unresolved corners.
- No modifications to GPS sampling, OSRM acceptance or confidence, previously saved road data, fog quality, achievements, mileage, server scoring or advertisement behavior. Any battery reduction is expected to be modest and depends on real-world map refresh frequency.
- Preserve Android 12+ compatibility, application ID com.roadconquest.app, permanent upload signing identity, and full embedded native debug symbols plus R8 mapping.

Android version code is 52. Install the signed APK over your existing installation to retain local history. Do not uninstall or clear app data.

For Google Play, upload only the signed `RoadConquest-1.0-beta.26.aab` from GitHub Releases. The application ID and signing key remain unchanged.
