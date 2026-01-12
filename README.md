# Cat Recognition App

Android application for detecting and tracking cats using TensorFlow Lite and OpenCV.

## Features

- Real-time cat detection using TensorFlow Lite SSD MobileNet
- Continuous video tracking with OpenCV TrackerMIL
- Color-based filtering (Black, Tabby, or Any)
- Hybrid detection/tracking mode for efficient performance
- Live camera overlay with bounding boxes

## Prerequisites

- Android Studio (latest version recommended)
- Android SDK with API 31+
- JDK 11 or higher
- Git

## Setup Instructions

### 1. Clone the Repository

```bash
git clone <repository-url>
cd cat-recognition-app
```

### 2. Download OpenCV Android SDK

This project uses OpenCV for object tracking. Since the OpenCV library is large (1.3GB), it's not included in the repository. Run the setup script to download it:

**Windows:**
```batch
setup-opencv.bat
```

**Linux/Mac:**
```bash
./setup-opencv.sh
```

This will download OpenCV 4.8.0 Android SDK (~300MB download) and extract it to the `opencv/` directory.

**Manual Download (Alternative):**
If the script doesn't work, you can manually download OpenCV:
1. Download [OpenCV 4.8.0 Android SDK](https://github.com/opencv/opencv/releases/download/4.8.0/opencv-4.8.0-android-sdk.zip)
2. Extract the zip file
3. Rename the `OpenCV-android-sdk/sdk` folder to `opencv`
4. Move the `opencv` folder to the project root directory

### 3. Build the Project

```bash
./gradlew assembleDebug
```

Or open the project in Android Studio and build normally.

### 4. Run on Device

Connect an Android device with USB debugging enabled and run:

```bash
./gradlew installDebug
```

Or use Android Studio's "Run" button.

## Project Structure

```
cat-recognition-app/
├── app/                          # Main application module
│   ├── src/main/
│   │   ├── java/.../
│   │   │   ├── camera/          # CameraX integration
│   │   │   ├── ml/              # TensorFlow Lite detection
│   │   │   ├── tracking/        # OpenCV tracking implementation
│   │   │   ├── ui/              # Jetpack Compose UI
│   │   │   └── utils/           # Helper utilities
│   │   └── assets/              # TFLite model files
│   └── build.gradle.kts
├── opencv/                       # OpenCV Android SDK (not in git)
├── setup-opencv.bat              # Windows setup script
├── setup-opencv.sh               # Linux/Mac setup script
└── README.md
```

## Usage

1. Launch the app
2. Grant camera permissions
3. (Optional) Select a cat color filter: Any Color, Black, or Tabby
4. Point camera at a cat
5. Press "Start Tracking" to begin continuous tracking
6. The app will show a colored bounding box around the detected cat
7. Press "Stop Tracking" to stop

## Technical Details

- **Detection Model**: TensorFlow Lite SSD MobileNet
- **Tracking**: OpenCV TrackerMIL (Multiple Instance Learning)
- **Framework**: Jetpack Compose + CameraX
- **Language**: Kotlin
- **Min SDK**: 31
- **Target SDK**: 34

### Tracking System

The app uses a hybrid detection/tracking approach:
- Initial detection runs when "Start Tracking" is pressed
- Continuous tracking runs at ~30 FPS using OpenCV
- Periodic re-detection every 30 frames to correct tracking drift
- Tracks only the most confident cat of the selected color

## Dependencies

- AndroidX Core, Lifecycle, Compose
- CameraX (camera2, lifecycle, view)
- TensorFlow Lite (with support and metadata)
- OpenCV 4.8.0 Android SDK (local module)
- Coil (image loading)

## Troubleshooting

### OpenCV not found
Run the setup script again: `setup-opencv.bat` (Windows) or `./setup-opencv.sh` (Linux/Mac)

### Camera not working
Ensure camera permissions are granted in Android Settings > Apps > Cat Recognition App > Permissions

### Build errors
- Clean and rebuild: `./gradlew clean assembleDebug`
- Invalidate caches in Android Studio: File > Invalidate Caches / Restart

### Tracking not working
- Ensure good lighting conditions
- Cat should be clearly visible and not too far away
- Try different color filters if the cat is not being detected

## License

This project uses:
- OpenCV (Apache 2.0 License)
- TensorFlow Lite (Apache 2.0 License)
