# Road Conquest 1.0 Beta 12

- Replace the repeating sine/cosine fog texture with seamless domain-warped fractal noise so the map fog reads as natural mist instead of a visible grid.
- Keep the fog texture static and deterministic while precomputing its noise lattices and color palette to reduce generation work with identical rendered output.
- Reduce unnecessary map work by skipping fog rebuilds for heading-only or identical live-location updates, reusing MapLibre camera snapshots within callbacks, and avoiding duplicate place-overlay reloads.
- Reduce background/UI overhead by suppressing UI-only tracking broadcasts when the map is not listening, avoiding duplicate foreground-notification updates, and reusing notification/PendingIntent objects.
- Reduce tracking-path allocation and database overhead by avoiding temporary one-item location lists, reusing accepted location snapshots, suppressing duplicate explored-cell writes, and caching exact history summaries and the last persisted track point.
- Reduce progression/account overhead by combining small database reads, avoiding repeated low-battery checks within a tracking session, and caching validated account endpoint parsing.
- Preserve the existing high-accuracy fused + GPS tracking behavior, 3-second tracking cadence, road-matching cadence and geometry, road/mileage counting, fog reveal distance, map visual quality, scoring values, and Android 12+ support.
- Add and extend regression coverage for fog seams/pixel equivalence, summary-cache correctness, raw database mutation safety, endpoint-cache invalidation, provider behavior, and background broadcast suppression.
- Keep the permanent `com.roadconquest.app` package ID, API 37 target, release signing identity, account behavior, and local/cloud data deletion semantics unchanged.

Android version code is 38. Android 12 or newer is required.
