package com.example.catrecognitionsystem.utils

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.util.Log
import androidx.core.graphics.get
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

object CatColorAnalyzer {

    private const val TAG = "CatColorAnalyzer"

    // GrabCut mask values (matches OpenCV constants)
    private const val GC_BGD = 0
    private const val GC_FGD = 1
    private const val GC_PR_BGD = 2
    private const val GC_PR_FGD = 3

    /** Last GrabCut mask rendered as a Bitmap for debug display. Updated on each detection. */
    @Volatile var debugMaskBitmap: Bitmap? = null
        private set

    enum class CatColor {
        BLACK,
        TABBY,
        UNKNOWN
    }

    /**
     * Analyzes the color of a detected cat using GrabCut segmentation to isolate
     * foreground (cat) pixels before classification. Falls back to rectangular
     * sampling if GrabCut fails or finds too few foreground pixels.
     *
     * @param bitmap Full camera frame
     * @param boundingBox Normalized coordinates [0,1] of the cat region
     * @return CatColor classification
     */
    fun analyzeCatColor(bitmap: Bitmap, boundingBox: RectF): CatColor {
        val left   = (boundingBox.left   * bitmap.width ).toInt().coerceIn(0, bitmap.width  - 1)
        val top    = (boundingBox.top    * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val right  = (boundingBox.right  * bitmap.width ).toInt().coerceIn(0, bitmap.width)
        val bottom = (boundingBox.bottom * bitmap.height).toInt().coerceIn(0, bitmap.height)

        val width  = right - left
        val height = bottom - top

        if (width <= 10 || height <= 10) {
            Log.w(TAG, "Bounding box too small ($width×$height)")
            return CatColor.UNKNOWN
        }

        // Read pixel data for the box once — reused for both GrabCut and fallback
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, left, top, width, height)

        return try {
            analyzeWithGrabCut(bitmap, pixels, left, top, width, height)
        } catch (e: Exception) {
            Log.e(TAG, "GrabCut failed, falling back to rectangular sampling", e)
            classifyFromPixels(pixels)
        }
    }

    /**
     * Runs GrabCut on the cropped bounding box region, then classifies color
     * using only the foreground (cat) pixels identified by the mask.
     */
    private fun analyzeWithGrabCut(
        bitmap: Bitmap,
        pixels: IntArray,
        left: Int, top: Int,
        width: Int, height: Int
    ): CatColor {
        // Convert the cropped region to an RGB Mat for GrabCut
        val cropped = Bitmap.createBitmap(bitmap, left, top, width, height)
        val rgbaMat = Mat()
        Utils.bitmapToMat(cropped, rgbaMat)
        cropped.recycle()
        val rgbMat = Mat()
        Imgproc.cvtColor(rgbaMat, rgbMat, Imgproc.COLOR_RGBA2RGB)
        rgbaMat.release()

        val mask     = Mat()
        val bgModel  = Mat()
        val fgModel  = Mat()

        try {
            // Enhance local contrast before GrabCut so the cat/floor boundary is visible
            // even when they share similar average colors (e.g. black cat on dark floor,
            // tabby cat on brown floor). CLAHE operates on the L channel in LAB space so
            // it boosts luminance contrast without distorting hue.
            val enhancedMat = applyClahe(rgbMat)

            // Leave a small margin so GrabCut rect doesn't touch the exact image edge
            val margin = max(2, min(width, height) / 10)
            val rect   = Rect(margin, margin, width - 2 * margin, height - 2 * margin)

            Imgproc.grabCut(enhancedMat, mask, rect, bgModel, fgModel, 5, Imgproc.GC_INIT_WITH_RECT)
            enhancedMat.release()

            // Tally color stats over foreground pixels only
            var darkPixels        = 0
            var brownOrangePixels = 0
            var totalForeground   = 0
            val hsv = FloatArray(3)
            val rowBuf = ByteArray(mask.cols())

            for (y in 0 until mask.rows()) {
                mask.get(y, 0, rowBuf)
                for (x in 0 until mask.cols()) {
                    val maskVal = rowBuf[x].toInt() and 0xFF
                    if (maskVal == GC_FGD || maskVal == GC_PR_FGD) {
                        totalForeground++
                        val pixel = pixels[y * width + x]
                        val r = Color.red(pixel)
                        val g = Color.green(pixel)
                        val b = Color.blue(pixel)
                        if ((r + g + b) / 3 < 60) darkPixels++
                        Color.RGBToHSV(r, g, b, hsv)
                        if (hsv[0] in 10f..70f && hsv[1] > 0.15f && hsv[2] > 0.2f) brownOrangePixels++
                    }
                }
            }

            Log.d(TAG, "GrabCut: foreground=$totalForeground / ${width * height} pixels")

            // Build a debug bitmap: white=definite FG, light-gray=probable FG,
            // dark-gray=probable BG, black=definite BG
            val maskPixels = IntArray(width * height)
            val debugRowBuf = ByteArray(mask.cols())
            for (y in 0 until mask.rows()) {
                mask.get(y, 0, debugRowBuf)
                for (x in 0 until mask.cols()) {
                    maskPixels[y * width + x] = when (debugRowBuf[x].toInt() and 0xFF) {
                        GC_FGD    -> android.graphics.Color.WHITE
                        GC_PR_FGD -> android.graphics.Color.LTGRAY
                        GC_PR_BGD -> android.graphics.Color.DKGRAY
                        else      -> android.graphics.Color.BLACK  // GC_BGD
                    }
                }
            }
            val maskBmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            maskBmp.setPixels(maskPixels, 0, width, 0, 0, width, height)
            debugMaskBitmap = maskBmp

            if (totalForeground < 50) {
                Log.w(TAG, "GrabCut foreground too sparse ($totalForeground px), falling back to rectangular sampling")
                return classifyFromPixels(pixels)
            }

            val darkRatio        = darkPixels.toFloat()        / totalForeground
            val brownOrangeRatio = brownOrangePixels.toFloat() / totalForeground

            Log.d(TAG, "GrabCut color — Dark: ${(darkRatio * 100).toInt()}%, " +
                    "BrownOrange: ${(brownOrangeRatio * 100).toInt()}%")

            return classify(darkRatio, brownOrangeRatio)
        } finally {
            rgbMat.release()
            mask.release()
            bgModel.release()
            fgModel.release()
        }
    }

    /**
     * Fallback: classifies color from all pixels in the supplied array
     * (the full bounding box crop, no masking).
     */
    /**
     * Applies CLAHE (Contrast Limited Adaptive Histogram Equalization) to the L channel
     * of the LAB representation of [src] (RGB input), returning a new contrast-enhanced
     * RGB Mat. The caller is responsible for releasing the returned Mat.
     *
     * This makes the cat/background boundary detectable even when the cat and floor share
     * similar average colors, because CLAHE amplifies local texture and edge contrast.
     */
    private fun applyClahe(src: Mat): Mat {
        val labMat = Mat()
        Imgproc.cvtColor(src, labMat, Imgproc.COLOR_RGB2Lab)

        val channels = mutableListOf<Mat>()
        Core.split(labMat, channels)
        labMat.release()

        val clahe = Imgproc.createCLAHE(3.0, Size(8.0, 8.0))
        val lEnhanced = Mat()
        clahe.apply(channels[0], lEnhanced)
        channels[0].release()
        channels[0] = lEnhanced

        val labEnhanced = Mat()
        Core.merge(channels, labEnhanced)
        channels.forEach { it.release() }

        val rgbEnhanced = Mat()
        Imgproc.cvtColor(labEnhanced, rgbEnhanced, Imgproc.COLOR_Lab2RGB)
        labEnhanced.release()

        return rgbEnhanced
    }

    private fun classifyFromPixels(pixels: IntArray): CatColor {
        if (pixels.isEmpty()) return CatColor.UNKNOWN

        var darkPixels        = 0
        var brownOrangePixels = 0
        val hsv = FloatArray(3)

        for (pixel in pixels) {
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)
            if ((r + g + b) / 3 < 60) darkPixels++
            Color.RGBToHSV(r, g, b, hsv)
            if (hsv[0] in 10f..70f && hsv[1] > 0.15f && hsv[2] > 0.2f) brownOrangePixels++
        }

        val darkRatio        = darkPixels.toFloat()        / pixels.size
        val brownOrangeRatio = brownOrangePixels.toFloat() / pixels.size

        Log.d(TAG, "Rectangular fallback — Dark: ${(darkRatio * 100).toInt()}%, " +
                "BrownOrange: ${(brownOrangeRatio * 100).toInt()}%")

        return classify(darkRatio, brownOrangeRatio)
    }

    /**
     * Shared classification rules applied to whichever pixel set was sampled.
     */
    private fun classify(darkRatio: Float, brownOrangeRatio: Float): CatColor = when {
        darkRatio > 0.6f && brownOrangeRatio < 0.15f -> CatColor.BLACK
        brownOrangeRatio > 0.20f                     -> CatColor.TABBY
        brownOrangeRatio > 0.12f && darkRatio < 0.5f -> CatColor.TABBY
        darkRatio > 0.5f && brownOrangeRatio < 0.10f -> CatColor.BLACK
        darkRatio > 0.5f && brownOrangeRatio > 0.15f -> CatColor.TABBY
        darkRatio > 0.5f                             -> CatColor.BLACK
        else                                         -> CatColor.UNKNOWN
    }

    /**
     * Returns a human-readable color name.
     */
    fun getColorName(color: CatColor): String = when (color) {
        CatColor.BLACK   -> "Black"
        CatColor.TABBY   -> "Tabby"
        CatColor.UNKNOWN -> "Unknown"
    }
}
