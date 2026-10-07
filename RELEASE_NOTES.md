# Road Conquest 1.0 Beta 13

- Improve road matching with conservative course guidance derived from accepted GPS movement, helping OSRM distinguish parallel/divided roads without increasing GPS frequency, search radiuses, or lowering confidence thresholds.
- Use Android speed-accuracy metadata so explicitly unreliable measured speed cannot by itself make GPS drift look like driving; clear movement evidence still works normally.
- Replace the old local road-count heuristic with a stronger human-road identity layer: signed route refs plus local topology group technical OSM/OSRM fragments into the road a driver would normally perceive as one road.
- Keep divided carriageways and connected signed-route fragments together, normalize route-reference formatting, and handle concurrent/multiplexed route refs without collapsing distinct routes after they split.
- Keep on/off ramps and anonymous roundabouts visible as driven blue geometry without inflating Roads Unlocked; genuinely named roundabouts still count.
- Normalize worldwide road names and route refs with Unicode-compatible rules so equivalent international labels do not become duplicate road identities.
- Keep road points and road achievements tied to the human-road count while preserving already-earned rewards if later grouping correctly merges previously separate fragments.
- Fix an intermittent one-frame uncovered fog corner during rapid rotated zooms by overlapping the detailed/world fog raster handoff for one rendered frame.
- Version exported local-data metadata as schema 8 to reflect the updated road-group identity semantics.
- Preserve the existing 3-second tracking cadence, matching retry/gap protections, fog reveal distances, blue-road geometry, database version, Android 12+ support, package ID, signing identity, account behavior, and local/cloud deletion semantics.
- Extend regression coverage for course guidance, speed uncertainty, route refs, ramps, roundabouts, multiplexed routes, Unicode road names, human-road grouping, export metadata, and rapid fog transitions.

Android version code is 39. Android 12 or newer is required.
