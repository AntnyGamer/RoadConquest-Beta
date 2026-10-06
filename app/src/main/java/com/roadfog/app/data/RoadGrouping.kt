package com.roadfog.app.data

import org.json.JSONArray
import java.util.Locale
import kotlin.math.*

/**
 * Groups locally saved matched fragments into user-facing road identities.
 *
 * Public OSRM does not expose authoritative OSM way IDs, so local counts remain estimates.
 * Count nearby fragments of the same street together despite GPS/matcher seams. Connected
 * unnamed access lanes form one local road network rather than one road per sampling fragment.
 * This groups identities only: it never invents geometry across an unrecorded gap.
 */
internal object RoadGrouping {
    const val JOIN_TOLERANCE_M = 75.0
    private const val UNNAMED_JOIN_TOLERANCE_M = 25.0
    private const val EARTH_RADIUS_M = 6_371_008.8
    private const val METERS_PER_DEGREE = 111_320.0

    data class Road(
        val segmentId: String,
        val name: String,
        val geometryJson: String,
        val minLatitude: Double,
        val maxLatitude: Double,
        val minLongitude: Double,
        val maxLongitude: Double,
        val groupId: String? = null
    ) {
        val nameKey: String = normalizeName(name)
        val coordinates: DoubleArray? by lazy(LazyThreadSafetyMode.NONE) { parseCoordinates(geometryJson) }
    }

    fun normalizeName(name: String): String = name.trim().lowercase(Locale.US)
        .replace(Regex("\\s+"), " ").let { if (it == "unnamed road") "" else it }

    fun isUnnamed(nameKey: String): Boolean =
        nameKey.isEmpty() || nameKey == "unnamed road"

    fun assignGroups(roads: List<Road>): Map<String, String> {
        if (roads.isEmpty()) return emptyMap()
        val result = HashMap<String, String>(roads.size)
        val named = roads.groupBy { it.nameKey }

        for (sameName in named.values) {
            val count = sameName.size
            if (count == 1) {
                val only = sameName[0]
                result[only.segmentId] = only.segmentId
                continue
            }
            val parent = IntArray(count) { it }
            fun root(index: Int): Int {
                var current = index
                while (parent[current] != current) {
                    parent[current] = parent[parent[current]]
                    current = parent[current]
                }
                return current
            }
            fun union(a: Int, b: Int) {
                val first = root(a)
                val second = root(b)
                if (first != second) parent[second] = first
            }

            val order = sameName.indices.sortedBy { sameName[it].minLatitude }
            val latitudePad = JOIN_TOLERANCE_M / METERS_PER_DEGREE
            for (position in order.indices) {
                val firstIndex = order[position]
                val first = sameName[firstIndex]
                for (nextPosition in position + 1 until order.size) {
                    val secondIndex = order[nextPosition]
                    val second = sameName[secondIndex]
                    if (second.minLatitude > first.maxLatitude + latitudePad) break
                    if (connected(first, second)) union(firstIndex, secondIndex)
                }
            }

            val canonical = HashMap<Int, String>()
            for (index in sameName.indices) {
                val component = root(index)
                val id = sameName[index].segmentId
                canonical[component] = minOf(canonical[component] ?: id, id)
            }
            for (index in sameName.indices) {
                result[sameName[index].segmentId] = requireNotNull(canonical[root(index)])
            }
        }
        return result
    }

    fun connected(first: Road, second: Road): Boolean {
        if (first.nameKey != second.nameKey) return false
        if (!boundsCanTouch(first, second)) return false
        val a = first.coordinates ?: return false
        val b = second.coordinates ?: return false
        if (a.size < 4 || b.size < 4) return false
        val tolerance = if (isUnnamed(first.nameKey)) UNNAMED_JOIN_TOLERANCE_M else JOIN_TOLERANCE_M
        return endpointToPolylineMeters(a[0], a[1], b) <= tolerance ||
            endpointToPolylineMeters(a[a.size - 2], a[a.size - 1], b) <= tolerance ||
            endpointToPolylineMeters(b[0], b[1], a) <= tolerance ||
            endpointToPolylineMeters(b[b.size - 2], b[b.size - 1], a) <= tolerance
    }

    private fun boundsCanTouch(first: Road, second: Road): Boolean {
        val latitudePad = JOIN_TOLERANCE_M / METERS_PER_DEGREE
        if (first.maxLatitude + latitudePad < second.minLatitude ||
            second.maxLatitude + latitudePad < first.minLatitude
        ) return false

        // Raw min/max longitude is not safe at ±180°. Exact endpoint-to-polyline
        // distance below already normalizes longitude on the globe.
        return true
    }

    private fun parseCoordinates(json: String): DoubleArray? {
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return null
        if (array.length() < 2) return null
        val output = DoubleArray(array.length() * 2)
        for (index in 0 until array.length()) {
            val coordinate = array.optJSONArray(index) ?: return null
            if (coordinate.length() < 2) return null
            val longitude = coordinate.optDouble(0, Double.NaN)
            val latitude = coordinate.optDouble(1, Double.NaN)
            if (!latitude.isFinite() || !longitude.isFinite() ||
                latitude !in -90.0..90.0 || longitude !in -180.0..180.0
            ) return null
            output[index * 2] = latitude
            output[index * 2 + 1] = longitude
        }
        return output
    }

    private fun endpointToPolylineMeters(
        latitude: Double,
        longitude: Double,
        coordinates: DoubleArray
    ): Double {
        val latitudeRadians = Math.toRadians(latitude)
        val cosLatitude = cos(latitudeRadians).coerceAtLeast(0.01)
        fun x(otherLongitude: Double): Double {
            val delta = ((otherLongitude - longitude + 540.0) % 360.0) - 180.0
            return EARTH_RADIUS_M * Math.toRadians(delta) * cosLatitude
        }
        fun y(otherLatitude: Double): Double =
            EARTH_RADIUS_M * Math.toRadians(otherLatitude - latitude)

        var best = Double.POSITIVE_INFINITY
        var index = 0
        while (index + 3 < coordinates.size) {
            val ax = x(coordinates[index + 1])
            val ay = y(coordinates[index])
            val bx = x(coordinates[index + 3])
            val by = y(coordinates[index + 2])
            val dx = bx - ax
            val dy = by - ay
            val lengthSquared = dx * dx + dy * dy
            val t = if (lengthSquared == 0.0) 0.0
                else (-(ax * dx + ay * dy) / lengthSquared).coerceIn(0.0, 1.0)
            best = min(best, hypot(ax + t * dx, ay + t * dy))
            index += 2
        }
        return best
    }
}
