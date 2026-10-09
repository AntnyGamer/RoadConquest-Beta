# Road Conquest 1.0 Beta 25

- Fix a confirmed-road visual gap near the Exit 14B / Benigno Boulevard turn: Beta 24's display-only junction check looked only at the final two centerline vertices, so an otherwise trustworthy approach ending in a 0.64-meter micro-segment was rejected.
- Use a nearby point farther back on the **same already-confirmed road geometry** when the immediate endpoint segment is shorter than the existing 4-meter direction threshold. The extra search is bounded to 25 meters; use the original adjacent vertex whenever it is already sufficiently long.
- Keep the existing 15-second, 30-meter, centerline intersection, direction and mutually unique-continuation safeguards. Do not connect unknown roads, guessed turns, separate drives or roads with insufficient direction evidence.
- Retain all history, road unlocks, mileage, points, map appearance and leaderboard rules. These display-only junction links do not modify the road database or award any progress, and the CR 607 / Beckett Road connection eligible in Beta 24 remains supported.
- Preserve existing GPS tracking behavior, OSRM confidence and road matching, permanent upload-signing certificate, and complete MapLibre native debug symbols plus R8 mapping embedded in the Google Play AAB.

Android version code is 51. Android 12 or newer is required. Install the signed APK over your existing installation to retain all local history; do not uninstall or clear app data.

For Google Play, upload only the signed `RoadConquest-1.0-beta.25.aab` from GitHub Releases. The app ID and permanent upload key remain unchanged.

Some parking aisles are not present in the underlying road data. This release intentionally does not manufacture unlocked roads or historical driving paths without sufficient confirmed geometry.
