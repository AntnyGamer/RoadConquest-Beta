# Road Conquest 1.0 Beta 17

- Stop hidden-map visibility polling, redraw timers, and follow-up viewport work when the activity stops. Ordinary pauses keep the visible map active; returning refreshes saved roads and preserves the original expiry of live location fixes.
- Reduce repeated native camera reads and avoid temporary collections for empty road overlays, while preserving fog resolution and appearance.
- Include the road-matching fixes made since Beta 16: richer turn-retry context, safer bearing guidance at sharp turns, and conservative repair of short, confidently aligned unnamed-road seams without awarding extra road-unlock credit.
- Expire each provisional GPS trail at its actual age, including on an idle map, while retaining recorded GPS evidence for matching retries. Do not schedule expiry refreshes for disconnected points that draw no trail.
- Keep location sampling cadence, tracking filters, fog quality, and the Android 12+ minimum unchanged by the map optimizations.
- Publish signed APK and AAB artifacts, R8 mapping, MapLibre native debug symbols, exact project source, SHA-256 checksums, and the tested account-service bundle.

This beta is published from `perf/battery-and-rendering-review`; the optimization PR remains unmerged.
Android version code is 43. Android 12 or newer is required.
