# Road Conquest 1.0 Beta 31

- Fix visited one-mile squares temporarily appearing covered again during fast pinch zooms. A geographically anchored regional fog mask retains those clearings between detailed redraws, with one active fog raster at a time to prevent double-darkening.
- Capture town discovery candidates from actual accepted driving GPS locations at finer intervals and smaller geocoding cells, rather than querying an approximate 2-kilometer cell center that might lie outside a briefly crossed town.
- Re-check accurate saved GPS driving history in bounded batches so previously missed towns can be recovered where the original data contains evidence; preserve each profile's zero-point starting location and never award town points solely for a raw GPS coordinate or twice for an existing place.
- Show already-cached town/state/country boundaries without clearing them during refresh, and add newly downloaded boundaries individually instead of delaying until four are available.
- Add **First visited** and **Last visited** timestamps to the popup for each visited town, state or country. The latest confirmed reverse-geocoded visit updates the last timestamp without awarding points again. Existing place records show their first saved visit as both values until a newer revisit is verified.
- Add unit tests for geographic cutout stability, old history and reset-baseline safety, exact municipal-boundary candidate positions, timestamp ordering, and first/last visits. Test the live rendered fog during animated zoom on Android 12, 15 and 17.
- Keep recorded roads, mileage, exploration data, verified scoring, achievement balance, purchases and account state unchanged.

Version code **57**, minimum Android 12. Install the signed Beta 31 APK over the existing app without uninstalling or clearing data.

For Play Store upload, use only the signed `RoadConquest-1.0-beta.31.aab` attached to the GitHub Release. Its signing identity and embedded complete native debug symbols remain unchanged.
