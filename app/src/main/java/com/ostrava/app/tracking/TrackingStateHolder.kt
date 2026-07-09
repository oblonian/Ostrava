package com.ostrava.app.tracking

import com.ostrava.app.domain.ActivityType
import com.ostrava.app.domain.IntervalPhase
import com.ostrava.app.domain.TrackPoint
import kotlinx.coroutines.flow.MutableStateFlow

enum class TrackingStatus { IDLE, TRACKING, PAUSED, AUTO_PAUSED }

data class RecordingState(
    val status: TrackingStatus = TrackingStatus.IDLE,
    val type: ActivityType = ActivityType.RUN,
    val distanceMeters: Double = 0.0,
    val movingTimeMillis: Long = 0L,
    val currentSpeedMps: Float = 0f,
    val maxSpeedMps: Float = 0f,
    val elevationGainMeters: Double = 0.0,
    val points: List<TrackPoint> = emptyList(),
    val lastFix: TrackPoint? = null,
    val gpsAccuracyMeters: Float? = null,
    val heartRateBpm: Int? = null,
    val intervalPhase: IntervalPhase? = null,
) {
    val isActive: Boolean get() = status != TrackingStatus.IDLE
    val avgSpeedMps: Double
        get() = if (movingTimeMillis > 0) distanceMeters / (movingTimeMillis / 1000.0) else 0.0
}

/**
 * Single source of truth shared between [TrackingService] (writer) and the UI (reader).
 * Avoids binder plumbing for in-process service state.
 */
object TrackingStateHolder {
    val state = MutableStateFlow(RecordingState())

    /** Set after a successful save so the record screen can navigate to the new activity. */
    val lastSavedActivityId = MutableStateFlow<Long?>(null)

    fun reset() {
        state.value = RecordingState()
    }
}
