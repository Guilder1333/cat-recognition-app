package com.example.catrecognitionsystem.tracking

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.example.catrecognitionsystem.ml.DetectionResult
import com.example.catrecognitionsystem.utils.CatColorAnalyzer
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

/**
 * Result of validation check during tracking
 */
data class ValidationResult(
    val updatedCat: TrackedCat,
    val validationPassed: Boolean,
    val shouldStopTracking: Boolean
)

/**
 * Manages tracking of the most confident cat detection of a specific color
 *
 * @param targetCatColor The specific cat color to track (null = track any color)
 * @param reDetectionFrameInterval Number of frames between full detection runs
 * @param trackingLostTimeoutMs Milliseconds to keep trying to recover lost tracking
 * @param validationIntervalMs Milliseconds between validation checks (default 2.5 seconds)
 */
class MultiCatTrackingManager(
    private var targetCatColor: CatColorAnalyzer.CatColor? = null,
    private val reDetectionFrameInterval: Int = 30,
    private val trackingLostTimeoutMs: Long = 2000,
    private val validationIntervalMs: Long = 2500
) {
    private val activeTrackers = mutableMapOf<String, CatTracker>()
    private var frameCount = 0

    companion object {
        private const val TAG = "MultiCatTrackingManager"
        private const val IOU_THRESHOLD = 0.3f
        private const val VALIDATION_IOU_THRESHOLD = 0.2f  // Slightly lower for validation
        private const val VALIDATION_FAILURE_THRESHOLD = 3  // Stop after 3 consecutive failures
    }

    /**
     * Sets the target cat color to track
     */
    fun setTargetColor(color: CatColorAnalyzer.CatColor?) {
        targetCatColor = color
        Log.d(TAG, "Target color set to: ${color?.name ?: "ANY"}")
    }

    /**
     * Checks if enough time has passed since last validation to run another
     */
    fun shouldRunValidation(currentCat: TrackedCat): Boolean {
        val timeSinceLastValidation = System.currentTimeMillis() - currentCat.lastValidationTimestamp
        return timeSinceLastValidation >= validationIntervalMs
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
     * Merges detection results with current tracked cat and validates tracking
     * - Matches new detections to existing tracker by IoU AND locked color
     * - Increments validation failure counter if no match found
     * - Returns validation result indicating if tracking should stop
     */
    fun mergeDetectionsWithTracking(
        bitmap: Bitmap,
        detections: List<DetectionResult>,
        currentTrackedCats: List<TrackedCat>
    ): ValidationResult {
        Log.d(TAG, "Merging ${detections.size} detections with ${currentTrackedCats.size} tracked cats")

        if (currentTrackedCats.isEmpty()) {
            Log.w(TAG, "No tracked cats to validate")
            return ValidationResult(
                updatedCat = TrackedCat(
                    boundingBox = RectF(0f, 0f, 0f, 0f),
                    confidence = 0f,
                    catColor = CatColorAnalyzer.CatColor.UNKNOWN,
                    displayColor = CatDisplayColors.getColorForIndex(0)
                ),
                validationPassed = false,
                shouldStopTracking = true
            )
        }

        val cat = currentTrackedCats.first()
        val now = System.currentTimeMillis()

        // Filter detections by LOCKED color (not target color) for validation
        val colorMatchingDetections = if (cat.lockedColor != CatColorAnalyzer.CatColor.UNKNOWN) {
            detections.filter { it.color == cat.lockedColor }
        } else {
            // If locked color is UNKNOWN, use target color filter
            filterDetectionsByColor(detections)
        }

        Log.d(TAG, "Filtered to ${colorMatchingDetections.size} detections matching locked color ${cat.lockedColor}")

        // Convert bitmap to Mat for potential tracker reinitialization
        val mat = Mat()
        val bitmapCopy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        Utils.bitmapToMat(bitmapCopy, mat)
        val frameMat = Mat()
        Imgproc.cvtColor(mat, frameMat, Imgproc.COLOR_RGBA2RGB)

        // Find detection that matches BOTH location (IoU) AND color
        var bestMatchingDetection: DetectionResult? = null
        var bestIoU = 0f

        for (detection in colorMatchingDetections) {
            val iou = calculateIoU(cat.boundingBox, detection.boundingBox)
            if (iou > VALIDATION_IOU_THRESHOLD && iou > bestIoU) {
                bestMatchingDetection = detection
                bestIoU = iou
            }
        }

        val validationPassed = bestMatchingDetection != null

        val updatedCat = if (bestMatchingDetection != null) {
            // VALIDATION PASSED: Found matching cat at tracking location
            Log.d(TAG, "Validation PASSED: Found ${cat.lockedColor} cat with IoU=$bestIoU")

            // Reinitialize tracker if it was lost
            if (cat.isLost) {
                Log.d(TAG, "Reinitializing lost tracker for cat ${cat.id}")
                removeTracker(cat.id)
                createTracker(cat.id, bestMatchingDetection.boundingBox, frameMat)
            }

            cat.copy(
                boundingBox = bestMatchingDetection.boundingBox,
                confidence = bestMatchingDetection.confidence,
                // catColor stays as lockedColor - don't update from detection
                isLost = false,
                framesSinceDetection = 0,
                lastSeenTimestamp = now,
                consecutiveValidationFailures = 0,
                lastValidationTimestamp = now,
                validationStatus = ValidationStatus.VALID
            )
        } else {
            // VALIDATION FAILED: No matching cat found at tracking location
            val newFailureCount = cat.consecutiveValidationFailures + 1
            Log.d(TAG, "Validation FAILED: No ${cat.lockedColor} cat found at tracking location. Failures: $newFailureCount/$VALIDATION_FAILURE_THRESHOLD")

            val newStatus = when {
                newFailureCount >= VALIDATION_FAILURE_THRESHOLD -> ValidationStatus.INVALID
                newFailureCount >= 1 -> ValidationStatus.UNCERTAIN
                else -> ValidationStatus.VALID
            }

            cat.copy(
                consecutiveValidationFailures = newFailureCount,
                lastValidationTimestamp = now,
                validationStatus = newStatus,
                isLost = newFailureCount >= 2
            )
        }

        mat.release()
        frameMat.release()

        val shouldStop = updatedCat.validationStatus == ValidationStatus.INVALID

        if (shouldStop) {
            Log.w(TAG, "Tracking should STOP: ${updatedCat.consecutiveValidationFailures} consecutive validation failures")
        }

        return ValidationResult(
            updatedCat = updatedCat,
            validationPassed = validationPassed,
            shouldStopTracking = shouldStop
        )
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
        val now = System.currentTimeMillis()

        val trackedCat = TrackedCat(
            boundingBox = bestDetection.boundingBox,
            confidence = bestDetection.confidence,
            catColor = bestDetection.color,
            lockedColor = bestDetection.color,  // Lock color at initialization
            displayColor = CatDisplayColors.getColorForIndex(0),
            label = bestDetection.label,
            consecutiveValidationFailures = 0,
            lastValidationTimestamp = now,
            validationStatus = ValidationStatus.VALID
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
