package com.roadconquest.app.map

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * World-wide, deterministic, approximately one-square-mile exploration tiles.
 *
 * Each latitude band is one statute mile high. Columns are sized at that band's
 * center latitude, giving cells approximately one mile wide on the ground rather
 * than only at the equator (as a fixed Web-Mercator grid would).
 *
 * Old 50 m exploration samples remain usable: this is a rendering projection,
 * not a change to road matching, scoring, or the history database.
 */
internal object FogGrid {
    private const val MILE_METERS = 1609.344
    private const val METERS_PER_LATITUDE_DEGREE = 111_195.0
    private const val LATITUDE_STEP = MILE_METERS / METERS_PER_LATITUDE_DEGREE
    private const val MAX_MERCATOR_LATITUDE = 85.05112878

    data class Cell(val row: Int, val column: Int) {
        val key: Long get() = (row.toLong() shl 32) or (column.toLong() and 0xffff_ffffL)
    }

    fun cell(latitude: Double, longitude: Double): Cell? {
        if (!latitude.isFinite() || !longitude.isFinite() ||
            latitude !in -90.0..90.0 || longitude !in -180.0..180.0
        ) return null
        val row = floor((latitude.coerceIn(-MAX_MERCATOR_LATITUDE, MAX_MERCATOR_LATITUDE) + 90.0) / LATITUDE_STEP).toInt()
        val columns = columnCount(row)
        val wrapped = ((longitude + 180.0) % 360.0 + 360.0) % 360.0
        return Cell(row, floor(wrapped / (360.0 / columns)).toInt().coerceIn(0, columns - 1))
    }

    /** Four corners in clockwise map-projection order: NW, NE, SE, SW (lat, lon). */
    fun corners(latitude: Double, longitude: Double): DoubleArray {
        val tile = cell(latitude, longitude) ?: return doubleArrayOf()
        val coordinates = DoubleArray(8)
        writeCorners(tile, coordinates, 0)
        return coordinates
    }

    fun visitedCorners(samples: DoubleArray): DoubleArray {
        val unique = HashSet<Long>()
        val cells = ArrayList<Cell>()
        for (i in 0 until samples.size - 1 step 2) {
            val tile = cell(samples[i], samples[i + 1]) ?: continue
            if (unique.add(tile.key)) cells.add(tile)
        }
        return DoubleArray(cells.size * 8).also { output ->
            cells.forEachIndexed { index, tile -> writeCorners(tile, output, index * 8) }
        }
    }

    private fun columnCount(row: Int): Int {
        val latitude = (-90.0 + (row + 0.5) * LATITUDE_STEP)
            .coerceIn(-MAX_MERCATOR_LATITUDE, MAX_MERCATOR_LATITUDE)
        val latitudeCos = cos(Math.toRadians(latitude))
        val widthDegrees = MILE_METERS / (METERS_PER_LATITUDE_DEGREE * latitudeCos)
        return (360.0 / widthDegrees).roundToInt().coerceAtLeast(1)
    }

    private fun writeCorners(cell: Cell, coordinates: DoubleArray, i: Int) {
        val columns = columnCount(cell.row)
        val width = 360.0 / columns
        val north = (-90.0 + (cell.row + 1) * LATITUDE_STEP).coerceAtMost(MAX_MERCATOR_LATITUDE)
        val south = (-90.0 + cell.row * LATITUDE_STEP).coerceAtLeast(-MAX_MERCATOR_LATITUDE)
        val west = -180.0 + cell.column * width
        val east = -180.0 + (cell.column + 1) * width
        coordinates[i] = north
        coordinates[i + 1] = west
        coordinates[i + 2] = north
        coordinates[i + 3] = east
        coordinates[i + 4] = south
        coordinates[i + 5] = east
        coordinates[i + 6] = south
        coordinates[i + 7] = west
    }
}
