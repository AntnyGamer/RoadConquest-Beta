# Road Conquest 1.0 Beta 16

Road Conquest remembers the roads you drive and the places you visit, revealing them through a
cloud-textured fog map.

[Download the Android app](https://github.com/AntnyGamer/RoadConquest-Beta/releases) ·
[Source and issues](https://github.com/AntnyGamer/RoadConquest-Beta) ·
[GNU AGPL v3 license](LICENSE)

## Install

Android 12 or newer is required. Download the signed APK from Releases for direct installation.
For Google Play, use the signed Android App Bundle (AAB) from the same verified release. Each release
also includes the Android Studio project and SHA-256 checksums.

Grant Precise location. For automatic background tracking, choose Allow all the time in Android's
location settings and allow background battery use. Settings includes shortcuts to the relevant
Android screens. Allow notifications to see the tracking notification and its Stop tracking control.

This release uses Android application ID `com.roadconquest.app` and internal version code 42.
The app shows its exact version in Settings → About. Treat the application ID as permanent once
the Google Play listing is created; changing it later would create a different app.

## Explore

- Recorded driving appears while matching is pending; confirmed road geometry then refines it.
- Drive to save matched road lines and reveal the map. Accurate locations also reveal nearby
  places while walking or stopped, without adding driving miles.
- Earn points from newly unlocked roads, achievements and first visits to towns, states/regions
  and countries. Your current point balance is visible directly on the map.
- Open Garage & Shop to buy and equip car shapes, car colors, road colors, or the Golden
  Road Conquest UI and launcher icon. Purchases are one-time and equipped cosmetics can be changed later.
- Choose Streets, Minimal, Night or Satellite maps, and a Light, Dark or phone-controlled appearance.
- Use the map overlay button to highlight explored countries in blue, states/regions in purple, or towns in green. Only one overlay type is active at a time; tap a highlighted area for population and area when available.
- Tap an unlocked road for its name, saved length, first-unlocked time, last-driven time and times driven.
- Browse achievements by **Road Conquest**, **Mileage**, **Exploration**, **Ad Rewards**, and **Bonus** categories. Battery challenges live under Bonus; ad milestones have their own Ad Rewards category.
  Ad milestones are wired for a future rewarded-ad SDK; ads are not included in this release.
- Use automatic tracking or control it manually. Stop tracking from the foreground notification.
- Export driving history, road geometry, explored places, place discoveries, point awards,
  purchases and progression counters from Settings.
- Create an optional account, change your username and manage leaderboard visibility. There are exactly two destructive account/data actions in the app: Account → Delete account removes the cloud account while keeping saved device history and requires the current account password; Settings → Data and privacy → Delete all data removes all local Road Conquest data while keeping the cloud account and leaderboard scores and requires typing the exact local confirmation phrase. Local full-data deletion works while signed out, stops tracking, disables verified-drive sharing and removes private interrupted-export snapshots.

Turning Show fog off changes only its visibility. Tracking continues and the saved reveals return
when fog is enabled again. Manual tracking stays off until you enable it. On Android 12-13,
automatic mode can keep its foreground service ready while Android Location is off. Android 14+
requires system Location to be enabled before a location foreground service can start; while the
app is open, Road Conquest starts tracking as soon as Location is enabled. If a reboot happens with
Location off on Android 14+, enable Location and open Road Conquest to restart tracking. After a
fresh install or device-data reset, the first good live fix defines the zero-point starting
town/state/country and awards no discovery points. Cached fixes from before the new location request
cannot become that baseline. Automatic tracking still depends on location permissions and Android's
background-execution rules; a force-stopped app must be opened manually before it can resume.

Mileage estimates distance between accepted driving fixes, including repeat drives. GPS speed is
a heuristic and cannot prove you are in a car. Long gaps, reversed timestamps and implausible jumps
are excluded. Local road totals use a human-road identity layer: signed route refs from the existing
OSRM match response take priority, connected same-name fragments are joined, nearby divided
carriageways with the same route ref are treated as one road, and anonymous on/off ramps do not
inflate the count. Distant same-name or same-ref roads remain separate unless driven geometry
establishes a local connection. The counter is intentionally human-oriented rather than a raw
OSM-way or matcher-fragment count.

## Accounts and leaderboards

The app defaults to the existing live HTTPS account service. Usernames are unique without regard
to capitalization. Passwords are salted and scrypt-hashed on the server; Android stores an encrypted
session token. Local exploration does not require an account.

Account availability and competitive scoring are separate. Verified leaderboard credit requires
the production Play Integrity credentials and a pinned private road matcher/catalog. Until those
are configured, the leaderboard reports verification as unavailable and does not rank local totals.
Competitive roads count distinct OSM way sections, rather than whole named streets.

Leaderboard visibility and live GPS sharing are separate settings. New accounts can be visible on
leaderboards, but verified-drive GPS sharing remains off until the user explicitly enables it in Settings.
Deleting an account revokes its sessions and removes its cloud competitive data while leaving
saved device history in place. Deleting device data also disables verified-drive
GPS sharing before tracking shuts down, so queued evidence is not submitted afterward. Other devices
and user-saved exported files are separate.

Moving GPS gaps longer than 30 seconds start a new matching interval; nearby stationary pauses can
still reconnect without inventing a route across a location outage.

The fog keeps a cached world layer ready during fast gestures and refreshes detailed reveals
while the camera moves. A detailed reveal stays visible during a gesture while its georeferenced
bitmap still covers the viewport, avoiding the old flash back to fully covered fog. The widest zoom
accounts for the screen diagonal and camera latitude so rotation keeps the map inside its world edges.

Place discovery is intentionally sparse: background tracking records only an occasional local
candidate, while town/state/country names are resolved later on a foreground worker through
Android's system geocoder. Point refreshes use aggregate database queries, Shop/Settings avoid
duplicate launch refreshes, and map cosmetics are only regenerated when their selection changes.
These choices keep the progression system from adding continuous background network or rendering work.

## Build and verify

Open the project in Android Studio with JDK 21 or newer, Android SDK 37.0 and Build Tools 36.0.0.
The wrapper pins Gradle 9.6.1 with a verified checksum and Android Gradle Plugin 9.4.1.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease :app:bundleRelease
./gradlew :app:connectedDebugAndroidTest
```

On Windows, use `gradlew.bat` with the same tasks. The debug APK is
`app/build/outputs/apk/debug/app-debug.apk`. Local release builds are unsigned until signed with
the permanent release key. Signing keys, local SDK paths, databases and build outputs are excluded
from Git.

GitHub Actions runs account/database tests, Android unit tests, debug and release lint, and native
map integration tests on Android 12, 15 and 17. The release job publishes the verified signed APK and Play-ready signed AAB only after
all required jobs pass and only when that version tag does not already exist; ordinary commits at an
already-published version are verified without retargeting or overwriting the release. It also attaches the exact account-function bundle produced by the tested
backend job, so the deployable server artifact for that release is preserved alongside the Android release artifacts. It verifies the permanent signing-certificate digest and includes the source
ZIP and checksums for the APK, AAB, source archive and server bundle. Configure these repository secrets from the private signing backup:

- `ROADCONQUEST_RELEASE_KEYSTORE_BASE64`
- `ROADCONQUEST_RELEASE_KEY_ALIAS`
- `ROADCONQUEST_RELEASE_STORE_PASSWORD`
- `ROADCONQUEST_RELEASE_KEY_PASSWORD`

Keep the signing backup outside the repository and release assets. Each release needs a new
`versionName` and increasing `versionCode`; two-part and three-part release versions are supported.

Override the account endpoint with the Gradle property
`ROADCONQUEST_ACCOUNT_API_URL=https://accounts.example.com`. Local matching defaults to the public
OSRM demo; a compatible controlled matcher can be selected with
`ROADCONQUEST_OSRM_API_URL=https://roads.example.com`. Administrative overlay boundaries are requested only after the user enables an overlay; results are cached and requests are throttled. The APK reads the active provider from `OVERLAY_PROVIDER.txt` on this repository’s `main` branch so the service can be switched or disabled without an app update. Custom builds can point at another remote config with `ROADCONQUEST_PLACE_OVERLAY_CONFIG_URL=https://example.com/overlay-provider.txt`.
See [server setup](server/README.md) and the [physical-device checklist](ANDROID_TEST_CHECKLIST.md).

## Google Play submission

The release bundle targets API 37, uses application ID `com.roadconquest.app`, and is signed for Play upload.
CI verifies 16 KB native-library packaging/alignment before publication, in addition to unit tests, lint,
release builds, account-server tests, and native map tests on supported Android generations.
The account service exposes browser pages at `/privacy` and `/delete-account`. The app links the privacy policy from
Settings; keep the deletion page publicly reachable for the Play Console account-deletion field without adding a third in-app delete action. The first Android location permission flow is preceded by a prominent disclosure
covering precise location, background use, road matching, and optional verified scoring.

Play Console still requires the publisher to complete the Data safety form, background-location declaration,
developer/package registration, store listing assets, review access where applicable, and the other account-level
declarations that cannot be supplied by source code alone.

## Privacy and map credits

Driving history, explored places, points and purchases stay on the phone. Road matching sends small
coordinate batches to the selected OSRM service. Town/state/country labels are requested through
Android's system geocoder only while Road Conquest is foregrounded; the geocoder implementation and
network behavior are supplied by the device. Pending data remains saved for retry after network or
matching failures. User-selected ZIP exports contain precise history, road geometry and progression data.

When enabled and available, verified scoring sends precise live GPS batches with Play Integrity
evidence for independent server matching. The server retains scores, short-lived receipt hashes,
and a timestamp plus one-way fingerprint for the last accepted boundary fix; it does not retain
complete route history. Saved local totals and old history are never accepted as competitive scores.

Street maps use OpenFreeMap, OpenMapTiles and OpenStreetMap contributors. Satellite imagery uses
Esri World Imagery and its credited providers. Map providers receive requests for the area you view.
Styles are bundled; tiles, sprites, fonts and imagery require network access or an existing cache.
Satellite detail varies by area.

Settings → Licenses and map credits contains the app license and provider notices. Third-party
styles, map data, imagery and dependencies retain their own licenses and service terms. See
[NOTICE](NOTICE) and the credits under `app/src/main/assets/`.

## License

Road Conquest's original source is licensed under GNU Affero General Public License v3 only
(`AGPL-3.0-only`). The complete license is in [LICENSE](LICENSE) and bundled with the app.
