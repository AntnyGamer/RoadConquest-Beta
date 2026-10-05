# RoadConquest 1.0 Beta 2

- Replace obsolete saved road evidence as corrected matcher results arrive, while leaving untouched history visible during repair.
- Start a new moving-road matching interval after GPS gaps longer than 30 seconds instead of allowing OSRM to infer a long missing route.
- Keep nearby stationary pauses reconnectable so ordinary traffic-light or parking stops do not create unnecessary road gaps.
- Preserve queued verified-drive fixes across temporary Play Integrity or account-service failures and retry them after the cooldown.
- Keep detailed georeferenced fog visible during camera gestures while it still covers the viewport, falling back to world fog only when necessary.
- Keep verified-scoring version documentation synchronized with the Android build in CI; beta.2 uses Android version code 28.
- Attach the exact backend function bundle that passed server/database tests to every GitHub release.
- Correct the AGPL corresponding-source URL in NOTICE.

Android 12 or newer is required. This update retains the existing application ID, permanent signing
identity, current database schema and saved user data. Existing beta installs can update in place.
