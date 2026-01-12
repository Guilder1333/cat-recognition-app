#!/bin/bash
# Setup script for OpenCV Android SDK
# Downloads and extracts OpenCV 4.8.0 if not already present

OPENCV_VERSION="4.8.0"
OPENCV_URL="https://github.com/opencv/opencv/releases/download/${OPENCV_VERSION}/opencv-${OPENCV_VERSION}-android-sdk.zip"
OPENCV_ZIP="opencv-android-sdk.zip"

echo "================================================"
echo "OpenCV Android SDK Setup"
echo "================================================"
echo ""

# Check if opencv directory already exists
if [ -d "opencv" ]; then
    echo "OpenCV directory already exists."
    echo "If you want to re-download, delete the opencv/ directory first."
    echo ""
    exit 0
fi

echo "Downloading OpenCV ${OPENCV_VERSION} Android SDK..."
echo "This may take a few minutes (approximately 300MB download)..."
echo ""

# Download using curl or wget (whichever is available)
if command -v curl &> /dev/null; then
    curl -L -o "${OPENCV_ZIP}" "${OPENCV_URL}"
elif command -v wget &> /dev/null; then
    wget -O "${OPENCV_ZIP}" "${OPENCV_URL}"
else
    echo "Error: Neither curl nor wget found. Please install one of them."
    exit 1
fi

if [ ! -f "${OPENCV_ZIP}" ]; then
    echo "Error: Download failed!"
    exit 1
fi

echo ""
echo "Download complete. Extracting..."
echo ""

# Extract using unzip
if command -v unzip &> /dev/null; then
    unzip -q "${OPENCV_ZIP}"
else
    echo "Error: unzip not found. Please install unzip."
    exit 1
fi

# Rename the extracted folder to opencv
if [ -d "OpenCV-android-sdk/sdk" ]; then
    mv "OpenCV-android-sdk/sdk" "opencv"
    rmdir "OpenCV-android-sdk"
fi

# Clean up zip file
rm "${OPENCV_ZIP}"

echo ""
echo "================================================"
echo "OpenCV setup complete!"
echo "================================================"
echo ""
echo "You can now build the project with: ./gradlew assembleDebug"
echo ""
