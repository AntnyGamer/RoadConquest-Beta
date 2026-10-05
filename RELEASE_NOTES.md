# RoadConquest 1.0 Beta 1

- Preserve continuous recorded driving while matching is pending, including offline driving.
- Retain confident, context-supported route geometry through ordinary junctions instead of discarding every alternative-candidate fix.
- Reject snaps far from GPS evidence and unsupported route detours.
- Count nearby street fragments and connected unnamed access lanes as road identities, rather than one road per fragment.
- Recalculate saved road identities and reprocess recorded GPS history while retaining mileage and existing map geometry.
- Keep cached world fog ready while rapid zooming or panning outruns the detailed fog bitmap.
- Refresh detailed fog during gestures with throttled background rendering and immutable projection snapshots.
- Account for screen size, rotation and camera latitude when limiting the widest zoom, preventing exposed world edges.
- Add regression checks for fast zooming, rotated coverage and invalid fog footprints.
- Add weekly dependency update pull requests for Android, the account server and GitHub Actions.
- Add consistent editor formatting and correct links to the new beta repository.

Android 12 or newer is required. This update retains the existing application ID, signing identity
and saved data, with internal version code 27.
