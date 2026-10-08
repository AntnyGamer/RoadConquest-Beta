# Road Conquest 1.0 Beta 16

- Include the post-Beta 15 refinements currently on `main`.
- Reuse compiled place-overlay and place-resolver regular expressions and immutable lookup collections instead of reconstructing them repeatedly.
- Use equivalent literal overlay color values instead of runtime color-string parsing, without altering the displayed overlay colors.
- Remove an obsolete, unreferenced Mapzen terrain-credits asset.
- Preserve the Beta 15 road gap fixes, audited 11 m low-speed snap tolerance, existing fog appearance, location sampling cadence, and the Android 12+ minimum.
- Continue publishing signed APK and Play-ready AAB artifacts, R8 mapping, MapLibre native debug symbols, project source, checksums, and the tested account-service bundle.

Android version code is 42. Android 12 or newer is required.
