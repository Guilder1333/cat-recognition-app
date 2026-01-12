package com.example.catrecognitionsystem.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CameraManager(private val context: Context) {

    private var imageCapture: ImageCapture? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null

    // Callback for continuous frames
    private var frameCallback: ((Bitmap) -> Unit)? = null

    companion object {
        private const val TAG = "CameraManager"
    }

    /**
     * Binds the camera to the lifecycle with preview and image capture use cases
     * @param enableAnalysis Whether to enable continuous frame analysis
     * @param onFrameCallback Callback for continuous frames (only used if enableAnalysis is true)
     */
    suspend fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        enableAnalysis: Boolean = false,
        onFrameCallback: ((Bitmap) -> Unit)? = null
    ) = suspendCancellableCoroutine<Unit> { continuation ->
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        frameCallback = onFrameCallback

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()

                // Setup preview use case
                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                // Setup image capture use case
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetRotation(previewView.display.rotation)
                    .build()

                // Setup use cases list
                val useCases = mutableListOf<UseCase>(preview, imageCapture!!)

                // Setup image analysis if enabled
                if (enableAnalysis && onFrameCallback != null) {
                    imageAnalyzer = ImageAnalysis.Builder()
                        .setTargetResolution(android.util.Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()
                        .also { analysis ->
                            analysis.setAnalyzer(
                                ContextCompat.getMainExecutor(context)
                            ) { imageProxy ->
                                processImageProxy(imageProxy)
                            }
                        }
                    useCases.add(imageAnalyzer!!)
                    Log.d(TAG, "Image analysis enabled")
                }

                // Select back camera
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                // Unbind any previous use cases
                cameraProvider?.unbindAll()

                // Bind use cases to camera
                camera = cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    *useCases.toTypedArray()
                )

                Log.d(TAG, "Camera bound successfully")
                continuation.resume(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Error binding camera", e)
                continuation.resumeWithException(e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Captures an image and returns it as a Bitmap
     */
    suspend fun captureImage(): Bitmap? = suspendCancellableCoroutine { continuation ->
        val capture = imageCapture ?: run {
            Log.e(TAG, "ImageCapture is null")
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }

        capture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    try {
                        // Convert ImageProxy to Bitmap
                        val bitmap = imageProxyToBitmap(imageProxy)
                        imageProxy.close()

                        Log.d(TAG, "Image captured successfully: ${bitmap?.width}x${bitmap?.height}")
                        continuation.resume(bitmap)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error converting image", e)
                        imageProxy.close()
                        continuation.resumeWithException(e)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Image capture failed", exception)
                    continuation.resumeWithException(exception)
                }
            }
        )
    }

    /**
     * Converts ImageProxy to Bitmap
     */
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        try {
            // Check if this is from ImageAnalysis (RGBA_8888) or ImageCapture (JPEG)
            val format = imageProxy.format

            if (format == android.graphics.ImageFormat.JPEG) {
                // ImageCapture produces JPEG - decode it
                val buffer = imageProxy.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)

                var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

                // Apply rotation if needed
                val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                if (rotationDegrees != 0) {
                    bitmap = rotateBitmap(bitmap, rotationDegrees.toFloat())
                }

                return bitmap
            } else {
                // ImageAnalysis with RGBA_8888 - create bitmap from raw pixels
                val bitmap = Bitmap.createBitmap(
                    imageProxy.width,
                    imageProxy.height,
                    Bitmap.Config.ARGB_8888
                )

                // Copy pixel data from ImageProxy to Bitmap
                val buffer = imageProxy.planes[0].buffer
                buffer.rewind() // Ensure we start from the beginning

                val rowStride = imageProxy.planes[0].rowStride
                val pixelStride = imageProxy.planes[0].pixelStride

                Log.d(TAG, "RGBA conversion - width: ${imageProxy.width}, height: ${imageProxy.height}, rowStride: $rowStride, pixelStride: $pixelStride")

                // Create a buffer for the entire row
                val pixels = IntArray(imageProxy.width * imageProxy.height)
                var pixelIndex = 0

                for (row in 0 until imageProxy.height) {
                    for (col in 0 until imageProxy.width) {
                        val bufferIndex = row * rowStride + col * pixelStride

                        // RGBA format
                        val r = (buffer.get(bufferIndex).toInt() and 0xFF)
                        val g = (buffer.get(bufferIndex + 1).toInt() and 0xFF)
                        val b = (buffer.get(bufferIndex + 2).toInt() and 0xFF)
                        val a = (buffer.get(bufferIndex + 3).toInt() and 0xFF)

                        // Android ARGB format
                        pixels[pixelIndex++] = (a shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }

                bitmap.setPixels(pixels, 0, imageProxy.width, 0, 0, imageProxy.width, imageProxy.height)

                // Apply rotation if needed
                val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                return if (rotationDegrees != 0) {
                    rotateBitmap(bitmap, rotationDegrees.toFloat())
                } else {
                    bitmap
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error converting ImageProxy to Bitmap", e)
            return null
        }
    }

    /**
     * Rotates a bitmap by the specified degrees
     */
    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(degrees)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * Processes ImageProxy and converts to Bitmap for tracking
     */
    private fun processImageProxy(imageProxy: ImageProxy) {
        try {
            Log.d(TAG, "Processing ImageProxy: format=${imageProxy.format}, size=${imageProxy.width}x${imageProxy.height}")
            val bitmap = imageProxyToBitmap(imageProxy)
            imageProxy.close()

            if (bitmap != null) {
                Log.d(TAG, "Converted to bitmap: ${bitmap.width}x${bitmap.height}, invoking callback")
                frameCallback?.invoke(bitmap)
            } else {
                Log.w(TAG, "Failed to convert ImageProxy to Bitmap")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing frame", e)
            imageProxy.close()
        }
    }

    /**
     * Unbinds all camera use cases and releases resources
     */
    fun unbind() {
        try {
            cameraProvider?.unbindAll()
            imageCapture = null
            imageAnalyzer = null
            camera = null
            frameCallback = null
            Log.d(TAG, "Camera unbound")
        } catch (e: Exception) {
            Log.e(TAG, "Error unbinding camera", e)
        }
    }
}
