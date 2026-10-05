# RoadConquest 1.0 Beta 6

- Fix place overlays after a fresh reset: the starting country, state/region, and town are now kept as overlay-visible baseline places while still awarding zero points and counting as zero discoveries.
- Recover Beta 5 counter-only starter baselines the next time that location resolves, without changing points or achievement progress.
- Refresh a selected overlay even when reverse geocoding only resolves zero-point starter places.
- When an overlay is enabled after a reset, seed place resolution from the best fresh cached location so the current baseline can appear without requiring a rewarded discovery first.
- Make overlay loading fall back to the default Nominatim boundary service when the remote provider-config file is temporarily unavailable, and bypass stale Beta 5 negative overlay cache entries.
- Stop drawing unmatched raw GPS evidence as a normal traveled-road line. Visible traveled lines now come only from OSRM road-snapped geometry, so temporary GPS traces cannot appear beside or cut across roads while matching is pending.
- Preserve the Beta 5 matcher repair, Settings organization, progression system, cosmetics, account, leaderboard, and fog behavior.

Android version code is 32. Android 12 or newer is required.
