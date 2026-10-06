package com.roadconquest.app.util

import android.content.Context
import com.roadconquest.app.R
import com.roadconquest.app.data.DataSummary

object StatsText {
    fun format(context: Context, summary: DataSummary): String {
        val roads = context.resources.getQuantityString(R.plurals.roads_unlocked,
            summary.roadsUnlockedCount.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(), summary.roadsUnlockedCount)
        return context.getString(R.string.map_stats, summary.distanceMeters / 1609.344, roads)
    }
}
