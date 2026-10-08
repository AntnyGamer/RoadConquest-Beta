# Road Conquest 1.0 Beta 15

- Make local road matching substantially less prone to tiny provisional gaps: keep valid sibling OSRM legs, prevent expired retries from starving behind fresh points, and repair only tightly bounded same-road split seams.
- Never let seam repair reintroduce an interval that failed local plausibility checks.
- Balance genuine low-speed road-centerline/GNSS offset against nearby parallel-road false snaps with the audited 11 m low-speed snap floor; normal-speed matching keeps its previous generous allowance.
- Handle missing or unreliable Android speed metadata consistently across driving acceptance, persistence, bearing guidance, long-gap continuity, and matching, using displacement only when it beats GNSS uncertainty.
- Never round OSRM candidate radiuses below reported GNSS uncertainty.
- Respect Android 14+ location foreground-service startup prerequisites and skip database, progression, verification, and notification setup on starts that cannot legally proceed.
- Reduce invisible runtime work without changing sampling or visuals: avoid repeated preview-listener removal, reject permanently ineligible verified-drive fixes before copying/queueing them, query enabled location providers once per decision, remove redundant provider-state preflights, and reuse appearance decisions.
- Remove source helpers/exports proven unused by repository-wide reference checks while preserving the intentionally future rewarded-ad integration.
- Keep GPS sampling cadence, map/fog visual quality and render thresholds, road-counting rules, package/signing identity, Android 12+ support, and local/cloud deletion semantics unchanged.
- Continue publishing R8 mapping metadata, native MapLibre debug symbols, 16 KB compatibility checks, signed APK/AAB artifacts, source, checksums, and the tested account-function bundle.

Android version code is 41. Android 12 or newer is required.
