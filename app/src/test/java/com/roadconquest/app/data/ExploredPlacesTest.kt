package com.roadconquest.app.data

import android.location.Location
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class ExploredPlacesTest {
    private lateinit var repository: TrackingRepository

    @Before fun setup() {
        repository = TrackingRepository(RuntimeEnvironment.getApplication())
        for (table in listOf("explored_places", "track_points", "roads")) {
            repository.readableDatabase().execSQL("DELETE FROM $table")
        }
    }

    private fun fix(latitude: Double = 40.0, longitude: Double = -74.0) = Location("gps").apply {
        this.latitude = latitude; this.longitude = longitude; accuracy = 5f; speed = 1f
    }

    @Test fun walkingPlacesPersistAndDeduplicateWithoutDrivingCredit() {
        assertTrue(repository.recordExploredPlace(fix()))
        assertFalse(repository.recordExploredPlace(fix()))
        assertTrue(repository.recordExploredPlace(fix(40.001)))
        val reopened = TrackingRepository(RuntimeEnvironment.getApplication())
        val places = reopened.getExploredPlacesInBounds(41.0, -73.0, 39.0, -75.0)
        assertEquals(4, places.size)
        assertEquals(0L, reopened.getSummary().trackPointCount)
        assertEquals(0L, reopened.getSummary().roadSegmentCount)
        assertEquals(0.0, reopened.getSummary().distanceMeters, 0.0)
        assertTrue(reopened.loadMatchingWindow().points.isEmpty())
        assertTrue(reopened.getExploredPlacesInBounds(20.0, 20.0, 10.0, 10.0).isEmpty())
    }

    @Test fun inaccurateMockAndInvalidFixesCannotPermanentlyClearFog() {
        assertFalse(repository.recordExploredPlace(fix().apply { accuracy = 26f }))
        assertFalse(repository.recordExploredPlace(fix().apply { removeAccuracy() }))
        assertFalse(repository.recordExploredPlace(fix().apply { isMock = true }))
        assertFalse(repository.recordExploredPlace(fix(longitude = 181.0)))
        assertTrue(repository.getExploredPlacesInBounds(90.0, 180.0, -90.0, -180.0).isEmpty())
    }

    @Test fun wrappedBoundsReturnBothSidesOfTheDateLineOnly() {
        repository.recordExploredPlace(fix(0.0, 179.999))
        repository.recordExploredPlace(fix(0.0, -179.999))
        repository.recordExploredPlace(fix(0.0, 0.0))
        assertEquals(4, repository.getExploredPlacesInBounds(1.0, -179.0, -1.0, 179.0).size)
    }
}
