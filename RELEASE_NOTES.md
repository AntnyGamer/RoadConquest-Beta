# Road Conquest 1.0 Beta 30

- Completely redesign map fog as **soft, dark-gray layered clouds** anchored to a stable geographic Web Mercator coordinate system. Three deterministic cloud-detail scales provide broad mist masses at global zoom, cloud banks at regional zoom and soft wisps at street zoom, without temporal animation or jitter.
- The permanent explored **1-mile-square cells are holes in exactly that same world-referenced cloud image**. They cannot move separately from the cloud layer or the underlying map during camera pan, zoom or rotation.
- Remove the old per-camera texture phases and dynamically rotated viewport-projection cutouts, which could visibly relocate uncovered areas during zoom/pan. MapLibre now moves each raster together with the geographic map.
- Avoid the two-frame handoff in which both 80% opacity fog sources were displayed simultaneously, creating harsh 96%-opaque stripes and abrupt color changes.
- Use higher-resolution 896/1024-pixel geographically snapped raster regions when near land, rather than magnifying a 512-pixel world map across a city.
- At worldwide zoom, preserve fractional area coverage of visited cells much smaller than a world pixel; they must not become huge clear square blocks. Exact 1,500-foot outer fades are preserved at usable zoom levels.
- Keep the previously unlocked squares, road progress, 50-meter historical samples, driving statistics, turns, account/scoring data and purchase/ad behavior unchanged.
- Add regression tests for cloud color and alpha, whole-square cutouts, subpixel distant zoom, stable geographic cutout coordinates, stable geographic cloud shader phase and real Android map camera changes.

Version code **56**, minimum Android 12. Install the signed Beta 30 APK over the existing app without uninstalling or clearing data.

For Play Store upload, use only the signed `RoadConquest-1.0-beta.30.aab` attached to the GitHub Release. The existing upload certificate and embedded native debug symbols remain unchanged.
