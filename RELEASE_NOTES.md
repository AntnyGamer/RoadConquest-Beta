# Road Conquest 1.0 Beta 21

- Improve retries for short unmatched road gaps near turns. Use spare request slots for contiguous GPS fixes on both sides of the gap, giving the matcher more approach and exit evidence.
- Preserve the ten-fix request limit, live-batch throughput, GPS sampling, mileage calculations, and existing confidence, snap-distance and detour checks. Ambiguous drive endpoints still wait for supporting evidence.
- Stop retry context at missing raw fixes and existing trip boundaries. Already resolved context points never become newly markable points.
- Add a captured OSRM turn regression and native-GPS emulator driving tests with 18 turns, rounded corners, lane offsets, noisy and sparse fixes, a traffic-light stop, and screen-off tracking. Check Android's actual GPS time and speed before accepting replay results.
- Retain the Beta 19 startup-crash repair and Beta 20 signed Play bundle with complete native symbols and R8 mapping embedded.

Android version code is 47. Android 12 or newer is required. Install the signed APK over the existing version to retain local history. Do not uninstall or clear app storage.

Upload only the signed `RoadConquest-1.0-beta.21.aab` from GitHub Releases to Google Play. The full native symbols and R8 mapping are embedded. The app ID and signing key are unchanged.
