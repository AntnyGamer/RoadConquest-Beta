# RoadConquest 1.0 Beta

The initial release of RoadConquest: an Android driving exploration app that reveals a
cloud-covered map as you travel.

- Geographically anchored blue road lines, road matching with retry support and saved exploration.
- Cloud fog that stays attached to the map while panning, rotating and zooming, with cached
  viewport data and reduced rendering allocations.
- Streets, Minimal, Night and Satellite maps, plus Light, Dark and phone-controlled appearance.
- Local mileage and geometry-grouped road totals, tappable road details and recorded drive visits.
- Six achievements with individual progress bars.
- Automatic and manual tracking, background location controls and a Stop tracking notification.
- Driving-data export, optional accounts, username changes, privacy controls and account/device
  data deletion.

Android 12 or newer is required. The APK uses the permanent RoadConquest signing identity and
internal version code 26, allowing installation over the latest signed development build while
retaining its current-schema data. The user-visible version is 1.0 Beta.

Verified competitive scoring requires separate production Play Integrity and private road
matching/catalog configuration. Account hosting alone does not activate it; local totals are never
substituted for verified scores.

Release assets include the signed APK, Android Studio source project and SHA-256 checksums.
