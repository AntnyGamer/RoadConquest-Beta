# Android physical validation checklist

This checklist is for final device-level validation on supported Android 12+ phones and tablets that cannot be fully reproduced in the build environment.

## Install and permissions

1. With JDK 21+, Android SDK 37.0 and Build Tools 36.0.0 installed, open the project in Android Studio and complete a Gradle sync/build. The repository includes the full Gradle wrapper.
2. Install the debug build on the Android device.
3. Grant **Precise** location.
4. For automatic restart/background-created tracking, set Location to **Allow all the time** in Android app settings.
5. Allow notifications so the foreground tracking notification is visible.

## UI and map

1. Launch RoadConquest in portrait and landscape.
2. Confirm the Settings gear is clear of the status bar/camera cutout.
3. Confirm the bottom panel is clear of gesture navigation.
4. Confirm MapLibre attribution/logo remain visible above the bottom panel.
5. Confirm the labeled OpenFreeMap base map loads.
6. Confirm the map centers on the first fresh phone-location fix and the car icon follows current position.
7. Zoom in fully and confirm fog, blue unlocked-road lines, and the car marker remain visible at RoadConquest's maximum zoom on the device.
8. Zoom progressively outward and confirm the cloud pattern stays broad instead of collapsing into many tiny repeated texture tiles.
9. At world zoom and the next three zoom levels, pan through several full world copies in both directions. Confirm fog stays over the whole map while crossing the date line and looping. At intermediate zooms, blue lines stay visibly above fog as they load.

## Driving and fog

1. Drive a short route with several turns, including one slow section. At a normal intersection, confirm the blue approach joins the actual exit without unlocking the opposite side. Allow matching to catch up after an uncertain GPS fix; a pending section may appear later when the turn is clear.
2. Lock the screen for at least several minutes while driving and verify the foreground notification remains active.
3. Reopen RoadConquest and confirm driven roads are blue and map-matched to the roadway rather than the raw GPS trace.
4. Confirm fog is clear near unlocked roads and visited/live places, begins fading around 50 ft, and reaches 80% around 1,500 ft. Repeated roads and overlapping visited areas must not push the start farther out.
5. Re-drive part of the same route; export data and verify `first_unlocked_utc` stays the original time while `last_driven_utc` advances.
6. Walk briefly with RoadConquest tracking and confirm nearby places stay uncovered after walking away and reopening the app. Walking should not add driving miles or blue matched roads. Repeat with fog hidden, then turn fog back on. (Fast cycling can still resemble slow driving to the current speed heuristic.)

## Tracking modes and restart behavior

1. In **Always** mode, leave RoadConquest and confirm tracking continues.
2. Reboot the phone with background location granted and verify RoadConquest resumes according to the device's Android background-execution policy.
3. Switch to **Manual** mode, disable tracking, leave/reopen the app, and confirm it stays off until **Enable** is pressed.
4. Switch Manual -> Always and confirm tracking starts without needing another app restart.
5. Force-stop RoadConquest and confirm it does **not** restart itself until manually launched; this is expected Android behavior.

## Export

1. Use **Export driving data** in Settings.
2. Open the ZIP and verify `metadata.json`, `track_points.csv`, `roads.csv`, and `explored_places.csv` are present. Metadata schema version is 6 and the explored-place count matches its CSV.
3. Verify road rows contain segment ID, road name, first-unlocked time, last-driven time, and the `coordinates_json` geometry array.

If all of the above pass on the target Android device and firmware, that provides the device-level validation that static analysis cannot substitute for.

## Account maintenance

- Change a username using the current password; verify the new name in Account settings and
  after signing in again, with scores and privacy preserved. A taken name or wrong password
  must leave the original account unchanged.
- Cancel both deletion confirmations and verify that all data remains.
- Delete an account with the current password; verify that all old sessions stop working.
  The default local-history option clears trips, mileage, roads and explored places.
- Delete device data while offline and signed out. Tracking stops, history and fog reveals
  clear, and delayed road matches cannot restore them. The cloud account remains when only
  device data is deleted. Tracking can be explicitly restarted.
