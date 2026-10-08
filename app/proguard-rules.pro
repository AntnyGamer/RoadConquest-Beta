-keep class org.maplibre.** { *; }
-dontwarn org.maplibre.**
-dontwarn org.locationtech.jts.**

# Room instantiates this generated WorkManager database through reflection at startup.
-keep class androidx.work.impl.WorkDatabase_Impl { public <init>(); }
