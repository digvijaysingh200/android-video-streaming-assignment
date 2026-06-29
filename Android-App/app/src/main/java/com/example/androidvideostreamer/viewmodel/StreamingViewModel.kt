package com.example.androidvideostreamer.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.androidvideostreamer.camera.CameraManager
import com.example.androidvideostreamer.camera.FrameAnalyzer
import com.example.androidvideostreamer.network.SocketManager
import com.example.androidvideostreamer.network.VideoStreamingClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * StreamingViewModel
 *
 * Central coordinator for the streaming screen.  Owns every collaborator and
 * wires them together:
 *
 *  CameraX  ──frames──►  FrameAnalyzer
 *                              │  JPEG bytes
 *                              ▼
 *                     VideoStreamingClient
 *                              │  via SocketManager.send()
 *                              ▼
 *                      Desktop receiver (TCP)
 *
 * Connection-loss handling:
 *  When [SocketManager] transitions to [ConnectionState.ERROR] (e.g. desktop
 *  receiver closes), the ViewModel automatically stops the [FrameAnalyzer] and
 *  the [VideoStreamingClient] session so the UI reflects the correct state.
 */
class StreamingViewModel : ViewModel() {

    // ── Collaborators ─────────────────────────────────────────────────────────
    val cameraManager   = CameraManager()
    private val socketManager    = SocketManager()
    private val streamingClient  = VideoStreamingClient(socketManager)

    /**
     * [FrameAnalyzer] is public so [StreamingScreen] can pass it into
     * [CameraManager.startPreview] when camera permission is granted.
     *
     * The callback runs on the CameraX executor thread; we dispatch the
     * actual send to [Dispatchers.IO] via a new coroutine.
     */
//    val frameAnalyzer = FrameAnalyzer(jpegQuality = JPEG_QUALITY) { jpegBytes ->
//        viewModelScope.launch(Dispatchers.IO) {
//            val sent = streamingClient.sendFrame(jpegBytes)
//            // If a send fails while we think we're streaming, the socket
//            // is already in ERROR state — the observer below will clean up.
//            if (!sent && streamingClient.isStreaming.value) {
//                // Force stop the analyzer immediately to stop producing frames
//                // that have nowhere to go (the observer handles the full teardown)
//                frameAnalyzer.stop()
//            }
//        }
//    }
    val frameAnalyzer: FrameAnalyzer = FrameAnalyzer(
        jpegQuality = JPEG_QUALITY
    ) { jpegBytes ->

        viewModelScope.launch(Dispatchers.IO) {
            val sent = streamingClient.sendFrame(jpegBytes)

            if (!sent && streamingClient.isStreaming.value) {
                stopStreaming()
            }
        }
    }

    // ── User input fields ─────────────────────────────────────────────────────
    private val _ipAddress = MutableStateFlow("192.168.1.100")
    val ipAddress: StateFlow<String> = _ipAddress.asStateFlow()

    private val _port = MutableStateFlow("8080")
    val port: StateFlow<String> = _port.asStateFlow()

    // ── Camera permission ─────────────────────────────────────────────────────
    private val _isCameraPermissionGranted = MutableStateFlow(false)
    val isCameraPermissionGranted: StateFlow<Boolean> = _isCameraPermissionGranted.asStateFlow()

    // ── Derived UI state ──────────────────────────────────────────────────────

    /** Human-readable status string shown in the UI. */
    val connectionStatus: StateFlow<String> = socketManager.connectionState
        .map { state ->
            when (state) {
                SocketManager.ConnectionState.DISCONNECTED -> "Disconnected"
                SocketManager.ConnectionState.CONNECTING   -> "Connecting…"
                SocketManager.ConnectionState.CONNECTED    -> "Connected"
                SocketManager.ConnectionState.ERROR        -> "Connection lost"
            }
        }
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = "Disconnected",
        )

    val isConnected: StateFlow<Boolean> = socketManager.connectionState
        .map { it == SocketManager.ConnectionState.CONNECTED }
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = false,
        )

    val isStreaming: StateFlow<Boolean> = streamingClient.isStreaming
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = false,
        )

    val frameCount: StateFlow<Long> = streamingClient.frameCount
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0L,
        )

    // ── Connection-loss observer ──────────────────────────────────────────────

    init {
        // Automatically stop the streaming session and the frame analyzer
        // whenever the socket reports an ERROR (e.g. desktop app closed, Wi-Fi
        // dropped) or returns to DISCONNECTED from an active stream.
        socketManager.connectionState
            .onEach { state ->
                val sessionWasActive =
                    streamingClient.isStreaming.value || frameAnalyzer.isActive

                if (state == SocketManager.ConnectionState.ERROR && sessionWasActive) {
                    frameAnalyzer.stop()
                    streamingClient.stopStreaming()
                }
            }
            .launchIn(viewModelScope)
    }

    // ── Input handlers ────────────────────────────────────────────────────────

    fun onIpAddressChange(value: String) { _ipAddress.value = value }

    fun onPortChange(value: String) {
        if (value.all { it.isDigit() } && value.length <= 5) {
            _port.value = value
        }
    }

    // ── Permission ────────────────────────────────────────────────────────────

    fun onCameraPermissionResult(granted: Boolean) {
        _isCameraPermissionGranted.value = granted
    }

    // ── Network actions ───────────────────────────────────────────────────────

    /**
     * Validates input and opens a TCP connection to the desktop receiver.
     */
    fun connect() {
        val host       = _ipAddress.value.trim()
        val portNumber = _port.value.toIntOrNull() ?: return
        if (host.isBlank() || portNumber !in 1..65535) return

        viewModelScope.launch {
            socketManager.connect(host, portNumber)
        }
    }

    /**
     * Stops any active stream, stops the frame analyzer, and closes the socket.
     */
    fun disconnect() {
        frameAnalyzer.stop()
        streamingClient.stopStreaming()
        viewModelScope.launch {
            socketManager.disconnect()
        }
    }

    // ── Streaming actions ─────────────────────────────────────────────────────

    /**
     * Starts streaming — opens the [VideoStreamingClient] session and activates
     * the [FrameAnalyzer] so frames start flowing.
     */
    fun startStreaming() {
        if (!socketManager.isConnected) return
        val started = streamingClient.startStreaming()
        if (started) {
            frameAnalyzer.start()
        }
    }

    /**
     * Stops streaming — deactivates the [FrameAnalyzer] and closes the session.
     * The socket connection remains open.
     */
    fun stopStreaming() {
        frameAnalyzer.stop()
        streamingClient.stopStreaming()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        frameAnalyzer.stop()
        streamingClient.stopStreaming()
        cameraManager.shutdown()
    }

    // ── Constants ─────────────────────────────────────────────────────────────

    private companion object {
        const val JPEG_QUALITY = 70
    }
}
