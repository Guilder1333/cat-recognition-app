package com.example.catrecognitionsystem.ui

import android.graphics.Bitmap
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner
import com.example.catrecognitionsystem.camera.CameraManager
import com.example.catrecognitionsystem.ml.CatDetectionState
import com.example.catrecognitionsystem.ml.CatDetector
import com.example.catrecognitionsystem.ml.DetectionResult
import com.example.catrecognitionsystem.utils.CatColorAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CatDetectionScreen(
    cameraManager: CameraManager,
    catDetector: CatDetector
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var detectionState by remember { mutableStateOf(CatDetectionState()) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    // Initialize camera when previewView is available
    LaunchedEffect(previewView) {
        previewView?.let { view ->
            try {
                cameraManager.bindCamera(lifecycleOwner, view)
            } catch (e: Exception) {
                detectionState = detectionState.copy(
                    errorMessage = "Camera initialization failed: ${e.message}"
                )
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Title
        Text(
            text = "Cat Recognition System",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        // Camera preview or captured image
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (detectionState.capturedImage != null) {
                // Display captured image with bounding boxes
                ImageWithBoundingBoxes(
                    bitmap = detectionState.capturedImage!!,
                    detections = detectionState.detections
                )
            } else {
                // Display camera preview
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).also { view ->
                            previewView = view
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Loading indicator
            if (detectionState.isProcessing) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }

        // Detection results
        DetectionResultsCard(detectionState)

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    scope.launch {
                        captureAndDetect(
                            cameraManager = cameraManager,
                            catDetector = catDetector,
                            onStateUpdate = { detectionState = it }
                        )
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = !detectionState.isProcessing
            ) {
                Text("Capture & Detect")
            }

            Button(
                onClick = {
                    detectionState = CatDetectionState()
                },
                modifier = Modifier.weight(1f),
                enabled = detectionState.capturedImage != null && !detectionState.isProcessing
            ) {
                Text("Clear")
            }
        }

        // Error message
        if (detectionState.errorMessage != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = detectionState.errorMessage!!,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
fun ImageWithBoundingBoxes(
    bitmap: Bitmap,
    detections: List<DetectionResult>
) {
    var imageSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Captured image",
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { imageSize = it },
            contentScale = ContentScale.Fit
        )

        // Draw bounding boxes
        if (imageSize.width > 0 && imageSize.height > 0) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                // Calculate the actual image display size
                val imageAspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
                val canvasAspectRatio = size.width / size.height

                val displayWidth: Float
                val displayHeight: Float
                val offsetX: Float
                val offsetY: Float

                if (canvasAspectRatio > imageAspectRatio) {
                    // Canvas is wider than image
                    displayHeight = size.height
                    displayWidth = displayHeight * imageAspectRatio
                    offsetX = (size.width - displayWidth) / 2
                    offsetY = 0f
                } else {
                    // Canvas is taller than image
                    displayWidth = size.width
                    displayHeight = displayWidth / imageAspectRatio
                    offsetX = 0f
                    offsetY = (size.height - displayHeight) / 2
                }

                // Draw each detection
                detections.forEach { detection ->
                    // Convert normalized coordinates to pixel coordinates
                    val left = offsetX + detection.boundingBox.left * displayWidth
                    val top = offsetY + detection.boundingBox.top * displayHeight
                    val right = offsetX + detection.boundingBox.right * displayWidth
                    val bottom = offsetY + detection.boundingBox.bottom * displayHeight

                    // Draw rectangle
                    drawRect(
                        color = Color.Green,
                        topLeft = Offset(left, top),
                        size = Size(right - left, bottom - top),
                        style = Stroke(width = 4f)
                    )

                    // Draw label with color
                    val colorName = CatColorAnalyzer.getColorName(detection.color)
                    val label = "$colorName Cat ${(detection.confidence * 100).toInt()}%"
                    val paint = android.graphics.Paint().apply {
                        color = android.graphics.Color.GREEN
                        textSize = 48f
                        isAntiAlias = true
                    }

                    drawContext.canvas.nativeCanvas.drawText(
                        label,
                        left,
                        maxOf(top - 10f, 50f),
                        paint
                    )
                }
            }
        }
    }
}

@Composable
fun DetectionResultsCard(state: CatDetectionState) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Detection Results",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            if (state.capturedImage == null) {
                Text(
                    text = "No image captured yet. Press 'Capture & Detect' to start.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (state.isProcessing) {
                Text(
                    text = "Processing image...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (state.hasCats) {
                Text(
                    text = "Cat detected! Found ${state.detections.size} cat(s)",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.Green
                )

                Spacer(modifier = Modifier.height(8.dp))

                state.detections.forEachIndexed { index, detection ->
                    val colorName = CatColorAnalyzer.getColorName(detection.color)
                    Text(
                        text = "$colorName Cat ${index + 1}: ${(detection.confidence * 100).toInt()}% confidence",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                Text(
                    text = "No cats detected in the image",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private suspend fun captureAndDetect(
    cameraManager: CameraManager,
    catDetector: CatDetector,
    onStateUpdate: (CatDetectionState) -> Unit
) {
    // Set processing state
    onStateUpdate(CatDetectionState(isProcessing = true))

    try {
        // Capture image
        val bitmap = withContext(Dispatchers.Main) {
            cameraManager.captureImage()
        }

        if (bitmap == null) {
            onStateUpdate(
                CatDetectionState(
                    isProcessing = false,
                    errorMessage = "Failed to capture image"
                )
            )
            return
        }

        // Run detection on background thread
        val detections = withContext(Dispatchers.Default) {
            catDetector.detectCats(bitmap)
        }

        // Update state with results
        onStateUpdate(
            CatDetectionState(
                capturedImage = bitmap,
                detections = detections,
                hasCats = detections.isNotEmpty(),
                isProcessing = false,
                errorMessage = null
            )
        )
    } catch (e: Exception) {
        onStateUpdate(
            CatDetectionState(
                isProcessing = false,
                errorMessage = "Error: ${e.message}"
            )
        )
    }
}
