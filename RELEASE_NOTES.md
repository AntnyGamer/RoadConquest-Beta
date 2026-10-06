# RoadConquest 1.0 Beta 7

- Make the zero-point starting place use the first good live/current location fix after a fresh install or device-data reset. Cached fixes from before the new location request cannot become the baseline, and the starting town/state/country still awards zero discovery points.
- Retry failed zero-point reverse geocoding quickly instead of blocking fresh place discovery for about an hour, and combine several geocoder results from the same coordinate to fill missing country/state/town fields more reliably.
- Show a truthful foreground-notification waiting state while Android Location is off, then switch back to tracking text when Location returns.
- Keep automatic tracking armed when Android Location is off, including after reboot and when switching Manual -> Always. The first good fix after Location is turned back on can immediately establish the fresh starting place and resume tracking.
- Reject mock-location fixes from local driving history so they cannot add blue roads or mileage.
- Prevent stale pre-reset background work from restoring roads/progression rewards, place candidates, achievements, ad progress, battery progress, or purchases after device data has been deleted.
- Read multi-query progression snapshots under the shared history lock so concurrent reset/reward activity cannot briefly produce mixed UI totals.
- Make local progression reset self-contained, including achievement preference/history state, so a fresh profile cannot inherit old maximum-road progress or announcement state.
- Keep exported town/state/country discovery totals aligned with the app: the zero-point baseline remains in `visited_places.csv` for history/overlays but does not inflate discovery counts in metadata.
- Count Account, Achievements, Garage & Shop, and Leaderboards as RoadConquest foreground screens so internal navigation cannot be mistaken for leaving and reopening the app.
- Remove the unnecessary full saved-road regroup on the first summary of each app process; current road groups are maintained transactionally as roads are stored.
- Flush successful partial place-overlay download batches immediately even when a later boundary request fails, and enforce the Nominatim request throttle across all overlay clients in the process.
- Restrict direct external launching of MainActivity; launcher aliases remain the exported app entry points.
- Retain the Beta 6 road-matching, fog, progression, account, overlay, and migration-removal behavior unless changed above.

Android version code is 33. Android 12 or newer is required.
