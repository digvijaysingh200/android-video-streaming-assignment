"""
receiver.py — Frame-reading protocol for the Android video stream.

Wire format (matches Android VideoStreamingClient):
    ┌───────────────────────────────┐
    │  4 bytes  │  frame size  (N)  │  big-endian unsigned int32
    ├───────────────────────────────┤
    │  N bytes  │  JPEG payload     │
    └───────────────────────────────┘

FrameReceiver runs inside a QThread and emits Qt signals so the GUI
can update safely from the main thread.
"""

import struct
import time
import socket
from typing import Optional

import numpy as np
import cv2
from PyQt5.QtCore import QThread, pyqtSignal


# ── Constants ─────────────────────────────────────────────────────────────────
HEADER_SIZE    = 4               # bytes — big-endian uint32
MAX_FRAME_SIZE = 10 * 1024 * 1024  # 10 MB safety ceiling
RECV_CHUNK     = 65_536          # bytes per recv() syscall
FPS_WINDOW     = 30              # number of recent frames used for FPS average


class FrameReceiver(QThread):
    """
    Continuously reads JPEG frames from a connected TCP socket.

    Signals
    -------
    frame_ready(np.ndarray)
        Emitted for every successfully decoded BGR frame.
    stats_updated(float, int, int)
        Emitted after every frame with (fps, frame_width, frame_height).
    error_occurred(str)
        Emitted when an unrecoverable error terminates the loop.
    finished_cleanly()
        Emitted when the remote peer closes the connection gracefully.
    """

    frame_ready     = pyqtSignal(np.ndarray)
    stats_updated   = pyqtSignal(float, int, int)   # fps, w, h
    error_occurred  = pyqtSignal(str)
    finished_cleanly = pyqtSignal()

    # ── Constructor ───────────────────────────────────────────────────────────

    def __init__(self, conn: socket.socket, parent=None) -> None:
        super().__init__(parent)
        self._conn    = conn
        self._running = False
        self._frame_times: list[float] = []

    # ── QThread entry point ───────────────────────────────────────────────────

    def run(self) -> None:
        self._running = True
        try:
            while self._running:
                frame_bgr = self._read_frame()
                if frame_bgr is None:
                    # Peer closed connection cleanly
                    self.finished_cleanly.emit()
                    break

                self.frame_ready.emit(frame_bgr)
                self._emit_stats(frame_bgr)

        except RuntimeError as exc:
            if self._running:
                self.error_occurred.emit(str(exc))
        except Exception as exc:
            if self._running:
                self.error_occurred.emit(f"Unexpected error: {exc}")
        finally:
            self._close_socket()

    # ── Public API ────────────────────────────────────────────────────────────

    def stop(self) -> None:
        """Request the receiver to stop and close the underlying socket."""
        self._running = False
        self._close_socket()

    # ── Frame reading ─────────────────────────────────────────────────────────

    def _read_frame(self) -> Optional[np.ndarray]:
        """
        Read one [header + JPEG] frame from the socket.

        Returns
        -------
        np.ndarray
            Decoded BGR image on success.
        None
            When the remote peer closed the connection.

        Raises
        ------
        RuntimeError
            On protocol violations (oversized frame, corrupt header).
        """
        # 1. Read 4-byte size header
        header = self._recv_exact(HEADER_SIZE)
        if header is None:
            return None

        (frame_size,) = struct.unpack(">I", header)

        if frame_size == 0:
            return None  # sender's end-of-stream marker

        if frame_size > MAX_FRAME_SIZE:
            raise RuntimeError(
                f"Declared frame size {frame_size:,} bytes exceeds the "
                f"{MAX_FRAME_SIZE // (1024*1024)} MB safety limit — "
                "possible protocol mismatch."
            )

        # 2. Read JPEG payload
        jpeg_bytes = self._recv_exact(frame_size)
        if jpeg_bytes is None:
            return None

        # 3. Decode JPEG → BGR ndarray
        arr = np.frombuffer(jpeg_bytes, dtype=np.uint8)
        frame = cv2.imdecode(arr, cv2.IMREAD_COLOR)

        if frame is None:
            # Skip single corrupt frame rather than crashing
            return self._read_frame()

        return frame

    def _recv_exact(self, n: int) -> Optional[bytes]:
        """
        Blocking read of exactly *n* bytes from the socket.

        Returns None if the connection is closed before all bytes arrive.
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

    # ── Stats ─────────────────────────────────────────────────────────────────

    def _emit_stats(self, frame: np.ndarray) -> None:
        now = time.monotonic()
        self._frame_times.append(now)
        if len(self._frame_times) > FPS_WINDOW:
            self._frame_times.pop(0)

        fps = 0.0
        if len(self._frame_times) >= 2:
            span = self._frame_times[-1] - self._frame_times[0]
            if span > 0:
                fps = (len(self._frame_times) - 1) / span

        h, w = frame.shape[:2]
        self.stats_updated.emit(fps, w, h)

    # ── Socket teardown ───────────────────────────────────────────────────────

    def _close_socket(self) -> None:
        for fn in (
            lambda: self._conn.shutdown(socket.SHUT_RDWR),
            lambda: self._conn.close(),
        ):
            try:
                fn()
            except OSError:
                pass
