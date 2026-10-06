# Road Conquest 1.0 Beta 10

- Add dedicated thin outline layers for country, state/region, and town overlays so neighboring explored areas remain visually separated while preserving the existing country → state → town → fog stacking order.
- Keep overlay outlines anchored to geographic boundaries and rebuild line geometry from both Polygon and MultiPolygon boundary rings.
- Render confirmed blue roads and road/place fog reveals beginning at zoom 8 instead of zoom 9, making explored areas visible from roughly twice as far away without changing the real-world reveal fade distance at normal driving zooms.
- Preserve a small minimum on-screen fog-reveal radius at the farthest supported overview zoom so revealed routes do not disappear into sub-pixel rendering.
- When Android Location is off, start the map at the exact saved zero-point location rather than the world overview. While Location is available, fresh live GPS still wins and controls initial centering.
- Recover the zero-point camera location both while baseline reverse geocoding is still pending and after the baseline has been resolved.
- Use the farther-out road visibility threshold consistently for road tapping and native map rendering.
- Match verified-leaderboard OSRM candidate search to the strengthened local turn envelope while retaining the stricter server-side 0.95 confidence, no-alternative, distance, speed, and catalog checks.
- Update the prominent in-app and hosted privacy disclosures to explicitly state that Road Conquest collects precise location data for driven-road and visited-place features in the background when automatic tracking is enabled and the app is closed or not in use.
- Simplify destructive controls to exactly two in-app actions: Delete account removes the cloud account while keeping local history; Delete all data removes all local Road Conquest data while keeping the cloud account and leaderboard scores. Both require the current account password; the public deletion webpage remains available for Google Play's external account-deletion requirement but is no longer a third in-app button.
- Add a CI gate that verifies 16 KB native-library ZIP alignment and 64-bit ELF LOAD-segment alignment before a Play release can be published.
- Keep Android 12+ support, API 37 targeting, the permanent `com.roadconquest.app` package ID, accounts, local-data deletion, fog safety fallbacks, and release signing behavior unchanged unless listed above.

Android version code is 36. Android 12 or newer is required.
