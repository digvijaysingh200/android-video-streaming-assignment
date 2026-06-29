package com.example.androidvideostreamer.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * VideoStreamingClient
 *
 * Manages the streaming session state and routes JPEG frames to [SocketManager].
 * Frame framing ([4-byte size][JPEG]) is handled entirely inside
 * [SocketManager.send] — this class only guards the streaming gate.
 *
 * Lifecycle:
 *  1. [SocketManager.connect]  → connection established
 *  2. [startStreaming]         → opens the streaming session (gate = open)
 *  3. [sendFrame]              → called per-frame from [FrameAnalyzer] callback
 *  4. [stopStreaming]          → closes the streaming session (gate = closed)
 *
 * @param socketManager The shared [SocketManager] used for transport.
 */
class VideoStreamingClient(private val socketManager: SocketManager) {

    // ── State ─────────────────────────────────────────────────────────────────

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _frameCount = MutableStateFlow(0L)
    val frameCount: StateFlow<Long> = _frameCount.asStateFlow()

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Opens a streaming session.
     *
     * No-op if already streaming.  Returns false if the socket is not connected.
     *
     * @return `true` if the session was started (or was already active).
     */
    fun startStreaming(): Boolean {
        if (!socketManager.isConnected) return false
        if (_isStreaming.value) return true
        _isStreaming.value = true
        _frameCount.value  = 0L
        return true
    }

    /**
     * Closes the streaming session and resets the frame counter.
     *
     * Safe to call even when not currently streaming.
     */
    fun stopStreaming() {
        _isStreaming.value = false
    }

    /**
     * Sends a single JPEG frame to the desktop receiver.
     *
     * This is a **suspend** function that blocks the caller on [Dispatchers.IO]
     * until the frame is fully written to the socket buffer (or fails).
     *
     * @param frameData Compressed JPEG bytes from [FrameAnalyzer].
     * @return `true` if the frame was sent successfully; `false` if the
     *         streaming gate is closed, the socket is disconnected, or a
     *         write error occurred.
     */
    suspend fun sendFrame(frameData: ByteArray): Boolean {
        if (!_isStreaming.value || !socketManager.isConnected) return false

        val sent = socketManager.send(frameData)
        if (sent) {
            _frameCount.value++
        }
        return sent
    }

    /**
     * Human-readable session summary for debugging.
     */
    fun sessionSummary(): String =
        if (_isStreaming.value) "Streaming — ${_frameCount.value} frames sent"
        else                    "Not streaming"
}
