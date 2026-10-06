package com.roadconquest.app.data

data class TrackPoint(
    val id: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val speedMps: Float,
    val bearingDegrees: Float,
    val timestampMillis: Long,
    val matched: Boolean
)

data class MatchedRoad(
    val name: String,
    val coordinatesJson: String,
    val firstTimestamp: Long,
    val lastTimestamp: Long,
    val confidence: Double
)

data class RoadRecord(
    val segmentId: String,
    val name: String,
    val geometryJson: String,
    val firstUnlockedAt: Long,
    val lastDrivenAt: Long,
    val minLatitude: Double,
    val maxLatitude: Double,
    val minLongitude: Double,
    val maxLongitude: Double,
    val timesDriven: Int = 1,
    val timesDrivenExact: Boolean = true
)

data class DataSummary(
    val trackPointCount: Long,
    val roadSegmentCount: Long,
    val firstTrackAt: Long?,
    val lastTrackAt: Long?,
    val distanceMeters: Double = 0.0,
    val roadsUnlockedCount: Long = roadSegmentCount,
    val historyGeneration: Long = -1L
)
