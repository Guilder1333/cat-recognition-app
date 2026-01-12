package com.example.catrecognitionsystem.ml

import android.graphics.Bitmap
import android.graphics.RectF
import com.example.catrecognitionsystem.utils.CatColorAnalyzer

data class DetectionResult(
    val boundingBox: RectF,      // Normalized coordinates [0, 1]
    val label: String,
    val confidence: Float,
    val color: CatColorAnalyzer.CatColor = CatColorAnalyzer.CatColor.UNKNOWN
)

data class CatDetectionState(
    val capturedImage: Bitmap? = null,
    val detections: List<DetectionResult> = emptyList(),
    val hasCats: Boolean = false,
    val isProcessing: Boolean = false,
    val errorMessage: String? = null
)
