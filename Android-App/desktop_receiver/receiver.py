"""
receiver.py — Frame reading protocol for the Android video stream.

Frame wire format (same as the Android SocketManager / VideoStreamingClient):
    ┌─────────────────────────────┐
    │  4 bytes  │  frame size N   │  big-endian unsigned int
    ├─────────────────────────────┤
    │  N bytes  │  JPEG payload   │
    └─────────────────────────────┘

FrameReceiver runs inside a QThread and emits one Qt signal per decoded frame.
It also tracks per-frame timing so the server can report an accurate FPS value.
"""

import struct
import time
import socket
from typing import Optional

import numpy as np
import cv2

from PyQt5.QtCore import QThread, pyqtSignal


# ── Constants ─────────────────────────────────────────────────────────────────
HEADER_SIZE   = 4          # bytes — big-endian uint32
MAX_FRAME_SIZE = 10 * 1024 * 1024  # 10 MB safety limit
RECV_CHUNK    = 65536      # bytes per recv() call


class FrameReceiver(QThread):
    """
    Reads a continuous stream of JPEG frames from a connected TCP socket.

    Signals
    -------
    frame_ready(np.ndarray)
        Emitted for every successfully decoded BGR frame.
    error_occurred(str)
        Emitted when an unrecoverable error terminates the receiver.
    stats_updated(float, int, int)
        Emitted after every frame with (fps, width, height).
    """

    frame_ready    = pyqtSignal(np.ndarray)
    error_occurred = pyqtSignal(str)
    stats_updated  = pyqtSignal(float, int, int)   # fps, width, height

    def __init__(self, conn: socket.socket, parent=None) -> None:
        super().__init__(parent)
        self._conn        = conn
        self._running     = False

        # FPS tracking
        self._frame_times: list[float] = []
        self._fps_window  = 30   # average over last N frames

    # ── QThread entry point ───────────────────────────────────────────────────

    def run(self) -> None:
        self._running = True
        try:
            while self._running:
                frame_bgr = self._read_frame()
                if frame_bgr is None:
                    break

                # ── Emit frame ────────────────────────────────────────────────
                self.frame_ready.emit(frame_bgr)

                # ── Update FPS / resolution stats ─────────────────────────────
                now = time.monotonic()
                self._frame_times.append(now)
                if len(self._frame_times) > self._fps_window:
                    self._frame_times.pop(0)

                fps = 0.0
                if len(self._frame_times) >= 2:
                    elapsed = self._frame_times[-1] - self._frame_times[0]
                    if elapsed > 0:
                        fps = (len(self._frame_times) - 1) / elapsed

                h, w = frame_bgr.shape[:2]
                self.stats_updated.emit(fps, w, h)

        except Exception as exc:
            if self._running:
                self.error_occurred.emit(str(exc))
        finally:
            self._close_socket()

    # ── Public API ────────────────────────────────────────────────────────────

    def stop(self) -> None:
        """Signal the receiver loop to exit and close the socket."""
        self._running = False
        self._close_socket()

    # ── Internal helpers ──────────────────────────────────────────────────────

    def _read_frame(self) -> Optional[np.ndarray]:
        """
        Read one complete frame from the socket.

        Returns the decoded BGR image, or None if the connection closed.
        Raises RuntimeError on protocol violations.
        """
        # ── Step 1: read the 4-byte size header ───────────────────────────────
        header = self._recv_exact(HEADER_SIZE)
        if header is None:
            return None   # clean disconnect

        (frame_size,) = struct.unpack(">I", header)

        if frame_size == 0:
            return None   # sender signalled end-of-stream

        if frame_size > MAX_FRAME_SIZE:
            raise RuntimeError(
                f"Frame size {frame_size} bytes exceeds safety limit "
                f"({MAX_FRAME_SIZE} bytes). Possible protocol error."
            )

        # ── Step 2: read the JPEG payload ─────────────────────────────────────
        jpeg_bytes = self._recv_exact(frame_size)
        if jpeg_bytes is None:
            return None   # connection dropped mid-frame

        # ── Step 3: decode JPEG → BGR ndarray ────────────────────────────────
        jpeg_array = np.frombuffer(jpeg_bytes, dtype=np.uint8)
        frame_bgr  = cv2.imdecode(jpeg_array, cv2.IMREAD_COLOR)

        if frame_bgr is None:
            # Non-fatal: skip corrupt frames
            return self._read_frame()

        return frame_bgr

    def _recv_exact(self, n: int) -> Optional[bytes]:
        """
        Read exactly *n* bytes from the socket, blocking until all arrive.

        Returns None if the connection is closed before all bytes are received.
        """
        buf = bytearray()
        while len(buf) < n:
            try:
                chunk = self._conn.recv(min(RECV_CHUNK, n - len(buf)))
            except OSError:
                return None
            if not chunk:
                return None
            buf.extend(chunk)
        return bytes(buf)

    def _close_socket(self) -> None:
        try:
            self._conn.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        try:
            self._conn.close()
        except OSError:
            pass
