package com.example.catrecognitionsystem.tracking

/**
 * Represents the different states of the tracking system
 */
sealed class TrackingMode {
    /**
     * No tracking active, waiting for user to start
     */
    object Idle : TrackingMode()

    /**
     * Running initial detection to find cats before starting tracking
     */
    object InitialDetection : TrackingMode()

    /**
     * Actively tracking one or more cats
     * @param trackedCats List of currently tracked cats
     * @param framesSinceLastDetection Number of frames since last full detection run
     */
    data class ActiveTracking(
        val trackedCats: List<TrackedCat>,
        val framesSinceLastDetection: Int = 0
    ) : TrackingMode()

    /**
     * Tracking was lost for all cats
     * @param lastKnownCats Last known positions of tracked cats
     * @param lostTimestamp When tracking was lost
     */
    data class TrackingLost(
        val lastKnownCats: List<TrackedCat>,
        val lostTimestamp: Long
    ) : TrackingMode()
}
