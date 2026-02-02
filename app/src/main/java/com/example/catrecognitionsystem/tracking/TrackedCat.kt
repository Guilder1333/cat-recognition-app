package com.example.catrecognitionsystem.tracking

import android.graphics.RectF
import androidx.compose.ui.graphics.Color
import com.example.catrecognitionsystem.utils.CatColorAnalyzer
import java.util.UUID

/**
 * Validation status for tracking - indicates confidence that we're still tracking the original cat
 */
enum class ValidationStatus {
    VALID,      // Cat confirmed at tracking location during last validation
    UNCERTAIN,  // 1-2 validation failures - tracking may be drifting
    INVALID     // 3+ validation failures - definitively lost, should stop tracking
}

/**
 * Represents a cat being tracked in the video stream
 *
 * @param id Unique identifier for this tracked cat
 * @param boundingBox Current position in normalized coordinates [0,1]
 * @param confidence Tracking confidence score (0.0 to 1.0)
 * @param catColor Detected color of the cat (may update during tracking for display)
 * @param lockedColor Color locked at initialization - never changes, used for validation
 * @param displayColor UI color for drawing this cat's bounding box
 * @param lastSeenTimestamp When this cat was last successfully tracked
 * @param framesSinceDetection Frames elapsed since last detection verification
 * @param isLost Whether tracking has been lost for this cat
 * @param label Object class label (typically "cat")
 * @param consecutiveValidationFailures Number of consecutive validation failures
 * @param lastValidationTimestamp When last validation check occurred
 * @param validationStatus Current validation state
 */
data class TrackedCat(
    val id: String = UUID.randomUUID().toString(),
    val boundingBox: RectF,
    val confidence: Float,
    val catColor: CatColorAnalyzer.CatColor,
    val lockedColor: CatColorAnalyzer.CatColor = CatColorAnalyzer.CatColor.UNKNOWN,
    val displayColor: Color,
    val lastSeenTimestamp: Long = System.currentTimeMillis(),
    val framesSinceDetection: Int = 0,
    val isLost: Boolean = false,
    val label: String = "cat",
    val consecutiveValidationFailures: Int = 0,
    val lastValidationTimestamp: Long = System.currentTimeMillis(),
    val validationStatus: ValidationStatus = ValidationStatus.VALID
)

/**
 * Provides unique colors for each tracked cat's bounding box
 */
object CatDisplayColors {
    private val colors = listOf(
        Color.Green,
        Color.Cyan,
        Color.Yellow,
        Color.Magenta,
        Color(0xFFFF6B35) // Orange
    )

    /**
     * Returns a unique color for the given cat index
     * Colors wrap around after 5 cats
     */
    fun getColorForIndex(index: Int): Color {
        return colors[index % colors.size]
    }
}
