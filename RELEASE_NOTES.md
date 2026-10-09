# Road Conquest 1.0 Beta 30

- Fix the visibly shifting gray fog and sudden changes in brightness while zooming and panning. Explored square geography stays tied to the MapLibre map coordinates rather than the camera's previous cloud-texture phase.
- Render **one consistent dark slate fog color** (ARGB `#CC263142`, 80% opacity) over all unexplored territory. The map underneath still shows through; fog itself no longer changes tint whenever the camera moves.
- Remove the two-native-frame transition that stacked the full-world and detailed fog layers. Only one 80%-opaque raster is active per completed native update; overlapping layers no longer create dark 96%-opaque bars during camera gestures.
- Prefer a sharper viewport-specific fog raster at regional zoom levels instead of magnifying 512 world-map pixels across the screen. Keep appropriate viewport padding and stronger edge resolution (up to 1,024 pixels) so map-position transitions and fog borders do not jump between image resolutions.
- Preserve anti-aliased, area-proportional coverage for explored mile cells that occupy less than one world-image pixel. Such cells no longer punch out a huge white 78-km world pixel; at extremely distant zoom individual mile cells may naturally become imperceptible.
- Keep permanent global mile-square unlocks, existing historical backfill, seamless visited-area union, the 1,500-foot transition and all saved data, road matches, distances, achievements, points, accounts, and verified scoring intact.
- Add tests checking identical hex colors across different simulated camera texture matrices, subpixel coverage, and stable explored geographic positions through native map zoom/pan sequences.

Android version code **56**; requires Android 12+. Install the signed APK over Beta 29 without uninstalling or clearing data. The original upload certificate and native debug symbols are preserved in the signed Play AAB.

Only the published GitHub Release AAB is intended for Google Play Console, not intermediate build artifacts.
