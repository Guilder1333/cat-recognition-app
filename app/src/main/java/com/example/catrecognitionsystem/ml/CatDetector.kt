package com.example.catrecognitionsystem.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.example.catrecognitionsystem.utils.BitmapUtils
import com.example.catrecognitionsystem.utils.CatColorAnalyzer
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import kotlin.math.max
import kotlin.math.min

class CatDetector(context: Context) {

    private var detector: ObjectDetector? = null

    // Debug info accessible from outside
    var lastDebugInfo: String = "Not initialized"
        private set

    companion object {
        private const val TAG = "CatDetector"
        private const val MODEL_FILE = "efficientdet_lite0.tflite"
        private const val CONFIDENCE_THRESHOLD = 0.3f
        private const val MAX_RESULTS = 10
        private const val CAT_LABEL = "cat"
    }

    init {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_FILE)
                .build()

            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setScoreThreshold(CONFIDENCE_THRESHOLD)
                .setMaxResults(MAX_RESULTS)
                .build()

            detector = ObjectDetector.createFromOptions(context, options)
            lastDebugInfo = "Model: $MODEL_FILE\nInit: OK\nThreshold: $CONFIDENCE_THRESHOLD\nMaxResults: $MAX_RESULTS"
            Log.d(TAG, "CatDetector initialized with MediaPipe ObjectDetector")
        } catch (e: Throwable) {
            Log.e(TAG, "Error initializing CatDetector", e)
            lastDebugInfo = "INIT ERROR: ${e::class.simpleName}: ${e.message}"
        }
    }

    /**
     * Detects cats in the given bitmap using two-pass strategy (original + flipped)
     * @param bitmap The input image
     * @return List of DetectionResult containing only cat detections
     */
    fun detectCats(bitmap: Bitmap): List<DetectionResult> {
        if (detector == null) {
            Log.e(TAG, "Detector is null, cannot perform detection. Init info: $lastDebugInfo")
            lastDebugInfo = "Detector is null\n$lastDebugInfo"
            return emptyList()
        }

        try {
            val allDetections = mutableListOf<DetectionResult>()

            // Pass 1: Detect on original image
            val detections1 = runSingleDetection(bitmap, bitmap, false)
            allDetections.addAll(detections1)
            val rawDebugPass1 = lastDebugInfo
            Log.d(TAG, "Pass 1 (original): Found ${detections1.size} cats")

            // Pass 2: Detect on flipped image (helps with angled/side views)
            val flippedBitmap = BitmapUtils.flipBitmap(bitmap)
            val detections2 = runSingleDetection(flippedBitmap, bitmap, true)
            allDetections.addAll(detections2)
            Log.d(TAG, "Pass 2 (flipped): Found ${detections2.size} cats")

            // Remove duplicate detections (same cat detected in both passes)
            val uniqueDetections = removeDuplicateDetections(allDetections)
            Log.d(TAG, "Total unique cats detected: ${uniqueDetections.size}")

            // Final debug: raw model output from pass 1 + summary
            lastDebugInfo = "$rawDebugPass1---\nPass1 cats: ${detections1.size} | Pass2 cats: ${detections2.size}\nFinal unique cats: ${uniqueDetections.size}"

            return uniqueDetections
        } catch (e: Exception) {
            Log.e(TAG, "Error during detection", e)
            lastDebugInfo = "DETECT ERROR: ${e.message}\n${e.stackTraceToString().take(300)}"
            return emptyList()
        }
    }

    /**
     * Runs detection on a single image using MediaPipe ObjectDetector
     * @param detectionBitmap The bitmap to run detection on (may be flipped)
     * @param originalBitmap The original bitmap for color analysis
     * @param isFlipped Whether the detection bitmap is flipped horizontally
     */
    private fun runSingleDetection(detectionBitmap: Bitmap, originalBitmap: Bitmap, isFlipped: Boolean): List<DetectionResult> {
        try {
            val mpImage = BitmapImageBuilder(detectionBitmap).build()
            val result = detector!!.detect(mpImage)
            val detections = result.detections()

            Log.d(TAG, "Model returned ${detections.size} raw detections")

            // Build debug info showing all detections (not just cats)
            val debugBuilder = StringBuilder()
            debugBuilder.append("Model: $MODEL_FILE\n")
            debugBuilder.append("Raw detections: ${detections.size}\n")
            for (i in detections.indices) {
                val det = detections[i]
                val category = det.categories()[0]
                val box = det.boundingBox()
                debugBuilder.append("Det$i: ${category.categoryName()} score=${"%.3f".format(category.score())}" +
                        " box=[${box.left.toInt()},${box.top.toInt()},${box.right.toInt()},${box.bottom.toInt()}]\n")
            }
            lastDebugInfo = debugBuilder.toString()

            // Filter for cat detections and normalize bounding boxes
            val catDetections = mutableListOf<DetectionResult>()
            val imageWidth = detectionBitmap.width.toFloat()
            val imageHeight = detectionBitmap.height.toFloat()

            for (detection in detections) {
                val category = detection.categories()[0]
                if (!category.categoryName().equals(CAT_LABEL, ignoreCase = true)) continue

                val box = detection.boundingBox()

                // Normalize pixel coordinates to [0, 1]
                var left = (box.left / imageWidth).coerceIn(0f, 1f)
                val top = (box.top / imageHeight).coerceIn(0f, 1f)
                var right = (box.right / imageWidth).coerceIn(0f, 1f)
                val bottom = (box.bottom / imageHeight).coerceIn(0f, 1f)

                // Flip bounding box back if image was flipped
                if (isFlipped) {
                    val tempLeft = left
                    left = 1f - right
                    right = 1f - tempLeft
                }

                val boundingBox = RectF(left, top, right, bottom)
                val catColor = CatColorAnalyzer.analyzeCatColor(originalBitmap, boundingBox)

                catDetections.add(DetectionResult(
                    boundingBox = boundingBox,
                    label = category.categoryName(),
                    confidence = category.score(),
                    color = catColor
                ))

                Log.d(TAG, "Cat detected: confidence=${category.score()}, color=${CatColorAnalyzer.getColorName(catColor)}, box=[$left, $top, $right, $bottom]")
            }

            Log.d(TAG, "Cats in this pass: ${catDetections.size}")
            return catDetections
        } catch (e: Exception) {
            Log.e(TAG, "Error in single detection", e)
            lastDebugInfo = "INFERENCE ERROR: ${e.message}\n${e.stackTraceToString().take(300)}"
            return emptyList()
        }
    }

    /**
     * Removes duplicate detections (likely the same cat detected multiple times)
     */
    private fun removeDuplicateDetections(detections: List<DetectionResult>): List<DetectionResult> {
        if (detections.size <= 1) return detections

        val uniqueDetections = mutableListOf<DetectionResult>()

        for (detection in detections) {
            var isDuplicate = false

            for (existing in uniqueDetections) {
                // Check if bounding boxes overlap significantly (IoU > 0.5)
                val iou = calculateIoU(detection.boundingBox, existing.boundingBox)
                if (iou > 0.5f) {
                    isDuplicate = true
                    // Keep the one with higher confidence
                    if (detection.confidence > existing.confidence) {
                        uniqueDetections.remove(existing)
                        uniqueDetections.add(detection)
                    }
                    break
                }
            }

            if (!isDuplicate) {
                uniqueDetections.add(detection)
            }
        }

        return uniqueDetections
    }

    /**
     * Calculates Intersection over Union (IoU) between two bounding boxes
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

    /**
     * Closes the detector and releases resources
     */
    fun close() {
        try {
            detector?.close()
            detector = null
            Log.d(TAG, "CatDetector closed")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing CatDetector", e)
        }
    }
}
