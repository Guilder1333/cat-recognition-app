package com.example.catrecognitionsystem.tracking

import android.graphics.RectF
import android.util.Log
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.video.Tracker
import org.opencv.video.TrackerMIL

/**
 * Wrapper around OpenCV MIL tracker for a single cat
 *
 * @param catId Unique identifier for the cat being tracked
 * @param initialBoundingBox Initial bounding box in normalized coordinates [0,1]
 * @param frameMat First frame to initialize the tracker
 */
class CatTracker(
    private val catId: String,
    initialBoundingBox: RectF,
    frameMat: Mat
) {
    private var tracker: Tracker? = null
    private var isInitialized = false

    companion object {
        private const val TAG = "CatTracker"
    }

    init {
        try {
            // Create MIL tracker
            tracker = TrackerMIL.create()

            // Convert normalized RectF to OpenCV Rect in pixel coordinates
            val rect = Rect(
                (initialBoundingBox.left * frameMat.cols()).toInt(),
                (initialBoundingBox.top * frameMat.rows()).toInt(),
                ((initialBoundingBox.right - initialBoundingBox.left) * frameMat.cols()).toInt(),
                ((initialBoundingBox.bottom - initialBoundingBox.top) * frameMat.rows()).toInt()
            )

            // Initialize tracker with first frame
            tracker?.init(frameMat, rect)
            isInitialized = true

            Log.d(TAG, "Tracker initialized for cat $catId at $rect")
        } catch (e: Exception) {
            Log.e(TAG, "Error creating tracker for cat $catId", e)
            isInitialized = false
        }
    }

    /**
     * Updates tracking with a new frame
     * @param frameMat The new frame to process
     * @return Pair of (updated bounding box, confidence) or null if tracking failed
     */
    fun update(frameMat: Mat): Pair<RectF?, Float>? {
        if (!isInitialized || tracker == null) {
            return null
        }

        try {
            val rect = Rect()
            tracker?.update(frameMat, rect)

            // Check if rect is valid (has non-zero dimensions)
            if (rect.width <= 0 || rect.height <= 0) {
                Log.w(TAG, "Tracking failed for cat $catId - invalid rect")
                return null
            }

            // Convert OpenCV Rect to normalized RectF
            val normalizedBox = RectF(
                rect.x.toFloat() / frameMat.cols(),
                rect.y.toFloat() / frameMat.rows(),
                (rect.x + rect.width).toFloat() / frameMat.cols(),
                (rect.y + rect.height).toFloat() / frameMat.rows()
            )

            // Calculate confidence based on box validity
            val confidence = calculateConfidence(normalizedBox, rect)

            return Pair(normalizedBox, confidence)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating tracker for cat $catId", e)
            return null
        }
    }

    /**
     * Calculates confidence score based on bounding box characteristics
     */
    private fun calculateConfidence(normalizedBox: RectF, rect: Rect): Float {
        // Check if box is within frame bounds
        if (normalizedBox.left < 0 || normalizedBox.top < 0 ||
            normalizedBox.right > 1 || normalizedBox.bottom > 1) {
            return 0.3f
        }

        // Check if box size is reasonable (not too small or too large)
        val area = (normalizedBox.right - normalizedBox.left) *
                   (normalizedBox.bottom - normalizedBox.top)

        if (area !in 0.01f..0.8f) {
            return 0.5f
        }

        // Good tracking confidence
        return 0.85f
    }

    /**
     * Releases OpenCV tracker resources
     */
    fun release() {
        tracker = null
        isInitialized = false
    }
}
