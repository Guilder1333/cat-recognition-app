@echo off
echo Downloading EfficientDet-Lite0 model...

set MODEL_URL=https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/int8/1/efficientdet_lite0.tflite
set OUTPUT_DIR=app\src\main\assets
set OUTPUT_FILE=%OUTPUT_DIR%\efficientdet_lite0.tflite

if not exist "%OUTPUT_DIR%" mkdir "%OUTPUT_DIR%"

echo Downloading from %MODEL_URL%...
curl -L -o "%OUTPUT_FILE%" "%MODEL_URL%"

if exist "%OUTPUT_FILE%" (
    echo.
    echo Successfully downloaded EfficientDet-Lite0 model to %OUTPUT_FILE%
    echo Model size:
    for %%A in ("%OUTPUT_FILE%") do echo %%~zA bytes
) else (
    echo.
    echo Failed to download model. Please download manually from:
    echo %MODEL_URL%
    echo and save to %OUTPUT_FILE%
)

echo.
pause
