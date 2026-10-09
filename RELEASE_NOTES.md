# Road Conquest 1.0 Beta 24

- Repair short visual breaks at confirmed intersections, including the Flanders Road → Colonial Road and nearby Colonial Road turn shown in the driving export. The map renderer identifies uniquely supported road ends first recorded seconds apart, computes a centerline corner, and displays a continuous blue line without a diagonal shortcut.
- Limit display-only joins to road endpoints within 30 meters and 15 seconds of one another, with matching road directions and a uniquely supported successor. Avoid connecting unrelated roads, long gaps, ambiguous intersections or different trips.
- Existing local road data is preserved. Joining displayed blue segments does not award additional roads, mileage, achievements or leaderboard credit and does not loosen OSRM matching, GPS acceptance, or confidence rules.
- Carry forward Beta 23's stalled GPS callback recovery, Beta 22's older-unmatched-fix retry fairness, unchanged permanent signing identity, and full native MapLibre debug symbols plus R8 mapping embedded in the Google Play bundle.

Android version code is 50. Android 12 or newer is required. Install the signed APK over your existing installation to retain local driving history. Do not uninstall or clear app storage.

For Google Play, upload only the signed `RoadConquest-1.0-beta.24.aab` from GitHub Releases. The application ID and upload-signing key remain unchanged.

Note: display-only junction repairs require accepted road geometry on both sides. They cannot recreate a drive with no GPS samples or safely guess an ambiguous intersection.
