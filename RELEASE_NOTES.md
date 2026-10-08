# Road Conquest 1.0 Beta 15

- Reduce tiny random road gaps by retrying older eligible unresolved fixes instead of allowing fresh matching work to starve the retry backlog.
- Preserve valid sibling road geometry when one OSRM leg is locally implausible, and repair only small, high-confidence same-road seams without crossing an explicitly rejected interval.
- Keep normal-driving snap tolerance unchanged while using an 11 m low-speed floor: substantially more room than the original strict floor while still rejecting the supplied roughly 16 m nearby-road false snap.
- Treat missing or unreliable Android speed metadata consistently, using displacement only when it beats combined GNSS uncertainty; measured low speed remains authoritative.
- Never round OSRM candidate radiuses below reported fractional GNSS uncertainty.
- Respect Android 14+ location foreground-service runtime prerequisites instead of attempting impossible starts while system Location is off.
- Remove strictly redundant map/provider work: use one enabled-provider snapshot for cached-location lookup, skip listener-removal IPC when no tracking listener is registered, reuse the existing empty GeoJSON singleton directly, and use compile-time overlay colors instead of parsing fixed strings.
- Keep R8 minification, Play deobfuscation metadata, exact MapLibre native debug symbols, 16 KB native-library verification, the permanent package/signing identity, Android 12+ support, and existing local/cloud deletion semantics.

Android version code is 41. Android 12 or newer is required.
