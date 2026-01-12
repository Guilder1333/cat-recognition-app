package com.example.catrecognitionsystem.tracking

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.example.catrecognitionsystem.ml.DetectionResult
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

/**
 * Manages tracking of the most confident cat detection of a specific color
 *
 * @param targetCatColor The specific cat color to track (null = track any color)
 * @param reDetectionFrameInterval Number of frames between full detection runs
 * @param trackingLostTimeoutMs Milliseconds to keep trying to recover lost tracking
 */
class MultiCatTrackingManager(
    private var targetCatColor: com.example.catrecognitionsystem.utils.CatColorAnalyzer.CatColor? = null,
    private val reDetectionFrameInterval: Int = 30,
    private val trackingLostTimeoutMs: Long = 2000
) {
    private val activeTrackers = mutableMapOf<String, CatTracker>()
    private var frameCount = 0

    companion object {
        private const val TAG = "MultiCatTrackingManager"
        private const val IOU_THRESHOLD = 0.3f
    }

    /**
     * Sets the target cat color to track
     */
    fun setTargetColor(color: com.example.catrecognitionsystem.utils.CatColorAnalyzer.CatColor?) {
        targetCatColor = color
        Log.d(TAG, "Target color set to: ${color?.name ?: "ANY"}")
    }

    /**
     * Filters detections to only include cats matching the target color
     */
    private fun filterDetectionsByColor(detections: List<DetectionResult>): List<DetectionResult> {
        if (targetCatColor == null) {
            return detections
        }

        val filtered = detections.filter { it.color == targetCatColor }
        Log.d(TAG, "Filtered ${detections.size} detections to ${filtered.size} matching color ${targetCatColor?.name}")
        return filtered
    }

    /**
     * Process a frame with current trackers
     * @return List of tracked cats with updated positions
     */
    fun processFrame(bitmap: Bitmap, currentTrackedCats: List<TrackedCat>): List<TrackedCat> {
        if (activeTrackers.isEmpty()) {
            Log.w(TAG, "No active trackers")
            return emptyList()
        }

        frameCount++

        // Convert bitmap to OpenCV Mat
        val mat = Mat()
        val bitmapCopy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        Utils.bitmapToMat(bitmapCopy, mat)

        // Convert to appropriate format for tracking
        val frameMat = Mat()
        Imgproc.cvtColor(mat, frameMat, Imgproc.COLOR_RGBA2RGB)

        val updatedCats = mutableListOf<TrackedCat>()
        val now = System.currentTimeMillis()

        // Update each tracker
        for (cat in currentTrackedCats) {
            val tracker = activeTrackers[cat.id]

            if (tracker == null) {
                Log.w(TAG, "Tracker not found for cat ${cat.id}")
                continue
            }

            // Update tracker
            val result = tracker.update(frameMat)

            if (result == null || result.first == null) {
                // Tracking lost
                Log.w(TAG, "Tracking lost for cat ${cat.id}")

                // Check if we should keep trying
                val timeSinceLost = now - cat.lastSeenTimestamp
                if (timeSinceLost < trackingLostTimeoutMs) {
                    // Keep the cat with "lost" status
                    updatedCats.add(cat.copy(
                        isLost = true,
                        framesSinceDetection = cat.framesSinceDetection + 1
                    ))
                } else {
                    // Remove tracker
                    removeTracker(cat.id)
                }
            } else {
                // Tracking successful
                val (newBox, confidence) = result
                updatedCats.add(cat.copy(
                    boundingBox = newBox!!,
                    confidence = confidence,
                    isLost = false,
                    framesSinceDetection = cat.framesSinceDetection + 1
                ))
            }
        }

        mat.release()
        frameMat.release()

        return updatedCats
    }

    /**
     * Checks if it's time to re-run detection
     */
    fun shouldRunDetection(): Boolean {
        return frameCount % reDetectionFrameInterval == 0
    }

    /**
     * Merges detection results with current tracked cat
     * - Matches new detections to existing tracker
     * - Updates with most confident detection if tracking lost
     */
    fun mergeDetectionsWithTracking(
        bitmap: Bitmap,
        detections: List<DetectionResult>,
        currentTrackedCats: List<TrackedCat>
    ): List<TrackedCat> {
        Log.d(TAG, "Merging ${detections.size} detections with ${currentTrackedCats.size} tracked cats")

        // Filter detections by target color
        val filteredDetections = filterDetectionsByColor(detections)

        if (currentTrackedCats.isEmpty() || filteredDetections.isEmpty()) {
            return currentTrackedCats
        }

        // We only track one cat - the first in the list
        val cat = currentTrackedCats.first()

        // Convert bitmap to Mat for tracker updates
        val mat = Mat()
        val bitmapCopy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        Utils.bitmapToMat(bitmapCopy, mat)
        val frameMat = Mat()
        Imgproc.cvtColor(mat, frameMat, Imgproc.COLOR_RGBA2RGB)

        // Find best matching detection or highest confidence detection
        var bestDetection: DetectionResult? = null
        var bestIoU = 0f

        for (detection in filteredDetections) {
            val iou = calculateIoU(cat.boundingBox, detection.boundingBox)
            if (iou > IOU_THRESHOLD && iou > bestIoU) {
                bestDetection = detection
                bestIoU = iou
            }
        }

        // If no matching detection found but tracker is lost, use most confident detection
        if (bestDetection == null && cat.isLost) {
            bestDetection = filteredDetections.maxByOrNull { it.confidence }
            Log.d(TAG, "Using most confident detection for lost tracker")
        }

        val updatedCat = if (bestDetection != null) {
            // If tracker was lost, reinitialize it
            if (cat.isLost) {
                Log.d(TAG, "Reinitializing lost tracker for cat ${cat.id}")
                removeTracker(cat.id)
                createTracker(cat.id, bestDetection.boundingBox, frameMat)
            }

            cat.copy(
                boundingBox = bestDetection.boundingBox,
                confidence = bestDetection.confidence,
                catColor = bestDetection.color,
                isLost = false,
                framesSinceDetection = 0,
                lastSeenTimestamp = System.currentTimeMillis()
            )
        } else {
            // No matching detection - keep existing tracking result
            cat
        }

        mat.release()
        frameMat.release()

        return listOf(updatedCat)
    }

    /**
     * Initializes tracking with detection results
     * Only tracks the most confident detection of the target color
     */
    fun initializeTracking(bitmap: Bitmap, detections: List<DetectionResult>): List<TrackedCat> {
        Log.d(TAG, "Initializing tracking with ${detections.size} detections")

        // Clear existing trackers
        clearAllTrackers()
        frameCount = 0

        // Filter detections by target color
        val filteredDetections = filterDetectionsByColor(detections)

        if (filteredDetections.isEmpty()) {
            Log.w(TAG, "No detections matching target color ${targetCatColor?.name ?: "ANY"}")
            return emptyList()
        }

        // Convert bitmap to Mat
        val mat = Mat()
        val bitmapCopy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        Utils.bitmapToMat(bitmapCopy, mat)
        val frameMat = Mat()
        Imgproc.cvtColor(mat, frameMat, Imgproc.COLOR_RGBA2RGB)

        // Take only the most confident detection of the target color
        val bestDetection = filteredDetections.maxByOrNull { it.confidence }!!

        val trackedCat = TrackedCat(
            boundingBox = bestDetection.boundingBox,
            confidence = bestDetection.confidence,
            catColor = bestDetection.color,
            displayColor = CatDisplayColors.getColorForIndex(0),
            label = bestDetection.label
        )

        // Create OpenCV tracker
        createTracker(trackedCat.id, trackedCat.boundingBox, frameMat)

        mat.release()
        frameMat.release()

        Log.d(TAG, "Initialized tracking for most confident cat (confidence: ${trackedCat.confidence})")
        return listOf(trackedCat)
    }

    /**
     * Creates a new tracker for a cat
     */
    private fun createTracker(catId: String, boundingBox: RectF, frameMat: Mat) {
        try {
            val tracker = CatTracker(catId, boundingBox, frameMat)
            activeTrackers[catId] = tracker
            Log.d(TAG, "Created tracker for cat $catId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create tracker for cat $catId", e)
        }
    }

    /**
     * Removes a tracker
     */
    private fun removeTracker(catId: String) {
        activeTrackers[catId]?.release()
        activeTrackers.remove(catId)
        Log.d(TAG, "Removed tracker for cat $catId")
    }

    /**
     * Clears all trackers
     */
    fun clearAllTrackers() {
        activeTrackers.values.forEach { it.release() }
        activeTrackers.clear()
        frameCount = 0
        Log.d(TAG, "Cleared all trackers")
    }

    /**
     * Calculates IoU between two bounding boxes
     */
    private fun calculateIoU(box1: RectF, box2: RectF): Float {
        val intersectionLeft = max(box1.left, box2.left)
        val intersectionTop = max(box1.top, box2.top)
        val intersectionRight = min(box1.right, box2.right)
        val intersectionBottom = min(box1.bottom, box2.bottom)

        if (intersectionRight <= intersectionLeft || intersectionBottom <= intersectionTop) {
            return 0f
        }

        val intersectionArea = (intersectionRight - intersectionLeft) * (intersectionBottom - intersectionTop)
        val box1Area = (box1.right - box1.left) * (box1.bottom - box1.top)
        val box2Area = (box2.right - box2.left) * (box2.bottom - box2.top)
        val unionArea = box1Area + box2Area - intersectionArea

        return if (unionArea > 0) intersectionArea / unionArea else 0f
    }
}
