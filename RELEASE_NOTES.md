# Road Conquest 1.0 Beta 9

- Strengthen road matching around turns by widening high-quality OSRM candidate searches while retaining strict post-match distance, geometry, confidence, and detour validation.
- Give ambiguous junction retries context on both sides of the turn, including an approach anchor and up to two exit-side fixes.
- Retry partial turn matches sooner and run a final repair pass when Android Location is turned off.
- Serialize that final repair behind queued GPS persistence so the last accepted fixes cannot be missed at the end of a drive.
- Keep unresolved GPS intervals visible as a thin translucent trace until confirmed road geometry replaces them; provisional traces never count as unlocked roads.
- Fix same-named town overlay selection by preferring the boundary nearest the recorded discovery location, including township and administrative results.
- Invalidate stale place-overlay cache entries and shorten negative boundary caching so corrected town boundaries can recover quickly.
- Retain the explored-place overlay behavior: country below state below town below fog, one overlay category active at a time, with tap-for-info support.
- Remove redundant full-screen root backgrounds that duplicated the theme window background and caused avoidable overdraw.
- Keep Android 12+ support, the permanent `com.roadconquest.app` package ID, local-data/privacy behavior, accounts, achievements, cosmetics, fog, and leaderboard security semantics unchanged unless listed above.

Android version code is 35. Android 12 or newer is required.
