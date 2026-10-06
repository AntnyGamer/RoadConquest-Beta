package com.roadconquest.app.matching

import com.roadconquest.app.data.MatchedRoad
import com.roadconquest.app.data.TrackPoint
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sqrt

data class MatchResult(
    val roads: List<MatchedRoad>,
    val matchedPointConfidences: Map<Long, Double>
)

class OsrmMatcher(
    private val baseUrl: String = "https://router.project-osrm.org"
) {
    fun match(points: List<TrackPoint>): MatchResult? {
        if (points.size !in 2..MAX_MATCH_POINTS) return null
        val coordinates = points.joinToString(";") { "${it.longitude},${it.latitude}" }
        var previousSecond = Long.MIN_VALUE
        val timestamps = points.joinToString(";") { point ->
            val actualSecond = point.timestampMillis / 1000L
            val normalizedSecond = if (previousSecond == Long.MIN_VALUE) {
                actualSecond
            } else {
                maxOf(actualSecond, previousSecond + 1L)
            }
            previousSecond = normalizedSecond
            normalizedSecond.toString()
        }
        val radiuses = points.joinToString(";") {
            // Very small reported GNSS accuracy is common on modern phones, but the fix can
            // still cut across a corner by several meters while the map road centerline is
            // offset the other way. A 5 m OSRM search radius was leaving otherwise excellent
            // turn samples unmatched. Give every fix a modest 10 m search envelope; parseLeg()
            // still applies the stricter recorded-accuracy distance check before any geometry
            // can become permanent road credit, so this improves candidate discovery without
            // blindly accepting a nearby parallel road.
            it.accuracyMeters.coerceIn(MIN_MATCH_RADIUS_M, MAX_MATCH_RADIUS_M).toInt().toString()
        }
        // Keep a leg per surviving fix so an ambiguous batch tail can be withheld without
        // persisting its guessed junction spur. Named-road counts do not count these legs.
        val waypoints = points.indices.joinToString(";")
        // The driving filter already removes bad fixes. Preserve dense accepted samples;
        // server-side tidy can turn otherwise usable samples into unmatched tracepoints.
        val url = URI(
            "$baseUrl/match/v1/driving/$coordinates" +
                "?steps=true&geometries=geojson&overview=full&annotations=false&tidy=false&gaps=ignore" +
                "&waypoints=$waypoints&timestamps=$timestamps&radiuses=$radiuses"
        ).toURL()

        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "RoadConquest/1.0 (Android road-history app)")
        }

        return try {
            if (connection.responseCode !in 200..299) return null
            val body = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    if (output.size() + count > MAX_RESPONSE_BYTES) return null
                    output.write(buffer, 0, count)
                }
                output.toString(StandardCharsets.UTF_8.name())
            }
            parse(body, points)
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    internal fun parse(json: String, points: List<TrackPoint>): MatchResult? {
        val root = JSONObject(json)
        if (root.optString("code") != "Ok") return null
        val matchings = root.optJSONArray("matchings") ?: return null
        if (matchings.length() == 0) return null

        val confidences = DoubleArray(matchings.length()) { index ->
            matchings.getJSONObject(index).optDouble("confidence", 0.0)
                .takeIf { it.isFinite() && it in 0.0..1.0 } ?: 0.0
        }
        val tracepoints = root.optJSONArray("tracepoints") ?: return null
        if (tracepoints.length() != points.size) return null
        val traces = Array(matchings.length()) { mutableListOf<Trace>() }
        for (i in points.indices) {
            val tracepoint = tracepoints.optJSONObject(i) ?: continue
            val matchingIndex = tracepoint.optInt("matchings_index", -1)
            val waypoint = tracepoint.optInt("waypoint_index", -1)
            val alternatives = tracepoint.optInt("alternatives_count", -1)
            val location = tracepoint.optJSONArray("location") ?: return null
            if (matchingIndex !in traces.indices || waypoint < 0 || alternatives < 0 ||
                !validCoordinate(location)
            ) return null
            traces[matchingIndex] += Trace(i, waypoint, alternatives, location)
        }

        val roads = mutableListOf<MatchedRoad>()
        val resolved = mutableMapOf<Long, Double>()
        for (m in traces.indices) {
            val trace = traces[m]
            if (trace.size < 2) continue
            val legs = matchings.getJSONObject(m).optJSONArray("legs") ?: return null
            // All input fixes are requested as waypoints. Reject shifted, duplicated or missing
            // leg indices rather than assigning another fix's geometry and completion state.
            if (legs.length() != trace.size - 1 || trace.indices.any { trace[it].waypoint != it }) return null
            // Alternative candidates at an incremental trace endpoint still need more evidence.
            // Inside a confident trace, fixes on both sides already disambiguate the route;
            // discarding those legs creates holes at ordinary turns and junctions.
            fun reliable(index: Int): Boolean = trace[index].alternatives == 0 ||
                (index > 0 && index < trace.lastIndex &&
                    confidences[m] >= MIN_ACCEPTABLE_CONFIDENCE)
            var first = 0
            while (first < trace.size) {
                while (first < trace.size && !reliable(first)) first++
                if (first >= trace.size) break
                var last = first
                while (last + 1 < trace.size && reliable(last + 1)) last++
                if (last > first) {
                    val matchingRoads = mutableListOf<MatchedRoad>()
                    for (l in first until last) {
                        for (road in parseLeg(legs.getJSONObject(l), trace[l], trace[l + 1], points, confidences[m])) {
                            appendRoad(matchingRoads, road)
                        }
                    }
                    if (matchingRoads.isNotEmpty()) {
                        roads += matchingRoads
                        for (t in first..last) {
                            val input = trace[t].input
                            // The first fix after any withheld interval stays pending so a later
                            // retry can resolve the missing incoming segment with better context.
                            if (t == first && input != 0) continue
                            resolved[points[input].id] = confidences[m]
                        }
                    }
                }
                first = last + 1
            }
        }
        // A valid but ambiguous response is a partial match, not a network failure. It should
        // retry soon with more context and must not save provisional blue lines or road credit.
        return MatchResult(roads, resolved)
    }

    private data class Trace(val input: Int, val waypoint: Int, val alternatives: Int, val location: JSONArray)

    private fun parseLeg(
        leg: JSONObject, start: Trace, end: Trace, points: List<TrackPoint>, confidence: Double
    ): List<MatchedRoad> {
        val steps = leg.optJSONArray("steps") ?: error("Missing matched leg steps")
        val parsed = ArrayList<StepGeometry>(steps.length())
        var previous = start.location
        for (s in 0 until steps.length()) {
            val step = steps.getJSONObject(s)
            val distance = step.optDouble("distance", Double.NaN)
            require(distance.isFinite() && distance >= 0.0) { "Invalid step distance" }
            val geometry = step.optJSONObject("geometry")
            val coordinates = geometry?.optJSONArray("coordinates")
            if (distance == 0.0 && (coordinates == null || coordinates.length() < 2)) continue
            require(geometry?.optString("type") == "LineString" && coordinates != null &&
                coordinates.length() >= 2 && (0 until coordinates.length()).all {
                    coordinates.optJSONArray(it)?.let(::validCoordinate) == true
                }
            ) { "Incomplete matched leg geometry" }
            require(coordinateDistanceMeters(previous, coordinates.getJSONArray(0)) <= JOIN_TOLERANCE_M) {
                "Disconnected matched steps"
            }
            val connected = connectStart(previous, coordinates)
            val geometryMeters = lineLengthMeters(connected)
            if (geometryMeters >= MIN_GEOMETRY_LENGTH_M) {
                parsed += StepGeometry(
                    step.optString("name").ifBlank { step.optString("ref").ifBlank { "Unnamed road" } },
                    connected,
                    geometryMeters
                )
            }
            previous = connected.getJSONArray(connected.length() - 1)
        }
        require(coordinateDistanceMeters(previous, end.location) <= JOIN_TOLERANCE_M) {
            "Matched geometry misses its endpoint"
        }
        if (parsed.isNotEmpty()) {
            val lastIndex = parsed.lastIndex
            val last = parsed[lastIndex]
            val connected = connectEnd(last.coordinates, end.location)
            parsed[lastIndex] = last.copy(coordinates = connected, meters = lineLengthMeters(connected))
        }

        val total = parsed.sumOf { it.meters }
        val from = points[start.input].timestampMillis
        val duration = (points[end.input].timestampMillis - from).coerceAtLeast(0L)
        val rawStart = JSONArray().put(points[start.input].longitude).put(points[start.input].latitude)
        val rawEnd = JSONArray().put(points[end.input].longitude).put(points[end.input].latitude)
        val uncertainty = points[start.input].accuracyMeters + points[end.input].accuracyMeters
        require(coordinateDistanceMeters(rawStart, start.location) <= points[start.input].accuracyMeters * 2 + 10 &&
            coordinateDistanceMeters(rawEnd, end.location) <= points[end.input].accuracyMeters * 2 + 10) {
            "Matched road is too far from the recorded drive"
        }
        require(total <= coordinateDistanceMeters(rawStart, rawEnd) * 3 + uncertainty * 2 + 30 &&
            (duration == 0L || total <= duration / 1000.0 * 100 + uncertainty)) {
            "Matched route contains an unsupported detour"
        }
        var before = 0.0
        return buildList(parsed.size) {
            for (step in parsed) {
                val firstTime = from + (duration * if (total > 0.0) before / total else 0.0).roundToLong()
                before += step.meters
                val lastTime = from + (duration * if (total > 0.0) before / total else 0.0).roundToLong()
                add(MatchedRoad(
                    name = step.name,
                    coordinatesJson = step.coordinates.toString(),
                    firstTimestamp = firstTime,
                    lastTimestamp = lastTime,
                    confidence = confidence
                ))
            }
        }
    }

    private fun appendRoad(roads: MutableList<MatchedRoad>, road: MatchedRoad) {
        val previous = roads.lastOrNull()
        if (previous == null || !sameNamedRoad(previous.name, road.name)) {
            roads += road
            return
        }
        val previousCoordinates = JSONArray(previous.coordinatesJson)
        val coordinates = JSONArray(road.coordinatesJson)
        if (coordinateDistanceMeters(
                previousCoordinates.getJSONArray(previousCoordinates.length() - 1),
                coordinates.getJSONArray(0)
            ) > JOIN_TOLERANCE_M
        ) {
            roads += road
            return
        }

        val merged = JSONArray()
        for (i in 0 until previousCoordinates.length()) merged.put(previousCoordinates.getJSONArray(i))
        val previousEnd = previousCoordinates.getJSONArray(previousCoordinates.length() - 1)
        val currentStart = coordinates.getJSONArray(0)
        val firstToCopy = if (coordinateDistanceMeters(previousEnd, currentStart) <= DUPLICATE_POINT_TOLERANCE_M) 1 else 0
        for (i in firstToCopy until coordinates.length()) merged.put(coordinates.getJSONArray(i))
        roads[roads.lastIndex] = MatchedRoad(
            name = previous.name,
            coordinatesJson = merged.toString(),
            firstTimestamp = previous.firstTimestamp,
            lastTimestamp = road.lastTimestamp,
            confidence = minOf(previous.confidence, road.confidence)
        )
    }

    private fun sameNamedRoad(a: String, b: String): Boolean {
        val first = a.trim()
        val second = b.trim()
        return first.isNotEmpty() && !first.equals("Unnamed road", ignoreCase = true) &&
            first.equals(second, ignoreCase = true)
    }

    private data class StepGeometry(val name: String, val coordinates: JSONArray, val meters: Double)

    private fun connectStart(start: JSONArray, coordinates: JSONArray): JSONArray {
        val connected = JSONArray().put(copyCoordinate(start))
        val first = coordinates.getJSONArray(0)
        val from = if (coordinateDistanceMeters(start, first) <= DUPLICATE_POINT_TOLERANCE_M) 1 else 0
        for (i in from until coordinates.length()) connected.put(copyCoordinate(coordinates.getJSONArray(i)))
        return connected
    }

    private fun connectEnd(coordinates: JSONArray, end: JSONArray): JSONArray {
        val connected = JSONArray()
        val lastIndex = coordinates.length() - 1
        for (i in 0 until lastIndex) connected.put(copyCoordinate(coordinates.getJSONArray(i)))
        val last = coordinates.getJSONArray(lastIndex)
        if (coordinateDistanceMeters(last, end) > DUPLICATE_POINT_TOLERANCE_M) {
            connected.put(copyCoordinate(last))
        }
        connected.put(copyCoordinate(end))
        return connected
    }

    private fun lineLengthMeters(coordinates: JSONArray): Double {
        var meters = 0.0
        for (i in 0 until coordinates.length() - 1) {
            meters += coordinateDistanceMeters(coordinates.getJSONArray(i), coordinates.getJSONArray(i + 1))
        }
        return meters
    }

    private fun copyCoordinate(pair: JSONArray) =
        JSONArray().put(pair.getDouble(0)).put(pair.getDouble(1))

    private fun validCoordinate(pair: JSONArray): Boolean = pair.length() >= 2 &&
        pair.optDouble(0, Double.NaN) in -180.0..180.0 && pair.optDouble(1, Double.NaN) in -90.0..90.0

    private fun coordinateDistanceMeters(a: JSONArray, b: JSONArray): Double {
        val lon1 = a.optDouble(0, 0.0)
        val lat1 = Math.toRadians(a.optDouble(1, 0.0))
        val lon2 = b.optDouble(0, 0.0)
        val lat2 = Math.toRadians(b.optDouble(1, 0.0))
        val deltaLongitude = Math.toRadians(((lon2 - lon1 + 540.0) % 360.0) - 180.0)
        val x = deltaLongitude * cos((lat1 + lat2) / 2.0)
        val y = lat2 - lat1
        return EARTH_RADIUS_M * sqrt(x * x + y * y)
    }

    companion object {
        // The public OSRM demo rejects larger traces with TooBig (verified 2026-10-03).
        const val MAX_MATCH_POINTS = 10
        // A point with alternatives is only accepted when it is internal to a trace, so fixes
        // on both sides constrain the route. Keep this identical to TrackingService's road
        // acceptance threshold; otherwise a 0.45-0.79 contextual match can retry forever.
        internal const val MIN_ACCEPTABLE_CONFIDENCE = 0.45
        private const val MIN_MATCH_RADIUS_M = 10f
        private const val MAX_MATCH_RADIUS_M = 75f
        private const val EARTH_RADIUS_M = 6_371_008.8
        private const val MIN_GEOMETRY_LENGTH_M = 0.001
        private const val DUPLICATE_POINT_TOLERANCE_M = 0.01
        private const val JOIN_TOLERANCE_M = 1.0
        private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
    }
}
