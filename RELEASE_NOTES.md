# RoadConquest 1.0 Beta 6

- Fix place overlays after a fresh reset: the starting country, state/region, and town are now kept as overlay-visible baseline places while still awarding zero points and counting as zero discoveries.
- Refresh a selected overlay even when reverse geocoding only resolves zero-point starter places.
- When an overlay is enabled after a reset, seed place resolution from the best fresh cached location so the current baseline can appear without requiring a rewarded discovery first.
- Make overlay loading fall back to the default Nominatim boundary service when the remote provider-config file is temporarily unavailable.
- Keep unmatched raw GPS only as a faint, narrow provisional trace while OSRM is resolving it. Confirmed traveled roads remain thicker and nearly opaque and use road-snapped geometry, so pending data does not look like a finalized off-road route while the map still stays visually continuous.
- Fix a matcher dead-zone where an otherwise acceptable 45–79% confidence OSRM match with an internally ambiguous junction could be retried forever even after fixes on both sides constrained the route. Contextual interior points now use the same 45% acceptance threshold as the saved road geometry, while ambiguous trace endpoints still stay pending for more evidence.
- Remove beta-to-beta database, progression, and road-history migration/repair paths so the beta uses only the current data model.

Android version code is 32. Android 12 or newer is required.
