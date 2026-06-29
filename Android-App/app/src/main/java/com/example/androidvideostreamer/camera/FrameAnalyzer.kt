package com.example.androidvideostreamer.camera

import android.graphics.Bitmap
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

// ── Constants ─────────────────────────────────────────────────────────────────
private const val TARGET_FPS         = 15
private const val DEFAULT_QUALITY    = 70
private val TARGET_INTERVAL_MS       = 1_000L / TARGET_FPS   // ≈ 66 ms

/**
 * FrameAnalyzer
 *
 * Implements [ImageAnalysis.Analyzer] to continuously capture frames from
 * the CameraX pipeline, convert them to JPEG, and forward them via a callback.
 *
 * Design decisions:
 *  - [STRATEGY_KEEP_ONLY_LATEST] on the [ImageAnalysis] use-case means CameraX
 *    drops frames we can't keep up with — no queue build-up.
 *  - An additional time-gate ([TARGET_INTERVAL_MS]) caps delivery at 15 FPS
 *    regardless of the camera's native frame rate.
 *  - [image.close()] is always called in `finally` to prevent the camera
 *    pipeline from stalling.
 *  - [image.toBitmap()] (CameraX 1.2+) handles YUV→RGB conversion internally,
 *    giving us a clean ARGB_8888 Bitmap ready for JPEG compression.
 *
 * @param jpegQuality  JPEG compression quality 0–100 (default 70).
 * @param onFrameReady Callback invoked on the camera executor thread with each
 *                     compressed JPEG [ByteArray]. Keep the callback fast —
 *                     heavy work should be dispatched to [Dispatchers.IO].
 */
class FrameAnalyzer(
    private val jpegQuality: Int = DEFAULT_QUALITY,
    private val onFrameReady: (ByteArray) -> Unit,
) : ImageAnalysis.Analyzer {

    // ── State ─────────────────────────────────────────────────────────────────
    private val _isActive        = AtomicBoolean(false)
    private var lastFrameTimeMs  = 0L

    /** Whether the analyzer is currently forwarding frames. */
    val isActive: Boolean get() = _isActive.get()

    // ── Control ───────────────────────────────────────────────────────────────

    /** Allow frames to flow through to [onFrameReady]. */
    fun start() { _isActive.set(true) }

    /** Block frames (the CameraX pipeline keeps running; frames are just dropped). */
    fun stop()  { _isActive.set(false) }

    // ── ImageAnalysis.Analyzer ────────────────────────────────────────────────

    override fun analyze(image: ImageProxy) {
        try {
            // Gate 1: streaming must be active
            if (!_isActive.get()) return

            // Gate 2: FPS throttle — drop frames that arrive too quickly
            val now = System.currentTimeMillis()
            if (now - lastFrameTimeMs < TARGET_INTERVAL_MS) return
            lastFrameTimeMs = now

            // Convert and compress
            val jpegBytes = toJpeg(image) ?: return
            onFrameReady(jpegBytes)

        } finally {
            // Always close — failure to do so stalls the camera pipeline
            image.close()
        }
    }

    // ── Conversion ────────────────────────────────────────────────────────────

    /**
     * Converts an [ImageProxy] (typically YUV_420_888) to a JPEG [ByteArray].
     *
     * [ImageProxy.toBitmap] (CameraX ≥ 1.2.0) performs the YUV → ARGB_8888
     * conversion internally, so no manual NV21 interleaving is needed.
     *
     * @return Compressed JPEG bytes, or null if conversion fails.
     */
    private fun toJpeg(image: ImageProxy): ByteArray? {
        return try {
            val bitmap: Bitmap = image.toBitmap()
            // Pre-size the stream to avoid resizing; JPEG is typically 1/4 of raw
            val out = ByteArrayOutputStream(bitmap.byteCount / 4)
            bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
            bitmap.recycle()
            out.toByteArray()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
