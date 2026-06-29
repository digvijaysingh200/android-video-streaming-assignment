package com.example.androidvideostreamer

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.androidvideostreamer.ui.StreamingScreen
import com.example.androidvideostreamer.ui.theme.AndroidVideoStreamerTheme
import com.example.androidvideostreamer.viewmodel.StreamingViewModel

/**
 * MainActivity — the single-activity entry point for the app.
 *
 * Responsibilities:
 *  - Request the CAMERA permission at runtime.
 *  - Host the Compose content tree.
 *  - Provide the [StreamingViewModel] scoped to this Activity.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: StreamingViewModel by viewModels()

    // ── Permission launcher ──────────────────────────────────────────────────
    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            viewModel.onCameraPermissionResult(granted)
        }

    // ────────────────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Request camera permission as early as possible
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)

        setContent {
            AndroidVideoStreamerTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    StreamingScreen(viewModel = viewModel)
                }
            }
        }
    }
}