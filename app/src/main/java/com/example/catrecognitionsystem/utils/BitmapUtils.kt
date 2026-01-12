package com.example.catrecognitionsystem.utils

import android.graphics.Bitmap
import android.graphics.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder

object BitmapUtils {

    /**
     * Scales a bitmap to fit within maxSize while maintaining aspect ratio
     */
    fun scaleBitmap(bitmap: Bitmap, maxSize: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        if (width <= maxSize && height <= maxSize) {
            return bitmap
        }

        val scale = minOf(maxSize.toFloat() / width, maxSize.toFloat() / height)
        val matrix = Matrix()
        matrix.postScale(scale, scale)

        return Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true)
    }

    /**
     * Preprocesses a bitmap for TFLite model input
     * Resizes to 300x300 and converts to ByteBuffer with normalized float values
     */
    fun preprocessBitmap(bitmap: Bitmap, inputSize: Int = 300): ByteBuffer {
        // Resize to model input size
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)

        // Allocate ByteBuffer (4 bytes per float)
        val byteBuffer = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3)
        byteBuffer.order(ByteOrder.nativeOrder())

        // Convert pixels to buffer
        val intValues = IntArray(inputSize * inputSize)
        resizedBitmap.getPixels(intValues, 0, inputSize, 0, 0, inputSize, inputSize)

        // Convert each pixel to float and normalize [0, 255] to [0, 1]
        for (pixelValue in intValues) {
            // Extract RGB channels (model expects RGB, not BGR)
            val r = ((pixelValue shr 16) and 0xFF) / 255.0f
            val g = ((pixelValue shr 8) and 0xFF) / 255.0f
            val b = (pixelValue and 0xFF) / 255.0f

            byteBuffer.putFloat(r)
            byteBuffer.putFloat(g)
            byteBuffer.putFloat(b)
        }

        return byteBuffer
    }

    /**
     * Preprocesses a bitmap for quantized TFLite model input
     * Resizes to 300x300 and converts to ByteBuffer with uint8 values
     */
    fun preprocessBitmapQuantized(bitmap: Bitmap, inputSize: Int = 300): ByteBuffer {
        // Resize to model input size
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)

        // Allocate ByteBuffer (1 byte per pixel channel for uint8)
        val byteBuffer = ByteBuffer.allocateDirect(inputSize * inputSize * 3)
        byteBuffer.order(ByteOrder.nativeOrder())

        // Convert pixels to buffer
        val intValues = IntArray(inputSize * inputSize)
        resizedBitmap.getPixels(intValues, 0, inputSize, 0, 0, inputSize, inputSize)

        // Convert each pixel to uint8 (no normalization needed for quantized models)
        for (pixelValue in intValues) {
            // Extract RGB channels
            val r = ((pixelValue shr 16) and 0xFF).toByte()
            val g = ((pixelValue shr 8) and 0xFF).toByte()
            val b = (pixelValue and 0xFF).toByte()

            byteBuffer.put(r)
            byteBuffer.put(g)
            byteBuffer.put(b)
        }

        return byteBuffer
    }

    /**
     * Rotates a bitmap by the specified degrees
     */
    fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(degrees)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * Flips a bitmap horizontally (mirror)
     */
    fun flipBitmap(bitmap: Bitmap): Bitmap {
        val matrix = Matrix()
        matrix.preScale(-1.0f, 1.0f)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
