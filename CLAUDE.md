# CLAUDE.md - AI Assistant Guide

This document helps AI assistants understand the Cat Recognition App project structure and key implementation details.

## Project Overview

Android application that detects and tracks cats in real-time using TensorFlow Lite for detection and OpenCV for tracking. Built with Kotlin, Jetpack Compose, and CameraX.

**Key Features:**
- Real-time cat detection using TFLite SSD MobileNet
- Continuous video tracking with OpenCV TrackerMIL
- Color-based filtering (Black, Tabby, or Any)
- Hybrid detection/tracking for performance
- Live camera overlay with bounding boxes

## Architecture

### High-Level Flow
1. User presses "Start Tracking"
2. ImageAnalysis captures first frame from camera
3. TFLite model detects cats in frame
4. OpenCV TrackerMIL initializes on most confident cat
5. Continuous tracking at ~30 FPS
6. Periodic re-detection every 30 frames to correct drift

### Key Components

```
app/src/main/java/com/example/catrecognitionsystem/
├── camera/
│   └── CameraManager.kt          # CameraX integration, frame processing
├── ml/
│   ├── CatDetector.kt             # TFLite inference wrapper
│   └── DetectionResult.kt         # Detection data classes
├── tracking/
│   ├── MultiCatTrackingManager.kt # Orchestrates tracking lifecycle
│   ├── CatTracker.kt              # OpenCV TrackerMIL wrapper
│   ├── TrackedCat.kt              # Tracking state data class
│   └── TrackingMode.kt            # State machine states
├── ui/
│   └── CatDetectionScreen.kt     # Main Compose UI with tracking overlay
├── utils/
│   └── CatColorAnalyzer.kt       # HSV-based color classification
└── MainActivity.kt                # OpenCV initialization, entry point
```

## Critical Implementation Details

### 1. Frame Processing (CameraManager.kt)

**IMPORTANT:** `imageProxyToBitmap()` handles TWO different formats:
- **JPEG** (from ImageCapture): Standard JPEG decoding
- **RGBA_8888** (from ImageAnalysis): Raw pixel data that must be manually copied

```kotlin
// Check format BEFORE processing
if (format == android.graphics.ImageFormat.JPEG) {
    // Use BitmapFactory.decodeByteArray()
} else {
    // Manual pixel copying with rowStride/pixelStride
}
```

**Common Bug:** Trying to decode RGBA data as JPEG will fail silently and return null, causing tracking to hang.

### 2. Two-Phase Tracking Initialization (CatDetectionScreen.kt:59-147)

**Problem:** ImageCapture and ImageAnalysis provide frames from different times, causing tracker to initialize on wrong frame.

**Solution:** Use ImageAnalysis for BOTH initial detection and tracking:

```kotlin
Phase 1: isWaitingForFirstFrame = true
  → Enable ImageAnalysis
  → Capture first frame in callback
  → Store in firstFrameForTracking

Phase 2: LaunchedEffect(firstFrameForTracking)
  → Run detection on that frame
  → Initialize tracker with same frame
  → Switch to isTrackingActive = true
```

**Critical:** This ensures frame continuity between detection and tracking.

### 3. OpenCV Integration

**Location:** `opencv/` directory (NOT in git, downloaded via setup script)

**Setup:** OpenCV 4.8.0 Android SDK is 1.3GB and excluded from repository. New developers run:
- Windows: `setup-opencv.bat`
- Linux/Mac: `./setup-opencv.sh`

**Tracker:** Uses `TrackerMIL` (not TrackerKCF which isn't available in this version)

**Memory Management:** Always release Mat objects explicitly:
```kotlin
val mat = Mat()
try {
    // Use mat
} finally {
    mat.release()
}
```

### 4. Single Cat Tracking with Color Filtering

Originally designed for multi-cat tracking, but modified to track only the most confident cat of a specific color:

```kotlin
// In MultiCatTrackingManager.kt:initializeTracking()
val filteredDetections = filterDetectionsByColor(detections)
val bestDetection = filteredDetections.maxByOrNull { it.confidence }!!
// Creates tracker for this one cat only
```

Color filter set via `trackingManager.setTargetColor(selectedCatColor)` before tracking starts.

### 5. Detection Model (detect.tflite)

**Location:** `app/src/main/assets/detect.tflite`

**Type:** SSD MobileNet trained on COCO dataset

**Classes:**
- Index 16 or 17 = "cat" (flexible matching with ±1 tolerance)
- Confidence threshold: 0.1 (lowered for better detection)

**Input:** 300x300 RGB image

**Output:** Bounding boxes, class indices, scores

## File Locations

### Configuration
- `app/build.gradle.kts` - Dependencies, SDK versions, NDK filters
- `settings.gradle.kts` - Module includes (opencv)
- `opencv/build.gradle` - OpenCV module config (Java 11, BuildConfig, AIDL)

### Assets
- `app/src/main/assets/detect.tflite` - Detection model
- `app/src/main/assets/labelmap.txt` - Class labels (if present)

### Setup Scripts
- `setup-opencv.bat` / `setup-opencv.sh` - Downloads OpenCV SDK

## Build Instructions

```bash
# 1. Download OpenCV (first time only)
./setup-opencv.sh  # or setup-opencv.bat on Windows

# 2. Build
./gradlew assembleDebug

# 3. Install on device
./gradlew installDebug

# 4. View logs
adb logcat -s CatDetectionScreen CameraManager MultiCatTrackingManager CatDetector
```

## Common Issues & Solutions

### Issue: Build fails with "OpenCV not found"
**Solution:** Run setup script: `./setup-opencv.sh` or `setup-opencv.bat`

### Issue: Tracking hangs with loading icon
**Cause:** Frame processing failure in `imageProxyToBitmap()`
**Check:** Logs should show "Processing ImageProxy" and "Converted to bitmap"
**Solution:** Ensure RGBA format is handled correctly (not decoded as JPEG)

### Issue: Tracker drifts off cat
**Cause:** Normal tracking drift, should be corrected by periodic re-detection
**Check:** Logs should show "Running periodic detection" every 30 frames
**Solution:** Lower `reDetectionFrameInterval` if drift is too severe

### Issue: No cats detected
**Cause:** Detection threshold too high, poor lighting, or cat not in COCO classes
**Check:** Logs show "Total cats detected: N" with N > 0
**Solutions:**
- Lower confidence threshold in CatDetector.kt
- Improve lighting conditions
- Ensure cat is clearly visible and not occluded

### Issue: OpenCV TrackerKCF not found
**Cause:** TrackerKCF not available in OpenCV 4.8.0 Java bindings
**Solution:** Use TrackerMIL instead (already implemented)

### Issue: JVM target incompatibility
**Cause:** OpenCV module and app module have different Java versions
**Solution:** Both must use Java 11 (already configured)

## Code Conventions

### Logging
- Use `android.util.Log.d()` for debug info
- Tag format: Class name (e.g., "CatDetectionScreen")
- Log key lifecycle events: frame capture, detection results, tracker state changes

### Coroutines
- UI updates: `withContext(Dispatchers.Main)`
- Heavy processing: `withContext(Dispatchers.Default)`
- Camera operations: Already main thread (CameraX requirement)

### State Management
- Use Compose `remember { mutableStateOf() }` for UI state
- Use `LaunchedEffect` for side effects (camera binding, frame processing)
- Keep state immutable with `copy()` for updates

### Coordinates
- **ALWAYS use normalized coordinates [0, 1]** for bounding boxes
- Convert to pixels only when drawing on canvas
- Format: `RectF(left, top, right, bottom)` where all values are 0-1

## Testing

### Manual Testing Checklist
- [ ] Camera preview appears on launch
- [ ] "Start Tracking" button triggers detection
- [ ] Loading indicator appears and disappears
- [ ] Bounding box appears around detected cat
- [ ] Bounding box follows cat smoothly as it moves
- [ ] Color filter works (Black, Tabby, Any)
- [ ] "Stop Tracking" clears tracking and returns to idle
- [ ] Periodic re-detection maintains accuracy (check logs)
- [ ] Lost tracking shown in red after cat leaves frame
- [ ] App maintains 25+ FPS during tracking

### Debug Logs to Check
```bash
# Start tracking flow
CatDetectionScreen: Starting tracking - waiting for first frame
CatDetectionScreen: LaunchedEffect triggered - previewView: true, isWaiting: true
CameraManager: Binding camera with analysis: true
CameraManager: Image analysis enabled
CameraManager: Processing ImageProxy: format=1, size=640x480
CameraManager: Converted to bitmap: 640x480, invoking callback
CatDetectionScreen: Captured first frame for tracking: 640x480
CatDetectionScreen: Processing first frame for tracking initialization
CatDetector: Total cats detected: N
MultiCatTrackingManager: Filtered N detections to M matching color
CatDetectionScreen: Tracking initialized successfully

# Ongoing tracking
CatDetectionScreen: Processing tracking frame: 640x480, tracked cats: 1
MultiCatTrackingManager: Running periodic detection (every 30 frames)
```

## Performance Targets

- **FPS:** 25-30 FPS during active tracking
- **Detection latency:** <100ms per frame
- **Tracking latency:** <50ms per frame (OpenCV only)
- **Memory:** Stable over 10+ minutes, no leaks
- **Battery:** <15% drain per 5 minutes continuous use

## Dependencies

### Core
- Kotlin 1.9+
- Android Gradle Plugin 8.13
- compileSdk: 34, minSdk: 31, targetSdk: 34

### Libraries
- AndroidX Core, Lifecycle, Compose BOM
- CameraX: camera-core, camera2, lifecycle, view
- TensorFlow Lite: lite, support, metadata
- OpenCV 4.8.0 Android SDK (local module)
- Coil Compose (image loading)

### NDK
- OpenCV requires native libraries for: armeabi-v7a, arm64-v8a, x86, x86_64
- CMake builds `opencv_jni_shared` from OpenCV SDK

## Known Limitations

1. **Single cat tracking only** - Tracks most confident cat of selected color
2. **COCO dataset limitation** - Only detects cats that look similar to COCO training data
3. **Lighting sensitive** - Poor lighting reduces detection accuracy
4. **Tracking drift** - OpenCV tracking not perfect, requires periodic re-detection
5. **No persistence** - Tracking state lost when app is closed
6. **No recording** - Cannot save tracked video, only live tracking

## Future Improvements (If Requested)

1. Multi-cat tracking (revert to original design)
2. Custom TFLite model trained specifically for cats
3. Tracking history/trajectory visualization
4. Video recording with tracking overlay
5. Persistent cat identification across sessions
6. Performance optimization (reduce FPS for battery life)
7. Kalman filter for smoother tracking
8. Support for front camera

## Git Workflow

### Ignored Files
- `/opencv/` - 1.3GB, downloaded via setup script
- `/third_party/` - Future external dependencies
- `/build/`, `/.gradle/`, `.cxx/` - Build artifacts
- `/tmpclaude-*` - Temporary files

### Important Files to Commit
- All `.kt` source files
- `build.gradle.kts`, `settings.gradle.kts`
- `setup-opencv.bat`, `setup-opencv.sh`
- `README.md`, `CLAUDE.md`
- `.gitignore`

### Important Files NOT to Commit
- `opencv/` directory (too large)
- Build outputs, IDE files (`.idea/`, except run configs)
- Local properties (`local.properties`)

## Quick Reference: Common Tasks

### Add new detection class
1. Modify `CatDetector.kt:detectCats()` class index check
2. Update confidence threshold if needed
3. Test with diverse images

### Change tracking algorithm
1. Modify `CatTracker.kt:init()` - change from `TrackerMIL.create()`
2. Options: TrackerMIL, TrackerCSRT (if available)
3. Note: Not all OpenCV trackers available in Java bindings

### Adjust tracking frequency
1. Change `reDetectionFrameInterval` in `MultiCatTrackingManager` constructor
2. Default: 30 frames (~1 second at 30 FPS)
3. Lower = more accurate but slower, Higher = faster but more drift

### Add new cat color
1. Add enum value to `CatColorAnalyzer.CatColor`
2. Implement HSV range in `analyzeColor()`
3. Add FilterChip in `CatDetectionScreen.kt`
4. Update `getColorName()` helper

### Change camera resolution
1. Modify `ImageAnalysis.Builder().setTargetResolution()` in `CameraManager.kt`
2. Current: 640x480 (balance of performance/quality)
3. Higher = better quality but slower, Lower = faster but less detail

## Contact & Resources

- OpenCV Documentation: https://docs.opencv.org/4.8.0/
- TensorFlow Lite: https://www.tensorflow.org/lite
- CameraX Guide: https://developer.android.com/training/camerax
- Jetpack Compose: https://developer.android.com/jetpack/compose

---

*Last Updated: 2026-01-12*
*Project Version: 1.0*
*OpenCV Version: 4.8.0*
*TFLite Model: SSD MobileNet (COCO)*
