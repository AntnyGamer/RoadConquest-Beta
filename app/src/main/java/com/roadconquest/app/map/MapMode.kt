package com.roadconquest.app.map

enum class MapMode(val label: String, val styleAsset: String) {
    STREETS("Streets", "styles/liberty.json"),
    // Preserve the saved preference key while replacing the old hillshade mode with imagery.
    TERRAIN("Satellite", "styles/satellite.json"),
    MINIMAL("Minimal", "styles/positron.json"),
    NIGHT("Night", "styles/dark.json")
}
