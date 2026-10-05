# RoadConquest 1.0 Beta 5

- Reorganize Settings into clearer Appearance, Tracking, Account, Progress, Data and privacy,
  and About sections.
- Replace several slash and ampersand-heavy Settings labels with simpler wording and move export,
  privacy details, credits, and project links into the sections where they belong.
- Treat the first resolved town, state/region, and country after a fresh install or data reset as
  the starting baseline instead of awarding 2,100 points and three discoveries for opening the app.
- Safely convert an existing unspent Beta 4 starter trio into that zero-point baseline when it can
  be identified without rewriting purchases or place-achievement rewards.
- Keep later town, state/region, and country discoveries rewardable exactly once, while preserving
  existing place progress from earlier builds.
- Add a mutually exclusive map overlay picker: explored countries are blue, states/regions purple, and towns green, with semi-transparent native map fills below the fog.
- Make highlighted areas tappable for place type, population when available, and computed boundary area.
- Cache overlay boundaries locally and throttle opt-in boundary lookups so overlays do not add continuous background work.
- Add regression coverage for the fresh-slate place baseline behavior and overlay parsing.
- Keep the Beta 4 classic car marker, zoom cap, sword points icon, progression shop, tracking,
  account, and leaderboard systems unchanged.

Android version code is 31. Android 12 or newer is required.
