-keep class org.maplibre.** { *; }
-dontwarn org.maplibre.**
-dontwarn org.locationtech.jts.**

# Temporary final-audit report; removed before Beta 15 merge.
-printusage build/outputs/mapping/release/usage.txt
