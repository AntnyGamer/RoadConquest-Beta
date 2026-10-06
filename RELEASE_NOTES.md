# Road Conquest 1.0 Beta 11

- Add dedicated thin outline layers for country, state/region, and town overlays so neighboring explored areas remain visually separated while preserving the existing country → state → town → fog stacking order.
- Keep overlay outlines anchored to geographic boundaries and rebuild line geometry from both Polygon and MultiPolygon boundary rings.
- Render confirmed and provisional blue road lines from zoom 6, while keeping cleared fog reveals at zoom 9 and closer so roads remain visible from farther away without creating a regional clear-fog halo.
- Ease the 50-to-1500-foot reveal fade so the clear core stays unchanged, the fade still reaches full fog at 1500 feet, and distant parts of the fade retain substantially more fog instead of looking like a bright glow.
- Increase the static seamless fog texture detail so regional and driving zooms both show visible cloud variation without adding animation or a battery loop.
- Skip detailed fog bitmap rebuilds entirely below the reveal zoom and reduce moving-camera bitmap refreshes to about 8 fps while MapLibre natively transforms the existing georeferenced bitmap between refreshes.
- When Android Location is off, force a fresh activity launch to the exact saved zero-point location even if MapView restored a previously centered camera. While Location is available, fresh live GPS still wins and controls initial centering.
- Recover the zero-point camera location both while baseline reverse geocoding is still pending and after the baseline has been resolved.
- Use the farther-out road visibility threshold consistently for road tapping and native map rendering.
- Match verified-leaderboard OSRM candidate search to the strengthened local turn envelope while retaining the stricter server-side 0.95 confidence, no-alternative, distance, speed, and catalog checks.
- Update the prominent in-app and hosted privacy disclosures to explicitly state that Road Conquest collects precise location data for driven-road and visited-place features in the background when automatic tracking is enabled and the app is closed or not in use.
- Simplify destructive controls to exactly two in-app actions: Delete account removes the cloud account while keeping local history and requires the current account password; Delete all data removes all local Road Conquest data while keeping the cloud account and leaderboard scores and requires typing `I confirm I want to delete all of my local data.` exactly. Local deletion no longer depends on account sign-in or network access; the public deletion webpage remains available for Google Play's external account-deletion requirement but is no longer a third in-app button.
- Add a CI gate that verifies 16 KB native-library ZIP alignment and 64-bit ELF LOAD-segment alignment before a Play release can be published.
- Keep Android 12+ support, API 37 targeting, the permanent `com.roadconquest.app` package ID, accounts, local-data deletion, fog safety fallbacks, and release signing behavior unchanged unless listed above.

Android version code is 37. Android 12 or newer is required.
