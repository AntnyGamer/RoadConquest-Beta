# Road Conquest 1.0 Beta 14

- Enable R8 minification for release builds so Google Play receives a real Java/Kotlin deobfuscation mapping for crashes and ANRs.
- Keep resource shrinking disabled, preserving resource packaging behavior while still reducing/obfuscating bytecode.
- Embed exact MapLibre Native 13.6.1 OpenGL release debug symbols into the signed Play App Bundle.
- Pin the MapLibre symbol archive by SHA-256 and verify every ABI's ELF Build ID against the exact libmaplibre.so packaged in Road Conquest before publication.
- Publish the R8 mapping file and a standalone native-symbol archive alongside the release as debugging/recovery assets.
- Preserve the permanent package ID/signing identity, Android 12+ support, tracking cadence and accuracy, road matching/counting, fog behavior, account behavior, and local/cloud deletion semantics.

Android version code is 40. Android 12 or newer is required.
