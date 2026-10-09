# Road Conquest 1.0 Beta 27

- Fill a very short seam when OSRM splits two confidently matched, differently named roads, but only when consecutive GPS samples and snapped geometry independently satisfy strict timing, distance, bearing and snap checks. The inferred connector remains non-counting: it does not award an extra road, mileage or verified-score credit.
- Fix an edge case where an invalid split connector was marked as consumed before validation, preventing a later legitimate candidate from being considered.
- For a short (1–3-fix) unresolved sharp corner with confirmed approach and exit context, retry OSRM just once without potentially conflicting bearing hints. Accept the retry only if additional missing fixes are recovered while preserving every previously accepted fix and both boundary anchors; retain the existing acceptance and detour checks.
- Keep the original narrow OSRM GPS-uncertainty values. The older experimental increase was deliberately excluded to avoid encouraging matches onto nearby incorrect roads.
- Preserve Beta 26's map-refresh CPU optimizations and Beta 24/25's separate display-only junction improvements. This release does not change GPS sampling, user data, stored road history, known-road counts, fog styling, ads/purchases, or leaderboard score rules.
- Keep the permanent upload-signing identity, the same app ID, all four complete MapLibre native-symbol libraries embedded in the signed Play AAB and the embedded R8 mapping. Existing turn regressions and new supported/rejected cross-road and turn-retry cases run in CI.

Android version code is 53. Android 12 or newer is required. Install the signed APK over the existing app without uninstalling or clearing storage.

For Google Play, upload only the signed `RoadConquest-1.0-beta.27.aab` from GitHub Releases, never the unsigned GitHub Actions intermediate. The release workflow publishes from `main` after all required Android checks pass.
