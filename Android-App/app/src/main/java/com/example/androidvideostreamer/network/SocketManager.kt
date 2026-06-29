package com.example.androidvideostreamer.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

// ── Tuning constants ──────────────────────────────────────────────────────────
private const val CONNECT_TIMEOUT_MS = 5_000   // 5 s connection attempt limit
private const val SOCKET_TIMEOUT_MS  = 0       // 0 = no read timeout (streaming)

/**
 * SocketManager — real TCP socket manager.
 *
 * Owns a [java.net.Socket] and a [DataOutputStream] for writing framed JPEG
 * payloads to the desktop receiver.  All I/O happens on [Dispatchers.IO].
 *
 * Wire format written by [send]:
 *
 *     ┌───────────────────────────────────┐
 *     │  4 bytes  │  frame size N (BE)    │  DataOutputStream.writeInt()
 *     ├───────────────────────────────────┤
 *     │  N bytes  │  JPEG payload         │  DataOutputStream.write()
 *     └───────────────────────────────────┘
 *
 * Thread-safety: [send] is guarded by a [Mutex] so concurrent callers on the
 * IO dispatcher do not interleave partial writes.
 */
class SocketManager {

    // ── Observable state ──────────────────────────────────────────────────────
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    val isConnected: Boolean
        get() = _connectionState.value == ConnectionState.CONNECTED

    // ── Internal resources ────────────────────────────────────────────────────
    private var socket: Socket? = null
    private var dataOutputStream: DataOutputStream? = null

    /** Prevents two coroutines from writing to the stream simultaneously. */
    private val sendMutex = Mutex()

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Opens a TCP connection to [host]:[port].
     *
     * Blocks the calling coroutine on [Dispatchers.IO] for up to
     * [CONNECT_TIMEOUT_MS] milliseconds, then transitions state to
     * [ConnectionState.CONNECTED] or [ConnectionState.ERROR].
     *
     * @return `true` if the connection was established successfully.
     */
    suspend fun connect(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        _connectionState.value = ConnectionState.CONNECTING
        closeResources()   // clean up any previous session

        return@withContext try {
            val sock = Socket()
            sock.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            sock.soTimeout = SOCKET_TIMEOUT_MS
            sock.setKeepAlive(true)
            sock.tcpNoDelay  = true   // disable Nagle — important for low-latency video

            socket          = sock
            dataOutputStream = DataOutputStream(sock.getOutputStream().buffered())

            _connectionState.value = ConnectionState.CONNECTED
            true

        } catch (e: IOException) {
            e.printStackTrace()
            _connectionState.value = ConnectionState.ERROR
            false
        } catch (e: Exception) {
            e.printStackTrace()
            _connectionState.value = ConnectionState.ERROR
            false
        }
    }

    /**
     * Closes the socket and resets state to [ConnectionState.DISCONNECTED].
     */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        closeResources()
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /**
     * Sends a JPEG frame using the [4-byte size][JPEG] framing protocol.
     *
     * - Returns `false` immediately if not connected.
     * - On [IOException] transitions state to [ConnectionState.ERROR],
     *   closes the socket, and returns `false` so the caller knows the
     *   session ended.
     *
     * @param data Raw JPEG bytes to transmit.
     * @return `true` if all bytes were written and flushed successfully.
     */
    suspend fun send(data: ByteArray): Boolean {
        if (!isConnected) return false

        return sendMutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val dos = dataOutputStream ?: return@withContext false
                    dos.writeInt(data.size)   // 4-byte big-endian frame size
                    dos.write(data)           // JPEG payload
                    dos.flush()
                    true
                } catch (e: IOException) {
                    e.printStackTrace()
                    _connectionState.value = ConnectionState.ERROR
                    closeResources()
                    false
                }
            }
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun closeResources() {
        runCatching { dataOutputStream?.close() }
        runCatching { socket?.close() }
        dataOutputStream = null
        socket           = null
    }

    // ── Connection state enum ─────────────────────────────────────────────────

    enum class ConnectionState {
        /** No active socket; initial state or after [disconnect]. */
        DISCONNECTED,

        /** A [connect] attempt is in progress. */
        CONNECTING,

        /** Socket is open and [send] may be called. */
        CONNECTED,

        /** An [IOException] occurred; the socket has been closed automatically. */
        ERROR
    }
}
