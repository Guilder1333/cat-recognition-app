package com.example.catrecognitionsystem.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.example.catrecognitionsystem.utils.BitmapUtils
import com.example.catrecognitionsystem.utils.CatColorAnalyzer
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

class CatDetector(context: Context) {

    private var interpreter: Interpreter? = null
    private val labels: List<String>
    private val catClassIndex: Int

    companion object {
        private const val TAG = "CatDetector"
        private const val MODEL_FILE = "detect.tflite"
        private const val LABELS_FILE = "labelmap.txt"
        private const val INPUT_SIZE = 300
        private const val CONFIDENCE_THRESHOLD = 0.1f  // Very low threshold for debugging
        private const val MAX_DETECTIONS = 10
        private const val DEFAULT_CAT_INDEX = 17 // Default cat index in COCO

        // Output indices for SSD MobileNet
        private const val OUTPUT_LOCATIONS_INDEX = 0  // [1, 10, 4] - bounding boxes
        private const val OUTPUT_CLASSES_INDEX = 1    // [1, 10] - class indices
        private const val OUTPUT_SCORES_INDEX = 2     // [1, 10] - confidence scores
        private const val OUTPUT_COUNT_INDEX = 3      // [1] - number of detections
    }

    init {
        var tempLabels: List<String> = emptyList()
        var tempCatIndex = DEFAULT_CAT_INDEX

        try {
            // Load model from assets
            val modelBuffer = FileUtil.loadMappedFile(context, MODEL_FILE)

            // Configure interpreter options
            val options = Interpreter.Options().apply {
                setNumThreads(4)
                // Enable NNAPI delegate for hardware acceleration
                setUseNNAPI(true)
            }

            interpreter = Interpreter(modelBuffer, options)

            // Load labels
            tempLabels = FileUtil.loadLabels(context, LABELS_FILE)

            // Find cat class index
            val foundIndex = tempLabels.indexOfFirst { it.equals("cat", ignoreCase = true) }
            if (foundIndex != -1) {
                tempCatIndex = foundIndex
                Log.d(TAG, "Cat class found at index: $tempCatIndex")
            } else {
                Log.w(TAG, "Cat class not found in labels, using default index $DEFAULT_CAT_INDEX")
            }

            Log.d(TAG, "CatDetector initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing CatDetector", e)
        }

        // Assign final values
        labels = tempLabels
        catClassIndex = tempCatIndex
    }

    /**
     * Detects cats in the given bitmap
     * @param bitmap The input image
     * @return List of DetectionResult containing only cat detections
     */
    fun detectCats(bitmap: Bitmap): List<DetectionResult> {
        if (interpreter == null) {
            Log.e(TAG, "Interpreter is null, cannot perform detection")
            return emptyList()
        }

        try {
            val allDetections = mutableListOf<DetectionResult>()

            // Pass 1: Detect on original image
            val detections1 = runSingleDetection(bitmap, bitmap, false)
            allDetections.addAll(detections1)
            Log.d(TAG, "Pass 1 (original): Found ${detections1.size} cats")

            // Pass 2: Detect on flipped image (helps with angled/side views)
            val flippedBitmap = BitmapUtils.flipBitmap(bitmap)
            val detections2 = runSingleDetection(flippedBitmap, bitmap, true)
            allDetections.addAll(detections2)
            Log.d(TAG, "Pass 2 (flipped): Found ${detections2.size} cats")

            // Remove duplicate detections (same cat detected in both passes)
            val uniqueDetections = removeDuplicateDetections(allDetections)
            Log.d(TAG, "Total unique cats detected: ${uniqueDetections.size}")

            return uniqueDetections
        } catch (e: Exception) {
            Log.e(TAG, "Error during detection", e)
            return emptyList()
        }
    }

    /**
     * Runs detection on a single image
     * @param detectionBitmap The bitmap to run detection on (may be flipped)
     * @param originalBitmap The original bitmap for color analysis
     * @param isFlipped Whether the detection bitmap is flipped
     */
    private fun runSingleDetection(detectionBitmap: Bitmap, originalBitmap: Bitmap, isFlipped: Boolean): List<DetectionResult> {
        try {
            // Preprocess bitmap
            val scaledBitmap = BitmapUtils.scaleBitmap(detectionBitmap, 1024)
            val inputBuffer = BitmapUtils.preprocessBitmapQuantized(scaledBitmap, INPUT_SIZE)

            // Prepare output arrays
            val outputLocations = Array(1) { Array(MAX_DETECTIONS) { FloatArray(4) } }
            val outputClasses = Array(1) { FloatArray(MAX_DETECTIONS) }
            val outputScores = Array(1) { FloatArray(MAX_DETECTIONS) }
            val outputCount = FloatArray(1)

            // Create outputs map
            val outputs = mutableMapOf<Int, Any>()
            outputs[OUTPUT_LOCATIONS_INDEX] = outputLocations
            outputs[OUTPUT_CLASSES_INDEX] = outputClasses
            outputs[OUTPUT_SCORES_INDEX] = outputScores
            outputs[OUTPUT_COUNT_INDEX] = outputCount

            // Run inference
            interpreter?.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputs)

            // Log raw output info for debugging
            Log.d(TAG, "Model inference completed. Detection count: ${outputCount[0]}")
            Log.d(TAG, "First 3 scores: ${outputScores[0].take(3).joinToString()}")
            Log.d(TAG, "First 3 classes: ${outputClasses[0].take(3).joinToString()}")

            // Parse results and filter for cats, using original bitmap for color analysis
            return parseDetections(
                originalBitmap,
                outputLocations[0],
                outputClasses[0],
                outputScores[0],
                outputCount[0].toInt(),
                isFlipped
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error in single detection", e)
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
     * Parses the model output and filters for cat detections
     */
    private fun parseDetections(
        bitmap: Bitmap,
        locations: Array<FloatArray>,
        classes: FloatArray,
        scores: FloatArray,
        numDetections: Int,
        isFlipped: Boolean = false
    ): List<DetectionResult> {
        val detections = mutableListOf<DetectionResult>()
        val validDetections = min(numDetections, MAX_DETECTIONS)

        // Find max score for debugging
        val maxScore = scores.take(validDetections).maxOrNull() ?: 0f
        Log.d(TAG, "Processing $validDetections detections, looking for cat at index $catClassIndex")
        Log.d(TAG, "Max confidence score found: $maxScore")

        // Log ALL detections to see what's happening
        for (i in 0 until validDetections) {
            val score = scores[i]
            val classIndex = classes[i].toInt()
            val className = labels.getOrElse(classIndex) { "unknown-$classIndex" }
            Log.d(TAG, "Detection $i: class=$classIndex ($className), confidence=$score")
        }

        for (i in 0 until validDetections) {
            val score = scores[i]
            val classIndex = classes[i].toInt()

            // Filter by confidence threshold
            if (score < CONFIDENCE_THRESHOLD) {
                continue
            }

            // Filter for cat class only
            // Handle both 0-indexed and potential off-by-one issues
            val isCat = classIndex == catClassIndex ||
                        classIndex == catClassIndex + 1 ||
                        classIndex == catClassIndex - 1

            if (!isCat) {
                continue
            }

            Log.d(TAG, "Cat detected! classIndex=$classIndex, confidence=$score")

            // Extract bounding box coordinates
            // Output format: [top, left, bottom, right] in normalized coordinates [0, 1]
            var top = max(0f, min(1f, locations[i][0]))
            var left = max(0f, min(1f, locations[i][1]))
            var bottom = max(0f, min(1f, locations[i][2]))
            var right = max(0f, min(1f, locations[i][3]))

            // If image was flipped, flip bounding box coordinates back
            if (isFlipped) {
                val tempLeft = left
                left = 1f - right
                right = 1f - tempLeft
            }

            val boundingBox = RectF(left, top, right, bottom)

            // Analyze cat color
            val catColor = CatColorAnalyzer.analyzeCatColor(bitmap, boundingBox)

            // Create detection result
            detections.add(
                DetectionResult(
                    boundingBox = boundingBox,
                    label = labels.getOrElse(classIndex) { "cat" },
                    confidence = score,
                    color = catColor
                )
            )

            Log.d(TAG, "Cat detected: confidence=${score}, color=${CatColorAnalyzer.getColorName(catColor)}, box=[$left, $top, $right, $bottom]")
        }

        Log.d(TAG, "Total cats detected: ${detections.size}")
        return detections
    }

    /**
     * Closes the interpreter and releases resources
     */
    fun close() {
        try {
            interpreter?.close()
            interpreter = null
            Log.d(TAG, "CatDetector closed")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing CatDetector", e)
        }
    }
}
