package com.roadfog.app.data

import android.content.Context
import android.content.Intent
import com.roadfog.app.TrackingService
import com.roadfog.app.export.DataExporter
import com.roadfog.app.progression.ProgressionManager
import com.roadfog.app.util.Prefs

/** One authoritative path for deleting all device-only RoadConquest progress. */
object LocalDataReset {
    fun stopTracking(context: Context) {
        val app = context.applicationContext
        Prefs.setManualOnly(app, true)
        Prefs.setTrackingPaused(app, true)
        // Privacy deletion revokes precise-GPS upload consent before service teardown. This
        // prevents VerifiedDriving.close() from draining a queued final batch after deletion.
        Prefs.setDriveVerificationEnabled(app, false)
        app.stopService(Intent(app, TrackingService::class.java))
    }

    fun clearStoppedData(context: Context) {
        val app = context.applicationContext
        try {
            TrackingRepository(app).clearHistory()
            ProgressionManager.resetLocalProgression(app)
            DataExporter.clearTemporarySnapshots(app)
            for (action in listOf(
                TrackingService.ACTION_STATS_UPDATED,
                TrackingService.ACTION_ROADS_UPDATED,
                TrackingService.ACTION_EXPLORATION_UPDATED
            )) {
                app.sendBroadcast(Intent(action).setPackage(app.packageName))
            }
        } finally {
            // Manual-only remains enabled, so clearing this temporary stop flag cannot
            // restart tracking. It only avoids carrying a "stop until open" state forward.
            Prefs.setTrackingPaused(app, false)
        }
    }
}
