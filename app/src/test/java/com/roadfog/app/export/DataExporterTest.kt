package com.roadfog.app.export

import android.location.Location
import com.roadfog.app.data.MatchedRoad
import com.roadfog.app.data.ProgressionRepository
import com.roadfog.app.data.TrackingRepository
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class DataExporterTest {
    @Test fun exportsReadableZipWithAccurateCountsAndSafeRoadNames() {
        val context = RuntimeEnvironment.getApplication()
        val repository = TrackingRepository(context)
        ProgressionRepository(context).clearProgression()
        repository.readableDatabase().execSQL("DELETE FROM track_points")
        repository.readableDatabase().execSQL("DELETE FROM roads")
        repository.readableDatabase().execSQL("DELETE FROM explored_places")
        repository.recordExploredPlace(Location("gps").apply {
            latitude = 40.01; longitude = -74.0; accuracy = 5f
        })
        repository.insertLocation(Location("gps").apply {
            latitude = 40.0; longitude = -74.0; accuracy = 5f; time = 1_000_000
        })
        repository.upsertRoads(listOf(MatchedRoad("=1+1", "[[-74,40],[-74.001,40]]", 100, 200, 1.0)))
        val output = ByteArrayOutputStream()
        DataExporter.writeZip(context, repository, output)
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertEquals(setOf(
            "metadata.json", "track_points.csv", "roads.csv", "road_visits.csv", "explored_places.csv",
            "visited_places.csv", "place_candidates.csv", "progression_rewards.csv",
            "progression_purchases.csv", "progression_counters.csv"
        ), entries.keys)
        val metadata = JSONObject(entries.getValue("metadata.json"))
        assertEquals(1L, metadata.getLong("track_point_count"))
        assertEquals(1L, metadata.getLong("road_segment_count"))
        assertEquals(7, metadata.getInt("schema_version"))
        assertEquals(1L, metadata.getLong("explored_place_count"))
        assertEquals(1L, metadata.getLong("road_visit_count"))
        assertEquals(0L, metadata.getLong("points_balance"))
        assertEquals(0L, metadata.getLong("towns_visited"))
        assertEquals(1, entries.getValue("visited_places.csv").lineSequence().filter { it.isNotEmpty() }.count())
        assertEquals(2, entries.getValue("explored_places.csv").lineSequence().filter { it.isNotEmpty() }.count())
        assertEquals(1L, metadata.getLong("roads_unlocked_count"))
        assertTrue(entries.getValue("track_points.csv").lineSequence().first().endsWith(",distance_m"))
        assertEquals(2, entries.getValue("track_points.csv").lineSequence().filter { it.isNotEmpty() }.count())
        assertTrue(entries.getValue("roads.csv").contains(",'=1+1,"))
        assertTrue(entries.getValue("roads.csv").lineSequence().first().contains("road_group_id"))
        assertTrue(entries.getValue("roads.csv").lineSequence().first().contains("times_driven"))
        assertEquals(2, entries.getValue("road_visits.csv").lineSequence().filter { it.isNotEmpty() }.count())
    }

    @Config(sdk = [31, 35, 37], manifest = Config.NONE)
    @Test fun exportSnapshotStaysConsistentAndReleasesDatabaseBeforeSlowOutput() {
        val context = RuntimeEnvironment.getApplication()
        val repository = TrackingRepository(context)
        repository.readableDatabase().execSQL("DELETE FROM track_points")
        repository.readableDatabase().execSQL("DELETE FROM roads")
        repository.insertLocation(Location("gps").apply {
            latitude = 40.0; longitude = -74.0; accuracy = 5f; time = 1_000_000
        })
        val writer = Executors.newSingleThreadExecutor()
        val bytes = ByteArrayOutputStream()
        var inserted = false
        val output = object : OutputStream() {
            override fun write(value: Int) { onFirstWrite(); bytes.write(value) }
            override fun write(data: ByteArray, offset: Int, length: Int) {
                onFirstWrite(); bytes.write(data, offset, length)
            }
            private fun onFirstWrite() {
                if (inserted) return
                inserted = true
                // A different repository shares the production database pool. This would
                // time out on Android 12 if ZIP output still held a write transaction.
                writer.submit {
                    TrackingRepository(context).apply {
                        insertLocation(Location("gps").apply {
                            latitude = 40.001; longitude = -74.0; accuracy = 5f; time = 1_010_000
                        })
                        upsertRoads(listOf(MatchedRoad("New road", "[[-74,40],[-74,40.001]]", 100, 200, 1.0)))
                    }
                }.get(5, TimeUnit.SECONDS)
            }
        }
        try { DataExporter.writeZip(context, repository, output) } finally { writer.shutdownNow() }
        assertTrue(inserted)
        val entries = readEntries(bytes.toByteArray())
        val metadata = JSONObject(entries.getValue("metadata.json"))
        assertEquals(1L, metadata.getLong("track_point_count"))
        assertEquals(0L, metadata.getLong("road_segment_count"))
        assertEquals(0.0, metadata.getDouble("distance_meters"), 0.0)
        assertEquals(2, entries.getValue("track_points.csv").lineSequence().filter { it.isNotEmpty() }.count())
        assertEquals(1, entries.getValue("roads.csv").lineSequence().filter { it.isNotEmpty() }.count())
        assertEquals(2L, repository.getSummary().trackPointCount)
        assertEquals(1L, repository.getSummary().roadsUnlockedCount)
        assertTrue(repository.getSummary().distanceMeters > 100.0)
        assertTrue(context.cacheDir.listFiles()!!.none { it.name.startsWith("roadconquest-export-") })
    }

    @Test fun failedExportCleansUpSnapshotAndPreservesTrackingData() {
        val context = RuntimeEnvironment.getApplication()
        val repository = TrackingRepository(context)
        val summary = repository.getSummary()
        val output = object : OutputStream() {
            override fun write(value: Int) { throw IOException("simulated destination failure") }
            override fun write(data: ByteArray, offset: Int, length: Int) { throw IOException("simulated destination failure") }
        }
        assertThrows(IOException::class.java) { DataExporter.writeZip(context, repository, output) }
        assertEquals(summary, repository.getSummary())
        assertTrue(context.cacheDir.listFiles()!!.none { it.name.startsWith("roadconquest-export-") })
    }

    private fun readEntries(bytes: ByteArray): Map<String, String> {
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        return entries
    }
}
