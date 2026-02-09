# CLAUDE.md - AI Assistant Guide

This document helps AI assistants understand the Cat Recognition App project structure and key implementation details.

## Project Overview

Android application that detects and tracks cats using TensorFlow Lite for detection and OpenCV for tracking. Goal: automatically open a door when a cat is detected nearby (no collars). Built with Kotlin, Jetpack Compose, and CameraX.

**Key Features:**
- Cat detection using TFLite SSD MobileNet v1 (two-pass: original + flipped for better recall)
- Continuous video tracking with OpenCV TrackerMIL
- Color-based filtering and classification (Black, Tabby, or Any)
- Periodic validation against fresh detections with auto-stop on loss
- Live camera overlay with bounding boxes and status indicators
- Debug info card on screen showing raw model output (useful during tuning)

## Architecture

### High-Level Flow
1. User presses "Start Tracking"
2. ImageAnalysis captures first frame from camera
3. TFLite model runs two-pass detection (original + flipped) on frame
4. OpenCV TrackerMIL initializes on most confident cat matching selected color
5. Continuous tracking at ~30 FPS via OpenCV
6. Periodic validation every ~2.5s: re-runs detection, checks IoU + color match against tracked position
7. Auto-stops if 3 consecutive validations fail (cat no longer at tracked location)

### Key Components

```
app/src/main/java/com/example/catrecognitionsystem/
├── camera/
│   └── CameraManager.kt          # CameraX integration, frame processing
├── ml/
│   ├── CatDetector.kt             # TFLite inference wrapper (two-pass: original + flipped)
│   └── DetectionResult.kt         # Detection data classes + CatDetectionState
├── tracking/
│   ├── MultiCatTrackingManager.kt # Orchestrates tracking lifecycle and validation
│   ├── CatTracker.kt              # OpenCV TrackerMIL wrapper
│   ├── TrackedCat.kt              # Tracking state data class + ValidationStatus + CatDisplayColors
│   └── TrackingState.kt           # TrackingMode sealed class (Idle, InitialDetection, ActiveTracking, TrackingLost)
├── ui/
│   ├── CatDetectionScreen.kt     # Main Compose UI with tracking overlay
│   └── theme/                     # Material3 theme (Color.kt, Theme.kt, Type.kt)
├── utils/
│   ├── CatColorAnalyzer.kt       # HSV-based color classification (BLACK, TABBY, UNKNOWN)
│   └── BitmapUtils.kt            # Bitmap scaling, preprocessing (float + quantized), flipping
└── MainActivity.kt                # OpenCV initialization, permission handling, entry point
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

### 2. Two-Phase Tracking Initialization (CatDetectionScreen.kt:60-170)

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

**Two color fields on TrackedCat — do not confuse:**
- `lockedColor` — set once at initialization, never changes. Used by `mergeDetectionsWithTracking()` to filter validation detections (only matches cats of the same color).
- `catColor` — updated from the fresh detection each time validation passes. Used for display only.

### 5. Validation and Auto-Stop

During active tracking, `mergeDetectionsWithTracking()` runs periodically (controlled by `validationIntervalMs`, default 2.5s). It checks whether a detection of the correct color and overlapping IoU exists at the tracked position.

- **Pass:** resets `consecutiveValidationFailures` to 0, status → `VALID`
- **Fail:** increments `consecutiveValidationFailures`, status → `UNCERTAIN` (1-2 failures) or `INVALID` (3+ failures)
- **INVALID** triggers `shouldStopTracking = true`, which auto-stops tracking in `CatDetectionScreen`

Thresholds in `MultiCatTrackingManager`:
- `VALIDATION_IOU_THRESHOLD = 0.2` — minimum IoU to count as a match during validation
- `IOU_THRESHOLD = 0.3` — general matching
- `VALIDATION_FAILURE_THRESHOLD = 3` — consecutive failures before auto-stop

### 6. Detection Model (detect.tflite)

**Location:** `app/src/main/assets/detect.tflite`

**Type:** SSD MobileNet v1, quantized (uint8), trained on COCO dataset

**Classes:**
- Model outputs class 16 for cat; labelmap has cat at index 17 (off-by-one due to background class at index 0)
- `parseDetections()` matches with ±1 tolerance around `catClassIndex` to handle this
- Confidence threshold: 0.1

**Input:** 300x300 uint8 RGB image (use `BitmapUtils.preprocessBitmapQuantized`)

**Output:** 4 tensors via `runForMultipleInputsOutputs`:
- Index 0: locations `[1, 10, 4]` — bounding boxes as `[top, left, bottom, right]` normalized [0,1]
- Index 1: classes `[1, 10]` — class indices (float)
- Index 2: scores `[1, 10]` — confidence scores (float)
- Index 3: count `[1]` — number of detections (float)

**Two-pass strategy:** Detection runs twice per frame — once on the original image, once on a horizontally flipped image. Results are merged and deduplicated using IoU > 0.5, keeping the higher-confidence detection. This improves recall for cats at unusual angles but roughly doubles inference time.

## File Locations

### Configuration
- `app/build.gradle.kts` - Dependencies, SDK versions, NDK filters
- `settings.gradle.kts` - Module includes (opencv)
- `opencv/build.gradle` - OpenCV module config (Java 11, BuildConfig, AIDL)

### Assets
- `app/src/main/assets/detect.tflite` - Current detection model (SSD MobileNet v1, quantized)
- `app/src/main/assets/efficientdet_lite0.tflite` - Next model (MediaPipe format, requires Tasks API — see Roadmap Phase 1)
- `app/src/main/assets/labelmap.txt` - COCO class labels (91 entries, includes background placeholders as `???`)

### Setup Scripts
- `setup-opencv.bat` / `setup-opencv.sh` - Downloads OpenCV SDK
- `setup-efficientdet.bat` - Downloads EfficientDet-Lite0 model from MediaPipe model zoo

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

### Issue: SSD MobileNet detects cats as class 16 instead of 17
**Cause:** The model outputs 0-based class indices without a background class, but `labelmap.txt` includes a background placeholder (`???`) at index 0, shifting everything by +1. Cat is at labelmap index 17, but model outputs 16.
**Solution:** Match with ±1 tolerance around `catClassIndex` in `CatDetector.kt:parseDetections()`

### Issue: MediaPipe EfficientDet model fails to load with raw Interpreter
**Cause:** Models from `storage.googleapis.com/mediapipe-models/` are packaged with MediaPipe metadata. The standard TFLite `Interpreter` cannot parse them — `interpreter` stays null.
**Solution:** Use MediaPipe Tasks `ObjectDetector` API instead of raw `Interpreter`. See Roadmap Phase 1.

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

- **FPS:** 25-30 FPS during active tracking (OpenCV tracker runs every frame; detection does not)
- **Detection latency:** ~60-120ms per invocation (two-pass doubles single-pass cost); detection runs periodically, not every frame
- **Tracking latency:** <50ms per frame (OpenCV TrackerMIL only)
- **Memory:** Stable over 10+ minutes, no leaks
- **Battery:** <15% drain per 5 minutes continuous use
- **Target latency budget:** up to ~1 second per detection is acceptable for the door-opening use case

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

## Current Issues & Alternative Approaches to Consider

### Issue: OpenCV TrackerMIL Drift with Color-Based Validation

**Problem (2026-02-07):**
- TrackerMIL drifts significantly when cat moves or leaves frame
- When tracking tabby cat near similar-colored floor, tracker drifts to floor
- Color-based validation passes incorrectly because floor HSV values match tabby cat
- Validation accepts low-confidence floor detections as valid cat tracking
- Tracker continues "tracking" the floor even after cat has left the camera view

**Attempted Solutions:**
1. ✗ **Confidence threshold during validation** - Added 0.35 threshold but didn't resolve floor tracking issue
2. ✗ **Switch to TrackerCSRT** - Not available in standard OpenCV 4.8.0 Android SDK (requires opencv_contrib modules)
   - TrackerCSRT is in `org.opencv.tracking` package, not available in base OpenCV build
   - Would need to either build from source with contrib or use third-party dependency like `com.quickbirdstudios:opencv-contrib`

**Alternative: Sparse Optical Flow (Point Tracking) instead of TrackerMIL**

Replace rectangle-based TrackerMIL with Lucas-Kanade sparse optical flow tracking individual feature points on the cat. All required functions are in base OpenCV 4.8.0 (no contrib needed).

**How it would work:**
1. Detection finds cat → `Imgproc.goodFeaturesToTrack()` extracts ~20-30 Shi-Tomasi corners inside the bounding box
2. Each frame: `Video.calcOpticalFlowPyrLK()` tracks those points → derive bounding box from the point cloud
3. Points that diverge from the cluster (e.g., drift onto floor) are detected as outliers and discarded
4. When too many points are lost (below a minimum threshold), trigger re-detection

**Advantages over TrackerMIL:**
- No silent drift — each point has a tracking status/error; failed points are explicitly detected
- Natural outlier rejection — a point that jumps to floor diverges from the cluster and gets filtered
- Self-aware quality signal — number of remaining good points indicates tracking health
- Faster — LK optical flow is lighter than TrackerMIL

**Concern: black cats** — uniform dark fur has very few corners/edges. Feature detection may only find silhouette points (ears, outline against background), which are fewer and more fragile. Mitigations:
- Lower `qualityLevel` in `goodFeaturesToTrack()` to accept weaker features
- Silhouette/edge points (ears, head outline) are still trackable, just fewer
- Fall back to re-detection sooner when point count drops below minimum
- Tabby cats should track very well — fur patterns produce abundant texture features

### Issue: Rectangular Color Sampling Causes Misclassification

**Problem (2026-02-09):**
- `CatColorAnalyzer` samples pixels from the inner 60% of the bounding box — still a rectangle
- Cats are not rectangular: corners of the sampling region contain floor/background pixels
- Black cat near tabby-colored floor gets misclassified as tabby because floor pixels in the corners have brown/orange HSV values that push `brownOrangeRatio` above the tabby threshold
- The bottom of the bounding box is especially problematic — cat's feet/belly area includes floor

**Current implementation:** `CatColorAnalyzer.kt` — samples ~400 pixels on a grid from inner 60% rect, classifies by dark pixel ratio (>60% → black) and brown/orange ratio (>20% → tabby).

**Proposed solutions (in order of implementation priority):**

1. **Elliptical mask + top-biased center (recommended first step)**
   - Replace rectangular sampling with an ellipse inscribed in the bounding box — eliminates corner background pixels
   - Shift ellipse center up by ~15% to favor cat's back/head over floor-adjacent belly/feet
   - Check: `((x - cx)/rx)^2 + ((y - cy)/ry)^2 <= 1.0` for each sample point
   - Combine with **median-based classification** instead of mean/ratio — median is resistant to a few remaining outlier background pixels
   - Minimal code change, zero extra cost

2. **Color clustering with background rejection (if elliptical still insufficient)**
   - Sample pixels from full bounding box
   - Run 2-cluster k-means on HSV values (k=2: cat vs background)
   - Identify cat cluster by spatial proximity to bounding box center
   - Classify color using only cat cluster pixels
   - Handles arbitrary backgrounds by actively separating foreground/background
   - K-means on ~400 samples is sub-millisecond

3. **GrabCut segmentation (heaviest, last resort)**
   - OpenCV `Imgproc.grabCut()` with bounding box initialization
   - Most accurate foreground/background separation
   - Costs ~50-100ms per call — probably overkill given detection runs periodically
   - OpenCV already in project (for now)

### Recommended Alternative: Pure Detection Approach (No OpenCV Tracking)

**Key insight from requirements:** Door-opening use case accepts up to 1 second detection latency — continuous 30 FPS tracking is overkill.

**Proposed approach:**
- **Eliminate OpenCV tracking entirely** — remove `MultiCatTrackingManager`, `CatTracker`, OpenCV dependency
- **Run MediaPipe detection every 0.5-1 second** instead of detect-once + continuous tracking
- **Match detections frame-to-frame** using IoU + color similarity (simpler than maintaining tracker state)
- **Define door ROI** — only trigger door when cat detected inside region of interest
- **No tracker drift issues** — always using fresh ML inference, no accumulation of tracking errors

**Why this works better:**
1. More reliable — no drift, no floor false positives from tracker
2. Simpler codebase — remove entire tracking subsystem
3. Sufficient for use case — door doesn't need 30 FPS precision
4. Better accuracy — EfficientDet-Lite0 every 0.5s beats drifted tracker
5. No OpenCV dependency headaches — pure MediaPipe solution

**Alternative frameworks to consider:**
- **MediaPipe ObjectDetector** (already have dependency + model) — has built-in lightweight tracking if needed
- **MLKit Object Detection** — `com.google.mlkit:object-detection:17.0.1` — simpler API, built-in tracking
- **TensorFlow Lite Task Library** — `org.tensorflow:tensorflow-lite-task-vision` — supports existing models, has tracking

**Next steps:**
1. Complete Phase 1 (MediaPipe migration) first
2. Test pure detection approach (every 0.5-1s) without OpenCV tracking
3. If detection-only works well, remove OpenCV entirely from project
4. If tracking still needed, try MediaPipe's built-in tracker or MLKit instead of OpenCV

## Roadmap

Goal: reliable cat detection at a door to trigger automatic door opening. No collars. Up to ~1 second detection latency is acceptable.

### Phase 1 — Switch to MediaPipe Tasks + EfficientDet-Lite0 (current)

**Status:** In progress

- Replace raw TFLite Interpreter with MediaPipe Tasks ObjectDetector API
- `efficientdet_lite0.tflite` is already in assets (downloaded via `setup-efficientdet.bat`) but cannot be loaded by the standard Interpreter — it uses MediaPipe's packaged format with embedded metadata
- EfficientDet-Lite0 is significantly more accurate than the current SSD MobileNet v1
- Add `com.google.android.mediapipe:tasks-vision` dependency to `app/build.gradle.kts`
- Rewrite `CatDetector.kt` to use `ObjectDetector` from MediaPipe Tasks instead of `Interpreter`
- Tune confidence threshold after switching (start at 0.3, adjust based on results)
- If EfficientDet-Lite0 is not accurate enough, upgrade to EfficientDet-Lite2 (heavier, ~500-800ms, still within budget)

**Known pitfalls from previous attempts:**
- Do NOT try to load MediaPipe models with the raw `Interpreter` — it will fail with null interpreter
- The model file format looks like a valid .tflite but has extra MediaPipe metadata wrapper

### Phase 2 — Tune tracking and color detection

**Status:** Not started. Depends on Phase 1.

- Color detection currently re-analyzes on periodic re-detection (fixed in this session), but accuracy depends on HSV thresholds in `CatColorAnalyzer.kt` — tune ranges against real cats after Phase 1 is stable
- Tracker drift: evaluate whether `reDetectionFrameInterval` (currently 30 frames) needs adjustment with the new model's latency characteristics
- Validation IoU thresholds in `MultiCatTrackingManager` (currently 0.2 for validation, 0.3 for general) may need tuning once detection bounding boxes change with the new model
- Consider whether continuous OpenCV tracking is still needed or if periodic detection alone (every 1s) is sufficient for the door-opening use case

### Phase 3 — Motion detection and movement-based ROI

**Status:** Not started. Depends on Phase 2.

- Add lightweight frame-differencing motion detector that runs every frame (cheap, no ML)
- Only invoke the heavy detection model when motion is detected, saving battery and CPU
- Define a configurable door ROI (region of interest) in the camera frame — only trigger door open when cat is detected inside the ROI
- ROI should be configurable via the UI: let user draw or select the door zone on first setup
- Detection outside the ROI can be ignored entirely, or used to track cat approaching the door zone

### Deferred

- Multi-cat tracking
- Video recording with tracking overlay
- Tracking history/trajectory visualization
- Front camera support

## Git Workflow

### Ignored Files
- `/opencv/` - 1.3GB, downloaded via setup script
- `/third_party/` - Future external dependencies
- `/build/`, `/.gradle/`, `.cxx/` - Build artifacts
- `/tmpclaude-*` - Temporary files

### Important Files to Commit
- All `.kt` source files
- `build.gradle.kts`, `settings.gradle.kts`
- `setup-opencv.bat`, `setup-opencv.sh`, `setup-efficientdet.bat`
- `README.md`, `CLAUDE.md`
- `.gitignore`

### Important Files NOT to Commit
- `opencv/` directory (too large)
- Build outputs, IDE files (`.idea/`, except run configs)
- Local properties (`local.properties`)

## Quick Reference: Common Tasks

### Add new detection class
1. Modify `CatDetector.kt:parseDetections()` — the class index filter with ±1 tolerance is there
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
2. Implement classification logic in `analyzeCatColor()` — current approach samples the inner 60% of the bounding box and classifies based on pixel ratios:
   - BLACK: >60% dark pixels (brightness < 60) and <15% brown/orange
   - TABBY: >20% brown/orange pixels (HSV hue 10-70°, saturation >0.15, value >0.2)
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

*Last Updated: 2026-02-07*
*Project Version: 1.1*
*OpenCV Version: 4.8.0*
*Current Detection Model: SSD MobileNet v1 (detect.tflite) — quantized uint8, 300x300 input*
*Next Detection Model: EfficientDet-Lite0 via MediaPipe Tasks (efficientdet_lite0.tflite already in assets)*
*Recommended Next Step: Consider pure detection approach (every 0.5-1s) instead of continuous OpenCV tracking*
