package com.example.catrecognitionsystem.tracking

import android.graphics.RectF
import android.util.Log
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Sparse optical flow tracker using Lucas-Kanade (LK) method.
 *
 * Replaces TrackerMIL with point-based tracking to avoid silent drift onto
 * uniform backgrounds. Each tracked point has an explicit status/error, and
 * points that diverge from the cluster (e.g. onto the floor) are discarded as
 * outliers before the bounding box is derived.
 *
 * @param catId     Unique identifier for the cat being tracked
 * @param initialBoundingBox Initial bounding box in normalized coordinates [0,1]
 * @param frameMat  First frame to initialize the tracker (RGB format)
 */
class CatTracker(
    private val catId: String,
    private val initialBoundingBox: RectF,
    frameMat: Mat
) {
    private var prevGray: Mat = Mat()
    private var trackedPoints: MatOfPoint2f? = null
    private var isInitialized = false

    companion object {
        private const val TAG = "CatTracker"
        private const val MAX_FEATURES = 30
        private const val MIN_FEATURES = 5      // Re-detection triggered if below this
        private const val QUALITY_LEVEL = 0.01  // Low threshold — important for black cats with few corners
        private const val MIN_DISTANCE = 7.0    // Minimum pixel distance between features
        private const val MAX_TRACK_ERROR = 20f // LK tracking error threshold
        private const val OUTLIER_DISTANCE_FACTOR = 2.5 // Points beyond this × median dist are outliers
        private const val MIN_OUTLIER_THRESHOLD_PX = 20.0 // Minimum outlier radius even if cluster is tight
    }

    init {
        initialize(frameMat)
    }

    private fun initialize(frameMat: Mat) {
        val gray = Mat()
        try {
            Imgproc.cvtColor(frameMat, gray, Imgproc.COLOR_RGB2GRAY)

            val cols = frameMat.cols()
            val rows = frameMat.rows()

            // Clamp ROI to valid frame bounds
            val roiX = (initialBoundingBox.left * cols).toInt().coerceIn(0, cols - 1)
            val roiY = (initialBoundingBox.top * rows).toInt().coerceIn(0, rows - 1)
            val roiW = ((initialBoundingBox.right - initialBoundingBox.left) * cols).toInt()
                .coerceIn(1, cols - roiX)
            val roiH = ((initialBoundingBox.bottom - initialBoundingBox.top) * rows).toInt()
                .coerceIn(1, rows - roiY)

            val roi = gray.submat(roiY, roiY + roiH, roiX, roiX + roiW)
            val corners = MatOfPoint()

            Imgproc.goodFeaturesToTrack(roi, corners, MAX_FEATURES, QUALITY_LEVEL, MIN_DISTANCE)

            val count = corners.rows()

            if (count == 0) {
                Log.w(TAG, "No features found for cat $catId — bounding box may be too small or uniform")
                roi.release()
                corners.release()
                gray.release()
                return
            }

            // Shift corner coordinates from ROI-local to full-frame space
            val rawPoints = corners.toArray()
            val fullFramePoints = Array(rawPoints.size) { i ->
                Point(rawPoints[i].x + roiX, rawPoints[i].y + roiY)
            }

            roi.release()
            corners.release()
            prevGray.release()
            prevGray = gray  // transfer ownership — do NOT release gray below
            trackedPoints = MatOfPoint2f().also { it.fromArray(*fullFramePoints) }
            isInitialized = true

            Log.d(TAG, "LK tracker initialized for cat $catId with $count feature points")
        } catch (e: Exception) {
            Log.e(TAG, "Error creating LK tracker for cat $catId", e)
            gray.release()
            isInitialized = false
        }
    }

    /**
     * Advances the tracker by one frame using Lucas-Kanade optical flow.
     *
     * @param frameMat New frame in RGB format
     * @return Pair of (updated bounding box in normalized coords, confidence) or null if tracking failed
     */
    fun update(frameMat: Mat): Pair<RectF?, Float>? {
        if (!isInitialized || trackedPoints == null) return null

        val currGray = Mat()
        val nextPoints = MatOfPoint2f()
        val status = MatOfByte()
        val errors = MatOfFloat()

        try {
            Imgproc.cvtColor(frameMat, currGray, Imgproc.COLOR_RGB2GRAY)

            Video.calcOpticalFlowPyrLK(prevGray, currGray, trackedPoints!!, nextPoints, status, errors)

            val statusArr = status.toArray()
            val errorArr = errors.toArray()
            val nextArr = nextPoints.toArray()

            // Keep only points that LK tracked successfully and with low reprojection error
            val goodPoints = mutableListOf<Point>()
            for (i in statusArr.indices) {
                if (statusArr[i].toInt() == 1 && errorArr[i] < MAX_TRACK_ERROR) {
                    goodPoints.add(nextArr[i])
                }
            }

            if (goodPoints.size < MIN_FEATURES) {
                Log.w(TAG, "Cat $catId: only ${goodPoints.size} good points after LK — tracking lost")
                currGray.release()
                return null
            }

            // Discard points that diverge from the cluster centroid
            val filteredPoints = removeOutliers(goodPoints)

            if (filteredPoints.size < MIN_FEATURES) {
                Log.w(TAG, "Cat $catId: only ${filteredPoints.size} points after outlier removal — tracking lost")
                currGray.release()
                return null
            }

            // Update state: swap previous gray frame and tracked point set
            prevGray.release()
            prevGray = currGray  // transfer ownership
            trackedPoints!!.release()
            trackedPoints = MatOfPoint2f().also { it.fromArray(*filteredPoints.toTypedArray()) }

            val newBox = deriveBoundingBox(filteredPoints, frameMat.cols(), frameMat.rows())
            val confidence = calculateConfidence(filteredPoints.size, newBox)

            return Pair(newBox, confidence)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating LK tracker for cat $catId", e)
            currGray.release()
            return null
        } finally {
            nextPoints.release()
            status.release()
            errors.release()
        }
    }

    /**
     * Removes points that are far from the median cluster centre.
     * A point that drifts onto the floor or background will typically be far
     * from the majority of points still on the cat and gets filtered here.
     */
    private fun removeOutliers(points: List<Point>): List<Point> {
        if (points.size <= 3) return points

        val sortedX = points.map { it.x }.sorted()
        val sortedY = points.map { it.y }.sorted()
        val medianX = sortedX[points.size / 2]
        val medianY = sortedY[points.size / 2]

        val distances = points.map { p ->
            sqrt((p.x - medianX) * (p.x - medianX) + (p.y - medianY) * (p.y - medianY))
        }
        val medianDist = distances.sorted()[distances.size / 2]
        val threshold = max(medianDist * OUTLIER_DISTANCE_FACTOR, MIN_OUTLIER_THRESHOLD_PX)

        return points.filter { p ->
            sqrt((p.x - medianX) * (p.x - medianX) + (p.y - medianY) * (p.y - medianY)) <= threshold
        }
    }

    /**
     * Derives a bounding box from the point cloud centre, preserving the
     * original detection box size so the overlay doesn't shrink as points are lost.
     */
    private fun deriveBoundingBox(points: List<Point>, frameW: Int, frameH: Int): RectF {
        val cx = points.map { it.x }.average() / frameW
        val cy = points.map { it.y }.average() / frameH

        val halfW = (initialBoundingBox.right - initialBoundingBox.left) / 2f
        val halfH = (initialBoundingBox.bottom - initialBoundingBox.top) / 2f

        return RectF(
            (cx - halfW).toFloat().coerceIn(0f, 1f),
            (cy - halfH).toFloat().coerceIn(0f, 1f),
            (cx + halfW).toFloat().coerceIn(0f, 1f),
            (cy + halfH).toFloat().coerceIn(0f, 1f)
        )
    }

    private fun calculateConfidence(pointCount: Int, box: RectF): Float {
        val pointScore = (pointCount.toFloat() / MAX_FEATURES).coerceIn(0f, 1f)
        val edgePenalty = if (box.left < 0.01f || box.top < 0.01f ||
                              box.right > 0.99f || box.bottom > 0.99f) 0.7f else 1.0f
        return (0.6f + 0.3f * pointScore) * edgePenalty
    }

    /**
     * Releases all OpenCV resources held by this tracker.
     */
    fun release() {
        prevGray.release()
        trackedPoints?.release()
        trackedPoints = null
        isInitialized = false
    }
}
