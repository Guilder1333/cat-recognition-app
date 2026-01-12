@echo off
REM Setup script for OpenCV Android SDK
REM Downloads and extracts OpenCV 4.8.0 if not already present

SET OPENCV_VERSION=4.8.0
SET OPENCV_URL=https://github.com/opencv/opencv/releases/download/%OPENCV_VERSION%/opencv-%OPENCV_VERSION%-android-sdk.zip
SET OPENCV_ZIP=opencv-android-sdk.zip

echo ================================================
echo OpenCV Android SDK Setup
echo ================================================
echo.

REM Check if opencv directory already exists
if exist "opencv\" (
    echo OpenCV directory already exists.
    echo If you want to re-download, delete the opencv\ directory first.
    echo.
    pause
    exit /b 0
)

echo Downloading OpenCV %OPENCV_VERSION% Android SDK...
echo This may take a few minutes (approximately 300MB download)...
echo.

REM Download using PowerShell (available on all modern Windows)
powershell -Command "& {Invoke-WebRequest -Uri '%OPENCV_URL%' -OutFile '%OPENCV_ZIP%'}"

if not exist "%OPENCV_ZIP%" (
    echo Error: Download failed!
    pause
    exit /b 1
)

echo.
echo Download complete. Extracting...
echo.

REM Extract using PowerShell
powershell -Command "& {Expand-Archive -Path '%OPENCV_ZIP%' -DestinationPath '.' -Force}"

REM Rename the extracted folder to opencv
if exist "OpenCV-android-sdk\sdk" (
    move "OpenCV-android-sdk\sdk" "opencv"
    rmdir "OpenCV-android-sdk"
)

REM Clean up zip file
del "%OPENCV_ZIP%"

echo.
echo ================================================
echo OpenCV setup complete!
echo ================================================
echo.
echo You can now build the project with: gradlew assembleDebug
echo.
pause
