# Road Conquest 1.0 Beta 28

- Reveal the map one approximately 1-mile × 1-mile exploration cell at a time, including the whole cell as soon as an accurate saved/live location enters it. Fog fades gradually over 1,500 feet outside the cell boundaries, rather than following the driven blue road lines. Grid widths adjust by latitude and handle the international date line.
- Preserve old explored-place history: previously saved 50-meter exploration samples identify the new mile cells without a database reset or migration. Blue roads still render independently of fog. Near old grid boundaries, coarse historical cell centers can map a visit to an adjacent mile cell.
- Keep formerly visible intersection turns connected even if an unrelated later drive passes a nearby exit. Use bounded individual recorded road visits to repair additional uniquely supported missing visual joins; retain existing direction, uniqueness, timing and distance guards. These are display-only joins and do not add roads, distance, achievements or leaderboard credit.
- Restore retry handling for AdMob initialization and failed banner loads without re-registering lifecycle callbacks or loading ads while the screen is hidden.
- Treat malformed OSRM leg steps independently where possible, preserving valid neighboring matches; retain the original GPS sampling, snapped-distance, confidence and detour limits.
- Include safe maintenance work: buffered CSV export, reduced redundant map/fog allocations and duplicate fresh-database indexing, preserved existing overlay cache identifiers, and additional regression tests.
- Keep the permanent signing identity, app package, R8 mapping and complete MapLibre native debug symbols inside the signed Play App Bundle. The CI pipeline checks Android unit tests, lint/build, three native map-emulator APIs and release-startup tests before publishing from main.

Android version code is 54. Android 12 or newer is required. Install the signed APK over the existing app without uninstalling or clearing storage.

For Google Play, upload only the signed `RoadConquest-1.0-beta.28.aab` from GitHub Releases, never the unsigned GitHub Actions intermediate. The release workflow publishes from `main` only after the checks and emulator startup tests pass.
