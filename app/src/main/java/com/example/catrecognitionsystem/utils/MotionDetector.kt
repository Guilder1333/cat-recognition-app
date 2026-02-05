package com.example.catrecognitionsystem.utils

import android.graphics.Bitmap
import kotlin.math.abs

/**
 * Detects motion between consecutive frames using grayscale pixel difference
 * on downscaled images for performance.
 *
 * @param sampleWidth  Width to downscale frames to for comparison
 * @param sampleHeight Height to downscale frames to for comparison
 * @param pixelThreshold   Minimum luminance difference per pixel to count as changed
 * @param motionThreshold  Fraction of pixels that must change to trigger motion
 */
class MotionDetector(
    private val sampleWidth: Int = 160,
    private val sampleHeight: Int = 120,
    private val pixelThreshold: Int = 30,
    private val motionThreshold: Float = 0.05f
) {
    private var previousGray: IntArray? = null

    /**
     * Returns true if motion is detected compared to the previous frame.
     * The first call always returns false (no previous frame to compare).
     */
    fun detectMotion(bitmap: Bitmap): Boolean {
        val scaled = Bitmap.createScaledBitmap(bitmap, sampleWidth, sampleHeight, false)
        val pixels = IntArray(sampleWidth * sampleHeight)
        scaled.getPixels(pixels, 0, sampleWidth, 0, 0, sampleWidth, sampleHeight)
        scaled.recycle()

        // Convert to grayscale using fast integer luminance: Y = 0.299R + 0.587G + 0.114B
        val gray = IntArray(pixels.size) { i ->
            val r = (pixels[i] shr 16) and 0xFF
            val g = (pixels[i] shr 8) and 0xFF
            val b = pixels[i] and 0xFF
            (r * 77 + g * 150 + b * 29) shr 8
        }

        val prev = previousGray
        previousGray = gray

        if (prev == null) return false

        var changedPixels = 0
        for (i in gray.indices) {
            if (abs(gray[i] - prev[i]) > pixelThreshold) {
                changedPixels++
            }
        }

        return changedPixels.toFloat() / gray.size >= motionThreshold
    }

    fun reset() {
        previousGray = null
    }
}
