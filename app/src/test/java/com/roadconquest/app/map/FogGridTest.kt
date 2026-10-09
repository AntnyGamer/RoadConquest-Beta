package com.roadconquest.app.map

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos

class FogGridTest {
    @Test fun gridCellsAreAboutOneMileHighAndWideAtTheirLatitude() {
        for (latitude in listOf(-65.0, 0.0, 39.0, 65.0, 84.0)) {
            val corners = FogGrid.corners(latitude, -74.0)
            assertEquals(8, corners.size)
            val north = corners[0]
            val south = corners[4]
            val west = corners[1]
            val east = corners[3]
            val heightMeters = (north - south) * 111_195.0
            val widthMeters = (east - west) * 111_195.0 * cos(Math.toRadians((north + south) / 2))
            assertEquals(1609.344, heightMeters, 1.0)
            assertEquals(1609.344, widthMeters, 10.0)
            assertTrue(north > south && east > west)
        }
    }

    @Test fun repeatSamplesInOneTileOnlyRevealThatTile() {
        val location = FogGrid.corners(39.95, -75.0)
        val north = location[0]
        val west = location[1]
        val south = location[4]
        val east = location[3]
        val lat = (north + south) / 2
        val lon = (west + east) / 2
        assertEquals(FogGrid.cell(lat, lon), FogGrid.cell(39.95, -75.0))
        val unique = FogGrid.visitedCorners(doubleArrayOf(lat, lon, lat, lon, 39.95, -75.0))
        assertArrayEquals(location, unique, 0.0)
        val next = FogGrid.visitedCorners(doubleArrayOf(lat, lon, lat, east + 0.0001))
        assertEquals(16, next.size)
    }

    @Test fun antimeridianTilesProjectIntoTheNearestWorldCopy() {
        val westOfDateLine = FogGrid.corners(0.0, -179.999)
        val nearEastSide = FogGrid.nearLongitude(westOfDateLine, 179.9)
        assertTrue(nearEastSide[1] >= 179.9)
        assertTrue(nearEastSide[3] >= 179.9)
        assertEquals(westOfDateLine[0], nearEastSide[0], 0.0)
        assertEquals(westOfDateLine[1] + 360.0, nearEastSide[1], 1e-9)
        val unchanged = FogGrid.nearLongitude(FogGrid.corners(39.9, -75.0), -75.0)
        assertArrayEquals(FogGrid.corners(39.9, -75.0), unchanged, 0.0)
    }

    @Test fun worldWrapAndInvalidCoordinatesNeverMakeInventedCells() {
        assertEquals(FogGrid.cell(0.0, -180.0), FogGrid.cell(0.0, 180.0))
        assertNull(FogGrid.cell(Double.NaN, 0.0))
        assertNull(FogGrid.cell(39.0, Double.POSITIVE_INFINITY))
        assertNull(FogGrid.cell(92.0, -75.0))
        assertTrue(FogGrid.visitedCorners(doubleArrayOf(Double.NaN, 0.0, 90.1, 20.0)).isEmpty())
    }
}
