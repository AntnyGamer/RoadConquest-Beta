package com.roadconquest.app.map

import org.json.JSONArray

object GeoJsonUtil {
    internal fun validRoadCoordinates(json: String): JSONArray? {
        val coordinates = runCatching { JSONArray(json) }.getOrNull() ?: return null
        if (coordinates.length() < 2) return null
        for (i in 0 until coordinates.length()) {
            val pair = coordinates.optJSONArray(i) ?: return null
            if (pair.length() < 2) return null
            val longitude = pair.optDouble(0, Double.NaN)
            val latitude = pair.optDouble(1, Double.NaN)
            if (longitude !in -180.0..180.0 || latitude !in -90.0..90.0) return null
            if (pair.length() != 2 || pair.opt(0) !is Number || pair.opt(1) !is Number) {
                coordinates.put(i, JSONArray().put(longitude).put(latitude))
            }
        }
        return coordinates
    }
}
