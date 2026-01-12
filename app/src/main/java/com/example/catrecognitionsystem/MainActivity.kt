package com.example.catrecognitionsystem

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.example.catrecognitionsystem.camera.CameraManager
import com.example.catrecognitionsystem.ml.CatDetector
import com.example.catrecognitionsystem.ui.CatDetectionScreen
import com.example.catrecognitionsystem.ui.theme.CatRecognitionSystemTheme
import org.opencv.android.OpenCVLoader

class MainActivity : ComponentActivity() {

    private lateinit var cameraManager: CameraManager
    private lateinit var catDetector: CatDetector
    private var cameraPermissionGranted = false

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        cameraPermissionGranted = isGranted
        if (!isGranted) {
            Toast.makeText(
                this,
                "Camera permission is required to use this app",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize OpenCV
        if (!OpenCVLoader.initDebug()) {
            android.util.Log.e("MainActivity", "OpenCV initialization failed")
            Toast.makeText(
                this,
                "OpenCV initialization failed. Tracking will not be available.",
                Toast.LENGTH_LONG
            ).show()
        } else {
            android.util.Log.d("MainActivity", "OpenCV initialized successfully")
        }

        // Initialize camera manager and cat detector
        cameraManager = CameraManager(this)
        catDetector = CatDetector(this)

        // Check camera permission
        checkCameraPermission()

        setContent {
            CatRecognitionSystemTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        if (cameraPermissionGranted) {
                            CatDetectionScreen(
                                cameraManager = cameraManager,
                                catDetector = catDetector
                            )
                        } else {
                            // Show permission required message
                            Text(
                                text = "Camera permission is required to use this app",
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                    }
                }
            }
        }
    }

    private fun checkCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                cameraPermissionGranted = true
            }
            else -> {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraManager.unbind()
        catDetector.close()
    }
}

