package com.roadfog.app.export

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.roadfog.app.data.DataSummary
import com.roadfog.app.data.TrackingRepository
import com.roadfog.app.util.Prefs
import org.json.JSONObject
import java.io.OutputStream
import java.io.File
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DataExporter {
    fun writeZip(context: Context, repository: TrackingRepository, output: OutputStream) {
        val snapshotFile = File.createTempFile("roadconquest-export-", ".db", context.cacheDir)
        try {
            SQLiteDatabase.openOrCreateDatabase(snapshotFile, null).use { snapshot ->
                repository.copyExportSnapshot(snapshot)
                val summary = TrackingRepository.summaryOf(snapshot)
                ZipOutputStream(output.buffered()).use { zip ->
                    val points = writeTrackPoints(snapshot, zip)
                    val roadCount = writeRoads(snapshot, zip)
                    val roadVisitCount = writeRoadVisits(snapshot, zip)
                    val exploredCount = writeExploredPlaces(snapshot, zip)
                    writeMetadata(context, summary.copy(trackPointCount = points.trackPointCount,
                        roadSegmentCount = roadCount, firstTrackAt = points.firstTrackAt, lastTrackAt = points.lastTrackAt),
                        exploredCount, roadVisitCount, zip)
                }
            }
        } finally {
            SQLiteDatabase.deleteDatabase(snapshotFile)
        }
    }

    private fun writeMetadata(
        context: Context,
        summary: DataSummary,
        exploredCount: Long,
        roadVisitCount: Long,
        zip: ZipOutputStream
    ) {
        val metadata = JSONObject()
            .put("app", "RoadConquest")
            .put("schema_version", 6)
            .put("explored_place_count", exploredCount)
            .put("road_visit_count", roadVisitCount)
            .put("exported_at", Instant.now().toString())
            .put("manual_only", Prefs.isManualOnly(context))
            .put("map_mode", Prefs.mapMode(context).name)
            .put("ui_theme", Prefs.uiTheme(context).name)
            .put("fog_enabled", Prefs.isFogEnabled(context))
            .put("distance_meters", summary.distanceMeters)
            .put("roads_unlocked_count", summary.roadsUnlockedCount)
            .put("track_point_count", summary.trackPointCount)
            .put("road_segment_count", summary.roadSegmentCount)
            .put("first_track_at", summary.firstTrackAt?.let { Instant.ofEpochMilli(it).toString() } ?: JSONObject.NULL)
            .put("last_track_at", summary.lastTrackAt?.let { Instant.ofEpochMilli(it).toString() } ?: JSONObject.NULL)

        zip.putNextEntry(ZipEntry("metadata.json"))
        zip.write(metadata.toString(2).toByteArray())
        zip.closeEntry()
    }

    private fun writeTrackPoints(database: SQLiteDatabase, zip: ZipOutputStream): DataSummary {
        var count = 0L
        var firstTime: Long? = null
        var lastTime: Long? = null
        zip.putNextEntry(ZipEntry("track_points.csv"))
        zip.writer(Charsets.UTF_8).let { writer ->
            writer.write("id,latitude,longitude,accuracy_m,speed_mps,bearing_deg,timestamp_utc,matched,next_match_attempt_utc,distance_m\n")
            database.query(
                "track_points",
                arrayOf("id", "latitude", "longitude", "accuracy_m", "speed_mps", "bearing_deg", "timestamp_ms", "matched", "next_match_attempt_ms", "distance_m"),
                null, null, null, null,
                "timestamp_ms ASC"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val timestamp = cursor.getLong(6)
                    if (firstTime == null) firstTime = timestamp
                    lastTime = timestamp
                    count++
                    writer.write(cursor.getLong(0).toString())
                    writer.write(','.code)
                    writer.write(cursor.getDouble(1).toString())
                    writer.write(','.code)
                    writer.write(cursor.getDouble(2).toString())
                    writer.write(','.code)
                    writer.write(cursor.getFloat(3).toString())
                    writer.write(','.code)
                    writer.write(cursor.getFloat(4).toString())
                    writer.write(','.code)
                    writer.write(cursor.getFloat(5).toString())
                    writer.write(','.code)
                    writer.write(Instant.ofEpochMilli(cursor.getLong(6)).toString())
                    writer.write(','.code)
                    writer.write(if (cursor.getInt(7) != 0) "true" else "false")
                    writer.write(','.code)
                    val retryAt = cursor.getLong(8)
                    if (retryAt > 0L) writer.write(Instant.ofEpochMilli(retryAt).toString())
                    writer.write(','.code)
                    writer.write(cursor.getDouble(9).toString())
                    writer.write("\n")
                }
            }
            writer.flush()
        }
        zip.closeEntry()
        return DataSummary(count, 0, firstTime, lastTime)
    }

    private fun writeExploredPlaces(database: SQLiteDatabase, zip: ZipOutputStream): Long {
        var count = 0L
        zip.putNextEntry(ZipEntry("explored_places.csv"))
        zip.writer(Charsets.UTF_8).let { writer ->
            writer.write("latitude,longitude\n")
            database.query("explored_places", arrayOf("latitude", "longitude"),
                null, null, null, null, "cell_x,cell_y").use { cursor ->
                while (cursor.moveToNext()) {
                    writer.write("${cursor.getDouble(0)},${cursor.getDouble(1)}\n")
                    count++
                }
            }
            writer.flush()
        }
        zip.closeEntry()
        return count
    }

    private fun writeRoadVisits(database: SQLiteDatabase, zip: ZipOutputStream): Long {
        var count = 0L
        zip.putNextEntry(ZipEntry("road_visits.csv"))
        zip.writer(Charsets.UTF_8).let { writer ->
            writer.write("segment_id,started_at_utc,ended_at_utc\n")
            database.query(
                "road_visits",
                arrayOf("segment_id", "started_at", "ended_at"),
                null, null, null, null,
                "started_at ASC"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    count++
                    val fields = listOf(
                        cursor.getString(0),
                        Instant.ofEpochMilli(cursor.getLong(1)).toString(),
                        Instant.ofEpochMilli(cursor.getLong(2)).toString()
                    )
                    writer.write(fields.joinToString(",") { CsvUtil.escape(it) })
                    writer.write("\n")
                }
            }
            writer.flush()
        }
        zip.closeEntry()
        return count
    }

    private fun writeRoads(database: SQLiteDatabase, zip: ZipOutputStream): Long {
        var count = 0L
        zip.putNextEntry(ZipEntry("roads.csv"))
        zip.writer(Charsets.UTF_8).let { writer ->
            writer.write("segment_id,road_group_id,name,first_unlocked_utc,last_driven_utc,times_driven,times_driven_exact,coordinates_json\n")
            database.query(
                "roads",
                arrayOf("segment_id", "road_group_id", "name", "first_unlocked_at", "last_driven_at", "drive_count", "drive_count_exact", "geometry_json"),
                null, null, null, null,
                "first_unlocked_at ASC"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    count++
                    val fields = listOf(
                        cursor.getString(0),
                        cursor.getString(1).ifBlank { cursor.getString(0) },
                        cursor.getString(2),
                        Instant.ofEpochMilli(cursor.getLong(3)).toString(),
                        Instant.ofEpochMilli(cursor.getLong(4)).toString(),
                        cursor.getInt(5).toString(),
                        (cursor.getInt(6) != 0).toString(),
                        cursor.getString(7)
                    )
                    writer.write(fields.joinToString(",") { CsvUtil.escape(it) })
                    writer.write("\n")
                }
            }
            writer.flush()
        }
        zip.closeEntry()
        return count
    }

}
