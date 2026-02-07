package com.example.catrecognitionsystem.utils

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.util.Log
import kotlin.math.max
import kotlin.math.min
import androidx.core.graphics.get

object CatColorAnalyzer {

    private const val TAG = "CatColorAnalyzer"

    enum class CatColor {
        BLACK,
        TABBY,
        UNKNOWN
    }

    /**
     * Analyzes the color of a detected cat in the given region
     * @param bitmap The full image
     * @param boundingBox Normalized coordinates [0, 1] of the cat region
     * @return CatColor classification
     */
    fun analyzeCatColor(bitmap: Bitmap, boundingBox: RectF): CatColor {
        try {
            // Convert normalized coordinates to pixel coordinates
            val left = (boundingBox.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
            val top = (boundingBox.top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
            val right = (boundingBox.right * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
            val bottom = (boundingBox.bottom * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)

            val width = right - left
            val height = bottom - top

            if (width <= 0 || height <= 0) {
                Log.w(TAG, "Invalid bounding box dimensions")
                return CatColor.UNKNOWN
            }

            // Create an inner region (60% of the bounding box) to avoid sampling background/floor
            // This focuses on the cat's body rather than edges where floor/background appear
            val marginX = (width * 0.2).toInt() // 20% margin on each side = 60% center
            val marginY = (height * 0.2).toInt()

            val innerLeft = left + marginX
            val innerTop = top + marginY
            val innerRight = right - marginX
            val innerBottom = bottom - marginY

            val innerWidth = innerRight - innerLeft
            val innerHeight = innerBottom - innerTop

            if (innerWidth <= 0 || innerHeight <= 0) {
                Log.w(TAG, "Inner region too small, using full bounding box")
                // Fallback to full box if too small
                return analyzeCatColorFullBox(bitmap, left, top, right, bottom)
            }

            Log.d(TAG, "Sampling from inner region: (${innerLeft}, ${innerTop}) to (${innerRight}, ${innerBottom})")

            // Sample pixels from the INNER region only
            val samples = mutableListOf<Int>()
            val step = max(1, min(innerWidth, innerHeight) / 20) // Sample ~400 pixels

            for (y in innerTop until innerBottom step step) {
                for (x in innerLeft until innerRight step step) {
                    if (x < bitmap.width && y < bitmap.height) {
                        samples.add(bitmap[x, y])
                    }
                }
            }

            if (samples.isEmpty()) {
                return CatColor.UNKNOWN
            }

            // Analyze color characteristics
            var darkPixels = 0
            var brightPixels = 0
            var brownOrangePixels = 0
            val hsv = FloatArray(3)

            for (pixel in samples) {
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                // Calculate brightness
                val brightness = (r + g + b) / 3

                if (brightness < 60) {
                    darkPixels++
                } else if (brightness > 150) {
                    brightPixels++
                }

                // Convert to HSV for better color analysis
                Color.RGBToHSV(r, g, b, hsv)
                val hue = hsv[0]
                val saturation = hsv[1]
                val value = hsv[2]

                // Check for brown/orange/tan colors typical of tabby cats
                // Hue: 20-60 degrees (orange to yellow-brown range)
                // Saturation: > 0.15 (some color, not grayscale)
                if (hue in 10f..70f && saturation > 0.15f && value > 0.2f) {
                    brownOrangePixels++
                }
            }

            val totalSamples = samples.size
            val darkRatio = darkPixels.toFloat() / totalSamples
            val brownOrangeRatio = brownOrangePixels.toFloat() / totalSamples

            Log.d(TAG, "Color analysis - Samples: $totalSamples, Dark: ${(darkRatio * 100).toInt()}%, " +
                    "BrownOrange: ${(brownOrangeRatio * 100).toInt()}%, Bright: ${(brightPixels.toFloat() / totalSamples * 100).toInt()}%")

            // Classification logic - adjusted for center-focused sampling
            val result = when {
                // Black cat: More than 60% dark pixels and very little brown/orange
                darkRatio > 0.6 && brownOrangeRatio < 0.15 -> CatColor.BLACK

                // Tabby cat: Significant brown/orange pixels
                brownOrangeRatio > 0.20 -> CatColor.TABBY

                // If we have some brown/orange but not enough dark pixels, likely tabby
                brownOrangeRatio > 0.12 && darkRatio < 0.5 -> CatColor.TABBY

                // If mostly dark with very minimal color, it's black
                darkRatio > 0.5 && brownOrangeRatio < 0.10 -> CatColor.BLACK

                // If dark with some color variation, could be dark tabby (but less threshold now)
                darkRatio > 0.5 && brownOrangeRatio > 0.15 -> CatColor.TABBY

                // If very dark with minimal color, it's black
                darkRatio > 0.5 -> CatColor.BLACK

                else -> CatColor.UNKNOWN
            }

            Log.d(TAG, "Color classification result: $result")
            return result

        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing cat color", e)
            return CatColor.UNKNOWN
        }
    }

    /**
     * Fallback method: analyzes color from full bounding box when inner region is too small
     */
    private fun analyzeCatColorFullBox(bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int): CatColor {
        val samples = mutableListOf<Int>()
        val width = right - left
        val height = bottom - top
        val step = max(1, min(width, height) / 20)

        for (y in top until bottom step step) {
            for (x in left until right step step) {
                if (x < bitmap.width && y < bitmap.height) {
                    samples.add(bitmap[x, y])
                }
            }
        }

        if (samples.isEmpty()) {
            return CatColor.UNKNOWN
        }

        var darkPixels = 0
        var brownOrangePixels = 0
        val hsv = FloatArray(3)

        for (pixel in samples) {
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)
            val brightness = (r + g + b) / 3

            if (brightness < 60) {
                darkPixels++
            }

            Color.RGBToHSV(r, g, b, hsv)
            if (hsv[0] in 10f..70f && hsv[1] > 0.15f && hsv[2] > 0.2f) {
                brownOrangePixels++
            }
        }

        val darkRatio = darkPixels.toFloat() / samples.size
        val brownOrangeRatio = brownOrangePixels.toFloat() / samples.size

        Log.d(TAG, "Full box analysis - Dark: ${(darkRatio * 100).toInt()}%, BrownOrange: ${(brownOrangeRatio * 100).toInt()}%")

        return when {
            darkRatio > 0.6 && brownOrangeRatio < 0.15 -> CatColor.BLACK
            brownOrangeRatio > 0.20 -> CatColor.TABBY
            darkRatio > 0.5 && brownOrangeRatio < 0.10 -> CatColor.BLACK
            darkRatio > 0.5 -> CatColor.BLACK
            else -> CatColor.UNKNOWN
        }
    }

    /**
     * Returns a human-readable color name
     */
    fun getColorName(color: CatColor): String {
        return when (color) {
            CatColor.BLACK -> "Black"
            CatColor.TABBY -> "Tabby"
            CatColor.UNKNOWN -> "Unknown"
        }
    }
}
