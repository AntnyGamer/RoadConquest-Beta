package com.roadfog.app.map

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.roadfog.app.data.RoadRecord
import kotlin.math.*

/** Parsed off the UI thread. Native batch projection accepts latitude, longitude pairs. */
class OverlayRoads(val coordinates: DoubleArray, val starts: IntArray) {
    companion object {
        val EMPTY = OverlayRoads(doubleArrayOf(), intArrayOf(0))

        fun prepare(roads: List<RoadRecord>): OverlayRoads {
            val segments = ArrayList<Segment>(roads.size)
            val nodes = ArrayList<Node>(roads.size * 2)
            val buckets = HashMap<Cell, MutableList<Int>>(roads.size * 2)
            val lookupCell = Cell(0, 0, 0)

            fun assignNode(latitude: Double, longitude: Double, excludedNode: Int = -1): Int {
                val lat = Math.toRadians(latitude)
                val lon = Math.toRadians(longitude)
                val cosLat = cos(lat)
                val cellX = floor(EARTH_RADIUS_M * cosLat * cos(lon) / ENDPOINT_JOIN_TOLERANCE_M).toLong()
                val cellY = floor(EARTH_RADIUS_M * cosLat * sin(lon) / ENDPOINT_JOIN_TOLERANCE_M).toLong()
                val cellZ = floor(EARTH_RADIUS_M * sin(lat) / ENDPOINT_JOIN_TOLERANCE_M).toLong()
                var best = -1
                var bestDistance = Double.POSITIVE_INFINITY
                for (dx in -1L..1L) for (dy in -1L..1L) for (dz in -1L..1L) {
                    lookupCell.set(cellX + dx, cellY + dy, cellZ + dz)
                    for (candidate in buckets[lookupCell].orEmpty()) {
                        if (candidate == excludedNode) continue
                        val node = nodes[candidate]
                        val distance = metersBetween(latitude, longitude, node.latitude, node.longitude)
                        if (distance <= ENDPOINT_JOIN_TOLERANCE_M && distance < bestDistance) {
                            best = candidate
                            bestDistance = distance
                        }
                    }
                }
                if (best >= 0) return best
                val index = nodes.size
                nodes += Node(latitude, longitude)
                buckets.getOrPut(Cell(cellX, cellY, cellZ)) { ArrayList(2) } += index
                return index
            }

            fun addSegment(coordinates: DoubleArray) {
                if (coordinates.size < 4) return
                val startLatitude = coordinates[0]
                val startLongitude = coordinates[1]
                val endLatitude = coordinates[coordinates.size - 2]
                val endLongitude = coordinates[coordinates.size - 1]
                val startNode = assignNode(startLatitude, startLongitude)
                val endNode = if (
                    coordinates.size > 4 &&
                    metersBetween(startLatitude, startLongitude, endLatitude, endLongitude) <= DUPLICATE_ENDPOINT_M
                ) {
                    startNode
                } else {
                    assignNode(endLatitude, endLongitude, startNode)
                }
                segments += Segment(coordinates, startNode, endNode)
            }

            for (road in roads) {
                val points = GeoJsonUtil.validRoadCoordinates(road.geometryJson) ?: continue
                val part = DoubleBuffer(points.length() * 2 + 4)
                var previous = points.getJSONArray(0)
                part.add(previous.getDouble(1))
                part.add(previous.getDouble(0))
                for (i in 1 until points.length()) {
                    val current = points.getJSONArray(i)
                    val previousLongitude = previous.getDouble(0)
                    val currentLongitude = current.getDouble(0)
                    val currentLatitude = current.getDouble(1)
                    val delta = currentLongitude - previousLongitude
                    if (abs(delta) > 180.0) {
                        // GeoJSON itself is wrapped to [-180, 180]. Split a real date-line
                        // crossing into two short pieces so neither MapLibre nor the fog
                        // projector can interpret it as a line across the rest of the world.
                        val unwrappedCurrent = currentLongitude - 360.0 * sign(delta)
                        val boundary = if (delta < 0.0) 180.0 else -180.0
                        val fraction = (boundary - previousLongitude) /
                            (unwrappedCurrent - previousLongitude)
                        val crossingLatitude = previous.getDouble(1) +
                            (currentLatitude - previous.getDouble(1)) * fraction
                        part.add(crossingLatitude)
                        part.add(boundary)
                        addSegment(part.toArray())
                        part.clear()
                        part.add(crossingLatitude)
                        part.add(-boundary)
                    }
                    part.add(currentLatitude)
                    part.add(currentLongitude)
                    previous = current
                }
                addSegment(part.toArray())
            }
            if (segments.isEmpty()) return EMPTY

            val degree = IntArray(nodes.size)
            val firstAdjacent = IntArray(nodes.size) { -1 }
            val secondAdjacent = IntArray(nodes.size) { -1 }
            fun addAdjacent(node: Int, segment: Int) {
                if (degree[node] == 0) firstAdjacent[node] = segment
                else if (degree[node] == 1) secondAdjacent[node] = segment
                degree[node]++
            }
            for (i in segments.indices) {
                addAdjacent(segments[i].startNode, i)
                addAdjacent(segments[i].endNode, i)
            }

            val output = DoubleBuffer((segments.sumOf { it.coordinates.size }).coerceAtLeast(16))
            val starts = IntArray(segments.size + 1)
            val used = BooleanArray(segments.size)
            var chainCount = 0

            fun walk(first: Int, startNode: Int) {
                var segmentIndex = first
                var nodeIndex = startNode
                var firstSegment = true
                while (!used[segmentIndex]) {
                    val segment = segments[segmentIndex]
                    val forward = segment.startNode == nodeIndex
                    appendSegment(output, segment, forward, nodes, joinPrevious = !firstSegment)
                    firstSegment = false
                    used[segmentIndex] = true
                    nodeIndex = if (forward) segment.endNode else segment.startNode
                    if (degree[nodeIndex] != 2) break
                    val next = if (firstAdjacent[nodeIndex] != segmentIndex) {
                        firstAdjacent[nodeIndex]
                    } else {
                        secondAdjacent[nodeIndex]
                    }
                    if (next < 0 || used[next]) break
                    segmentIndex = next
                }
                starts[++chainCount] = output.size
            }

            // Stop chains at real junctions/endpoints. All branches still snap to the same
            // sub-meter node, so turns remain visually connected without inventing a shortcut.
            for (i in segments.indices) {
                if (used[i]) continue
                val segment = segments[i]
                val startDegree = degree[segment.startNode]
                val endDegree = degree[segment.endNode]
                if (startDegree != 2 || endDegree != 2) {
                    walk(i, if (startDegree != 2) segment.startNode else segment.endNode)
                }
            }
            // Closed loops have degree two everywhere and need an arbitrary starting point.
            for (i in segments.indices) {
                if (!used[i]) walk(i, segments[i].startNode)
            }
            return OverlayRoads(output.toArray(), starts.copyOf(chainCount + 1))
        }

        private fun appendSegment(
            output: DoubleBuffer,
            segment: Segment,
            forward: Boolean,
            nodes: List<Node>,
            joinPrevious: Boolean
        ) {
            val coordinates = segment.coordinates
            val pointCount = coordinates.size / 2
            val firstNode = nodes[if (forward) segment.startNode else segment.endNode]
            val lastNode = nodes[if (forward) segment.endNode else segment.startNode]
            appendPoint(output, firstNode.latitude, firstNode.longitude, joinPrevious)

            if (forward) {
                for (point in 1 until pointCount - 1) {
                    appendPoint(output, coordinates[point * 2], coordinates[point * 2 + 1], true)
                }
            } else {
                for (point in pointCount - 2 downTo 1) {
                    appendPoint(output, coordinates[point * 2], coordinates[point * 2 + 1], true)
                }
            }
            appendPoint(output, lastNode.latitude, lastNode.longitude, true)
        }

        private fun appendPoint(
            output: DoubleBuffer,
            latitude: Double,
            longitude: Double,
            deduplicatePrevious: Boolean
        ) {
            if (deduplicatePrevious && output.size >= 2 &&
                output[output.size - 2] == latitude &&
                output[output.size - 1] == longitude
            ) return
            output.add(latitude)
            output.add(longitude)
        }

        private data class Segment(
            val coordinates: DoubleArray,
            val startNode: Int,
            val endNode: Int
        )

        private class Node(val latitude: Double, val longitude: Double)

        private class Cell(var x: Long, var y: Long, var z: Long) {
            fun set(x: Long, y: Long, z: Long) {
                this.x = x
                this.y = y
                this.z = z
            }

            override fun equals(other: Any?): Boolean =
                other is Cell && x == other.x && y == other.y && z == other.z

            override fun hashCode(): Int {
                var result = x.hashCode()
                result = 31 * result + y.hashCode()
                return 31 * result + z.hashCode()
            }
        }

        private fun metersBetween(
            latitude1: Double,
            longitude1: Double,
            latitude2: Double,
            longitude2: Double
        ): Double {
            val lat1 = Math.toRadians(latitude1)
            val lat2 = Math.toRadians(latitude2)
            val x = Math.toRadians(longitude2 - longitude1) * cos((lat1 + lat2) / 2.0)
            val y = lat2 - lat1
            return EARTH_RADIUS_M * hypot(x, y)
        }

        private class DoubleBuffer(initialCapacity: Int) {
            private var values = DoubleArray(initialCapacity)
            var size = 0
                private set

            operator fun get(index: Int) = values[index]

            fun add(value: Double) {
                if (size == values.size) values = values.copyOf(values.size * 2)
                values[size++] = value
            }

            fun clear() { size = 0 }

            fun toArray() = values.copyOf(size)
        }

        private const val EARTH_RADIUS_M = 6_371_008.8
        private const val ENDPOINT_JOIN_TOLERANCE_M = 1.0
        private const val DUPLICATE_ENDPOINT_M = 0.01
    }
}

/**
 * Off-screen fog renderer. The resulting bitmap is georeferenced by MapLibre's ImageSource, so
 * panning/zooming/rotation are handled by the native renderer instead of by an Android View.
 */
object FogBitmapRenderer {
    data class Request(
        val bitmapWidth: Int,
        val bitmapHeight: Int,
        val screenLeft: Float,
        val screenTop: Float,
        val screenScale: Float,
        val roads: OverlayRoads,
        val roadScreen: DoubleArray,
        val centerLatitude: Double,
        val metersPerScreenPixelAtCenter: Double,
        val liveLatitude: Double?,
        val liveScreen: DoubleArray?,
        val textureMatrix: FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
        val exploredCoordinates: DoubleArray = doubleArrayOf(),
        val exploredScreen: DoubleArray = doubleArrayOf()
    )

    fun render(request: Request, reusable: Bitmap? = null): Bitmap {
        val bitmap = if (reusable != null && !reusable.isRecycled &&
            reusable.width == request.bitmapWidth && reusable.height == request.bitmapHeight &&
            reusable.config == Bitmap.Config.ARGB_8888
        ) {
            reusable
        } else {
            reusable?.recycle()
            Bitmap.createBitmap(request.bitmapWidth, request.bitmapHeight, Bitmap.Config.ARGB_8888)
        }
        val canvas = Canvas(bitmap)
        val scratch = renderScratch.get()
        scratch.textureMatrix.setValues(request.textureMatrix)
        scratch.cloudShader.setLocalMatrix(scratch.textureMatrix)
        canvas.drawRect(0f, 0f, request.bitmapWidth.toFloat(), request.bitmapHeight.toFloat(), scratch.cloudPaint)
        if (request.roads.coordinates.isEmpty() && request.exploredCoordinates.isEmpty() && request.liveScreen == null) return bitmap

        // Combine the strongest reveal once. Repeated DST_OUT operations multiply the
        // remaining alpha, widening the clear area as roads/visited places overlap.
        val reveal = scratch.acquireRevealBitmap(request.bitmapWidth, request.bitmapHeight)
        val revealCanvas = Canvas(reveal)
        val erasePaint = scratch.revealPaint
        val capBounds = scratch.capBounds
        val roadLinear = scratch.roadLinear
        val roadRadial = scratch.roadRadial
        val locationRadial = scratch.locationRadial

        val centerCos = cos(Math.toRadians(request.centerLatitude.coerceIn(-85.05112878, 85.05112878)))
            .coerceAtLeast(1e-6)
        for (road in 0 until request.roads.starts.size - 1) {
            for (i in request.roads.starts[road] until request.roads.starts[road + 1] - 2 step 2) {
                if (!request.roadScreen[i].isFinite() || !request.roadScreen[i + 1].isFinite() ||
                    !request.roadScreen[i + 2].isFinite() || !request.roadScreen[i + 3].isFinite()) continue
                val latitude = (request.roads.coordinates[i] + request.roads.coordinates[i + 2]) / 2.0
                val localMetersPerPixel = request.metersPerScreenPixelAtCenter *
                    cos(Math.toRadians(latitude.coerceIn(-85.05112878, 85.05112878))) / centerCos
                if (!localMetersPerPixel.isFinite() || localMetersPerPixel <= 0) continue
                val radius = (ROAD_FULL_M / localMetersPerPixel * request.screenScale).toFloat()
                drawRoadReveal(
                    revealCanvas,
                    ((request.roadScreen[i] - request.screenLeft) * request.screenScale).toFloat(),
                    ((request.roadScreen[i + 1] - request.screenTop) * request.screenScale).toFloat(),
                    ((request.roadScreen[i + 2] - request.screenLeft) * request.screenScale).toFloat(),
                    ((request.roadScreen[i + 3] - request.screenTop) * request.screenScale).toFloat(),
                    radius, erasePaint, roadLinear, roadRadial, capBounds
                )
            }
        }

        for (i in request.exploredCoordinates.indices step 2) {
            drawPlaceReveal(revealCanvas, request, request.exploredCoordinates[i], request.exploredScreen[i],
                request.exploredScreen[i + 1], centerCos, erasePaint, locationRadial)
        }
        val live = request.liveScreen
        val liveLatitude = request.liveLatitude
        if (live != null && liveLatitude != null && live.all { it.isFinite() }) {
            drawPlaceReveal(revealCanvas, request, liveLatitude, live[0], live[1], centerCos, erasePaint, locationRadial)
        }
        canvas.drawBitmap(reveal, 0f, 0f, scratch.applyRevealPaint)
        return bitmap
    }

    private fun drawPlaceReveal(canvas: Canvas, request: Request, latitude: Double, x: Double, y: Double,
                                centerCos: Double, paint: Paint, gradient: RadialGradient) {
        if (!x.isFinite() || !y.isFinite()) return
        val metersPerPixel = request.metersPerScreenPixelAtCenter *
            cos(Math.toRadians(latitude.coerceIn(-85.05112878, 85.05112878))) / centerCos
        val radius = (LOCATION_FULL_M / metersPerPixel * request.screenScale).toFloat()
        if (!radius.isFinite() || radius <= 0f) return
        val drawX = ((x - request.screenLeft) * request.screenScale).toFloat()
        val drawY = ((y - request.screenTop) * request.screenScale).toFloat()
        if (drawX + radius < 0f || drawX - radius > canvas.width ||
            drawY + radius < 0f || drawY - radius > canvas.height
        ) return
        val save = canvas.save()
        canvas.translate(drawX, drawY)
        canvas.scale(radius, radius)
        paint.shader = gradient
        canvas.drawCircle(0f, 0f, 1f, paint)
        canvas.restoreToCount(save)
    }

    private fun drawRoadReveal(
        canvas: Canvas,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        radius: Float,
        erasePaint: Paint,
        roadLinear: LinearGradient,
        roadRadial: RadialGradient,
        capBounds: RectF
    ) {
        if (!radius.isFinite() || radius <= 0) return
        if (maxOf(x1, x2) + radius < 0f || minOf(x1, x2) - radius > canvas.width ||
            maxOf(y1, y2) + radius < 0f || minOf(y1, y2) - radius > canvas.height
        ) return
        val length = hypot(x2 - x1, y2 - y1)
        val save = canvas.save()
        canvas.translate(x1, y1)
        canvas.rotate(Math.toDegrees(atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())).toFloat())
        canvas.scale(radius, radius)
        erasePaint.shader = roadLinear
        canvas.drawRect(0f, -1f, length / radius, 1f, erasePaint)
        erasePaint.shader = roadRadial
        canvas.drawArc(capBounds, 90f, 180f, true, erasePaint)
        canvas.translate(length / radius, 0f)
        canvas.drawArc(capBounds, -90f, 180f, true, erasePaint)
        canvas.restoreToCount(save)
    }

    private fun radial(innerFraction: Float) = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(Color.WHITE, Color.WHITE, Color.BLACK),
        floatArrayOf(0f, innerFraction, 1f),
        Shader.TileMode.CLAMP
    )

    const val ROAD_CLEAR_M = 50f * 0.3048f
    const val ROAD_FULL_M = 1500f * 0.3048f
    const val LOCATION_CLEAR_M = ROAD_CLEAR_M
    const val LOCATION_FULL_M = ROAD_FULL_M
    const val MAX_FOG_ALPHA = 0.80f
    const val MIN_ROAD_ZOOM = 9.0
    const val MAX_ZOOM = 22.0
    const val CENTER_ZOOM = 18.0
    const val MAX_BITMAP_DIMENSION = 768
    const val VIEWPORT_PADDING_MULTIPLIER = 1.5f

    fun bitmapDimensionForZoom(zoom: Double): Int = when {
        zoom < MIN_ROAD_ZOOM -> 512
        zoom < 12.0 -> 640
        else -> MAX_BITMAP_DIMENSION
    }

    private val cloudTexture by lazy { FogTexture.create() }

    private class RenderScratch {
        val textureMatrix = Matrix()
        val cloudShader = BitmapShader(cloudTexture, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        val cloudPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            shader = cloudShader
            alpha = (MAX_FOG_ALPHA * 255).roundToInt()
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
        }
        val revealPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.LIGHTEN)
        }
        val applyRevealPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            colorFilter = ColorMatrixColorFilter(floatArrayOf(
                0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f,
                1f, 0f, 0f, 0f, 0f
            ))
        }
        val capBounds = RectF(-1f, -1f, 1f, 1f)
        val roadLinear = LinearGradient(
            0f, -1f, 0f, 1f,
            intArrayOf(Color.BLACK, Color.WHITE, Color.WHITE, Color.BLACK),
            floatArrayOf(0f, (1f - ROAD_CLEAR_M / ROAD_FULL_M) / 2f,
                (1f + ROAD_CLEAR_M / ROAD_FULL_M) / 2f, 1f),
            Shader.TileMode.CLAMP
        )
        val roadRadial = radial(ROAD_CLEAR_M / ROAD_FULL_M)
        val locationRadial = radial(LOCATION_CLEAR_M / LOCATION_FULL_M)
        private var revealBitmap: Bitmap? = null

        fun acquireRevealBitmap(width: Int, height: Int): Bitmap {
            val existing = revealBitmap
            if (existing != null && !existing.isRecycled && existing.width == width && existing.height == height) {
                existing.eraseColor(Color.BLACK)
                return existing
            }
            existing?.recycle()
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                it.eraseColor(Color.BLACK)
                revealBitmap = it
            }
        }
    }

    private val renderScratch = ThreadLocal.withInitial { RenderScratch() }
}
