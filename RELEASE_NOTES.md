# Road Conquest 1.0 Beta 29

- Replace Beta 28's temporary 50-meter-point projection with **permanent mile-scale square exploration cells** stored in their own database table. Entering any part of a new cell with a valid accurate GPS fix unlocks its entire area, even if the movement since the last fix is under 20 meters.
- Fix blocky gray squares and gradient seams at unexplored boundaries. Merge all neighboring unlocked cells into a single shape and compute one smooth 1,500-foot fade from the outer edge, without overlapping individual edge/corner shaders.
- Use globally aligned north/south/east/west cell boundaries, preventing the variable-column misalignment and T-junction seams in Beta 28. Squares are approximately one mile wide and high around 40° latitude; physical east-west width varies at other latitudes because the Earth is round.
- Preserve historical exploration during the database version-10-to-11 upgrade. Backfill unlocked squares from all saved 50-meter exploration samples and accepted raw driving points, transactionally and without modifying old history. Legacy exploration CSV remains in exports; newly persisted squares also export as `explored_grid.csv`.
- Show saved exploration at wide zoom levels rather than replacing it with completely opaque overview fog. At extreme world scale, individual one-mile squares may be smaller than a map pixel.
- Keep all previously recorded roads, driving distance, turn-repair behavior, points, leaderboard credit, app identity and signing identity unchanged. The new fog cells do not grant road or driving credit.
- Include specific regression checks for grid alignment, accurate-fix boundaries, historical backfill, fade continuity, export contents, and native map rendering. Automated release publication still requires the full Android test, lint, native-symbol and startup checks.

Android version code is 55, minimum Android 12. Install the signed Beta 29 APK over the existing app without uninstalling or clearing storage.

For Google Play, upload only the signed `RoadConquest-1.0-beta.29.aab` from GitHub Releases, not any unsigned CI artifact.
