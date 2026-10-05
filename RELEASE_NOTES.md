# RoadConquest 1.0 Beta 3

- Add a persistent points economy with a live map balance: 5 points per newly unlocked road,
  100 per new town, 500 per new state/region and 1,500 per new country, plus achievement rewards.
- Add Garage & Shop with four car-marker shapes, six car colors, six traveled-road colors and a
  3,000-point Golden RoadConquest UI/launcher icon. Purchases are one-time and can be re-equipped.
- Expand achievements with town, state/region and country milestones, 5% and 1% battery challenges,
  and requested rewarded-ad milestones at 5, 10, 25, 50 and 100 completed ads.
- Add a future rewarded-ad completion bridge without bundling an ad SDK yet; opening or dismissing
  an ad cannot increment progress.
- Discover administrative places without continuous background geocoding: tracking saves sparse
  local candidates and resolves them through Android's system geocoder only while the app is foregrounded.
- Preserve existing Beta 2 road/history data with an explicit database schema 9 -> 10 migration.
- Extend device-data deletion and ZIP export to progression data, purchases and place discoveries;
  export metadata schema is now 7.
- Modernize the interface with theme-aware rounded surfaces, normal-case buttons, a points chip and
  the new shop; the optional gold palette applies across app screens.
- Reduce unnecessary work by removing duplicate Shop/Settings refreshes, using aggregate point
  queries as reward history grows, avoiding battery DB writes above 5%, and only rebuilding map
  cosmetics when a selection actually changes.
- Keep the Beta 2 road-matching, GPS-gap, verified-drive and fog-caching fixes intact.
- Android version code is 29 and verified-scoring documentation includes it.

Android 12 or newer is required. This update retains the existing application ID and permanent
signing identity. Supported Beta 2 schema-9 installs migrate in place with saved driving data intact.
Ads themselves are not included in Beta 3; only the achievement/progression integration point is ready.
