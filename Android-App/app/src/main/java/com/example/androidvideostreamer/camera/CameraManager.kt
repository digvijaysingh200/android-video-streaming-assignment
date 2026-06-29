package com.example.androidvideostreamer.camera

import android.content.Context
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * CameraManager
 *
 * Wraps CameraX to provide:
 *  - A rear-camera [Preview] bound to any [LifecycleOwner].
 *  - An [ImageAnalysis] use-case that feeds frames to a [FrameAnalyzer].
 *  - [isCameraActive] state observable by the ViewModel / UI.
 *
 * Usage:
 *  1. Create one instance per screen (owned by the ViewModel).
 *  2. Call [startPreview] — pass a [FrameAnalyzer] to enable frame capture.
 *  3. Call [stopPreview] to unbind all use-cases.
 *  4. Call [shutdown] from [ViewModel.onCleared] to release the executor.
 */
class CameraManager {

    // ── State ─────────────────────────────────────────────────────────────────
    private val _isCameraActive = MutableStateFlow(false)
    val isCameraActive: StateFlow<Boolean> = _isCameraActive.asStateFlow()

    private var cameraProvider: ProcessCameraProvider? = null

    /**
     * Single-thread executor dedicated to CameraX callbacks and frame analysis.
     * Using one thread avoids frame ordering issues and limits CPU contention.
     */
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Binds the rear-camera [Preview] (and optionally [ImageAnalysis]) to
     * the given [lifecycleOwner].
     *
     * @param context        Activity / Application context.
     * @param lifecycleOwner Lifecycle to bind the CameraX use-cases to.
     * @param previewView    Surface that renders the live viewfinder.
     * @param frameAnalyzer  Optional [FrameAnalyzer]. When non-null an
     *                       [ImageAnalysis] use-case targeting ~640×480 is
     *                       added alongside the preview.
     */
    fun startPreview(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        frameAnalyzer: FrameAnalyzer? = null,
    ) {
        val future = ProcessCameraProvider.getInstance(context)

        future.addListener({
            cameraProvider = future.get()

            // ── Preview use-case ──────────────────────────────────────────────
            val preview = Preview.Builder()
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            // ── ImageAnalysis use-case (only when a FrameAnalyzer is provided) ─
            val imageAnalysis = frameAnalyzer?.let { analyzer ->
                @Suppress("DEPRECATION")   // setTargetResolution is deprecated in 1.3+
                // but ResolutionSelector is still @ExperimentalCamera2Interop on some
                // device variants — setTargetResolution is the safest cross-device choice.
                ImageAnalysis.Builder()
                    .setTargetResolution(Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also { it.setAnalyzer(cameraExecutor, analyzer) }
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider?.unbindAll()

                if (imageAnalysis != null) {
                    cameraProvider?.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalysis,
                    )
                } else {
                    cameraProvider?.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                    )
                }

                _isCameraActive.value = true

            } catch (e: Exception) {
                e.printStackTrace()
                _isCameraActive.value = false
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Unbinds all CameraX use-cases, stopping both preview and frame analysis.
     */
    fun stopPreview() {
        cameraProvider?.unbindAll()
        _isCameraActive.value = false
    }

    /**
     * Releases the camera executor. **Must** be called from [ViewModel.onCleared]
     * to avoid leaking the background thread.
     */
    fun shutdown() {
        stopPreview()
        cameraExecutor.shutdown()
    }
}
