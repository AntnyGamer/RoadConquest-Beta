# RoadConquest 1.0 Beta 5

RoadConquest remembers the roads you drive and the places you visit, revealing them through a
cloud-textured fog map.

[Download the Android app](https://github.com/AntnyGamer/RoadConquest-Beta/releases) ·
[Source and issues](https://github.com/AntnyGamer/RoadConquest-Beta) ·
[GNU AGPL v3 license](LICENSE)

## Install

Android 12 or newer is required. Download the signed APK from Releases and install it on your
phone. Each release also includes the Android Studio project and SHA-256 checksums.

Grant Precise location. For automatic background tracking, choose Allow all the time in Android's
location settings and allow background battery use. Settings includes shortcuts to the relevant
Android screens. Allow notifications to see the tracking notification and its Stop tracking control.

This release keeps the permanent signing identity and application ID `com.roadfog.app`.
Its internal Android version code is 31 so it can update the latest signed development build
without uninstalling it. The current database schema, saved history, settings and accounts are
preserved. Older unsupported database schemas require a fresh installation; export history before
uninstalling, because exports currently have no in-app import.

## Explore

- Recorded driving appears while matching is pending; confirmed road geometry then refines it.
- Drive to save matched road lines and reveal the map. Accurate locations also reveal nearby
  places while walking or stopped, without adding driving miles.
- Earn points from newly unlocked roads, achievements and first visits to towns, states/regions
  and countries. Your current point balance is visible directly on the map.
- Open Garage & Shop to buy and equip car shapes, car colors, road colors, or the Golden
  RoadConquest UI and launcher icon. Purchases are one-time and equipped cosmetics can be changed later.
- Choose Streets, Minimal, Night or Satellite maps, and a Light, Dark or phone-controlled appearance.
- Use the map overlay button to highlight explored countries in blue, states/regions in purple, or towns in green. Only one overlay type is active at a time; tap a highlighted area for population and area when available.
- Tap an unlocked road for its name, saved length, first-unlocked time, last-driven time and times driven.
- Track road, mileage, place, low-battery and rewarded-ad achievements with progress bars and point rewards.
  Ad milestones are wired for a future rewarded-ad SDK; ads are not included in this release.
- Use automatic tracking or control it manually. Stop tracking from the foreground notification.
- Export driving history, road geometry, explored places, place discoveries, point awards,
  purchases and progression counters from Settings.
- Create an optional account, change your username, manage leaderboard visibility or delete your
  account. Device history can also be deleted while offline.

Turning Show fog off changes only its visibility. Tracking continues and the saved reveals return
when fog is enabled again. Manual tracking stays off until you enable it. Automatic tracking depends
on location permissions and Android's background-execution rules; a force-stopped app must be
opened manually before it can resume.

Mileage estimates distance between accepted driving fixes, including repeat drives. GPS speed is
a heuristic and cannot prove you are in a car. Long gaps, reversed timestamps and implausible jumps
are excluded. Local road totals group nearby fragments of the same normalized street name and connected
unnamed access lanes. Distant same-name roads remain separate. These totals are personal estimates;
public matching does not provide authoritative street identities.

## Accounts and leaderboards

The app defaults to the existing live HTTPS account service. Usernames are unique without regard
to capitalization. Passwords are salted and scrypt-hashed on the server; Android stores an encrypted
session token. Local exploration does not require an account.

Account availability and competitive scoring are separate. Verified leaderboard credit requires
the production Play Integrity credentials and a pinned private road matcher/catalog. Until those
are configured, the leaderboard reports verification as unavailable and does not rank local totals.
Competitive roads count distinct OSM way sections, rather than whole named streets.

Leaderboard visibility and live GPS sharing are separate settings. New accounts enable both after
the signup disclosure. Users can hide their profile or stop sharing live evidence from Settings.
Deleting an account revokes its sessions and removes its cloud competitive data; the default
deletion option also clears history on this phone. Other devices and exported files are separate.

The update requeues saved GPS history once with matcher revision 3 and rebuilds matched road
geometry from those raw points. Mileage and the GPS history remain intact during the rebuild; old
road geometry is cleared first so overlapping matcher windows cannot delete or preserve the wrong
pieces. A local-data reset also clears any stale repair state before new driving begins. Moving GPS
gaps longer than 30 seconds start a new matching interval; nearby stationary pauses can still
reconnect without inventing a route across a location outage.

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
The wrapper pins Gradle 9.6.0 with a verified checksum and Android Gradle Plugin 9.4.1.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease
./gradlew :app:connectedDebugAndroidTest
```

On Windows, use `gradlew.bat` with the same tasks. The debug APK is
`app/build/outputs/apk/debug/app-debug.apk`. Local release builds are unsigned until signed with
the permanent release key. Signing keys, local SDK paths, databases and build outputs are excluded
from Git.

GitHub Actions runs account/database tests, Android unit tests, debug and release lint, and native
map integration tests on Android 12, 15 and 17. The release job publishes the verified APK only after
all required jobs pass. It also attaches the exact account-function bundle produced by the tested
backend job, so the deployable server artifact for that release is preserved alongside the APK. It verifies the permanent signing-certificate digest and includes the source
ZIP and checksums. Configure these repository secrets from the private signing backup:

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

## Privacy and map credits

Driving history, explored places, points and purchases stay on the phone. Road matching sends small
coordinate batches to the selected OSRM service. Town/state/country labels are requested through
Android's system geocoder only while RoadConquest is foregrounded; the geocoder implementation and
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

RoadConquest's original source is licensed under GNU Affero General Public License v3 only
(`AGPL-3.0-only`). The complete license is in [LICENSE](LICENSE) and bundled with the app.
