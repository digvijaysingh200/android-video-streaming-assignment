"""
server.py — TCP server that accepts one Android client at a time.

StreamServer runs in its own QThread. When a client connects it spawns
a FrameReceiver thread to handle that connection, then goes back to
waiting for the next client once the session ends.

Signals emitted (connected to the GUI in gui.py):
    client_connected(str)     — remote address string, e.g. "192.168.1.5:49200"
    client_disconnected()     — current client session ended
    server_started(int)       — server is listening on this port
    server_stopped()          — server has been shut down
    server_error(str)         — fatal error message
    frame_ready(np.ndarray)   — forwarded from the active FrameReceiver
    stats_updated(float,int,int) — forwarded from the active FrameReceiver
"""

import socket
from typing import Optional

import numpy as np
from PyQt5.QtCore import QThread, pyqtSignal

from receiver import FrameReceiver


# ── Tuning ────────────────────────────────────────────────────────────────────
BACKLOG        = 1      # only one Android client at a time
SOCKET_TIMEOUT = 1.0    # seconds — makes the accept() loop stoppable


class StreamServer(QThread):
    """
    Manages the lifecycle of the TCP server and the per-connection receiver.

    Usage
    -----
    server = StreamServer(port=8080)
    server.frame_ready.connect(my_slot)
    server.start()          # launches the QThread
    ...
    server.stop()           # request shutdown
    server.wait()           # block until thread exits
    """

    # ── Signals ───────────────────────────────────────────────────────────────
    client_connected    = pyqtSignal(str)          # remote addr
    client_disconnected = pyqtSignal()
    server_started      = pyqtSignal(int)          # port number
    server_stopped      = pyqtSignal()
    server_error        = pyqtSignal(str)

    # Forwarded from FrameReceiver
    frame_ready   = pyqtSignal(np.ndarray)
    stats_updated = pyqtSignal(float, int, int)    # fps, width, height

    # ── Constructor ───────────────────────────────────────────────────────────

    def __init__(self, port: int, parent=None) -> None:
        super().__init__(parent)
        self.port = port
        self._running: bool = False
        self._server_sock: Optional[socket.socket] = None
        self._receiver: Optional[FrameReceiver] = None

    # ── QThread entry point ───────────────────────────────────────────────────

    def run(self) -> None:
        self._running = True

        try:
            self._server_sock = self._create_server_socket(self.port)
        except OSError as exc:
            self.server_error.emit(
                f"Cannot bind to port {self.port}: {exc}"
            )
            return

        self.server_started.emit(self.port)

        # ── Accept loop ───────────────────────────────────────────────────────
        while self._running:
            conn, addr = self._accept()
            if conn is None:
                continue  # timeout or stop requested

            remote = f"{addr[0]}:{addr[1]}"
            self.client_connected.emit(remote)

            # Spawn a receiver for this connection
            self._receiver = FrameReceiver(conn)
            self._receiver.frame_ready.connect(self.frame_ready)
            self._receiver.stats_updated.connect(self.stats_updated)
            self._receiver.error_occurred.connect(self._on_receiver_error)
            self._receiver.finished_cleanly.connect(self._on_receiver_finished)
            self._receiver.start()

            # Block here until this session ends so we handle one client at a time
            self._receiver.wait()
            self._receiver = None

            if self._running:
                self.client_disconnected.emit()

        # ── Cleanup ───────────────────────────────────────────────────────────
        self._close_server_socket()
        self.server_stopped.emit()

    # ── Public API ────────────────────────────────────────────────────────────

    def stop(self) -> None:
        """
        Request the server to shut down.

        Stops the active FrameReceiver (if any), then closes the server socket
        so the accept() call unblocks and the thread exits.
        """
        self._running = False

        if self._receiver is not None:
            self._receiver.stop()
            self._receiver.wait()
            self._receiver = None

        self._close_server_socket()

    # ── Internal helpers ──────────────────────────────────────────────────────

    @staticmethod
    def _create_server_socket(port: int) -> socket.socket:
        """
        Create and bind a non-blocking TCP server socket.

        SO_REUSEADDR is set so the port can be reused immediately after
        the previous server instance exits.
        """
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.settimeout(SOCKET_TIMEOUT)
        sock.bind(("0.0.0.0", port))
        sock.listen(BACKLOG)
        return sock

    def _accept(self):
        """
        Wait up to SOCKET_TIMEOUT seconds for a client connection.

        Returns (conn, addr) on success, or (None, None) on timeout / error.
        """
        try:
            return self._server_sock.accept()
        except socket.timeout:
            return None, None
        except OSError:
            return None, None

    def _close_server_socket(self) -> None:
        if self._server_sock is not None:
            try:
                self._server_sock.close()
            except OSError:
                pass
            self._server_sock = None

    # ── Receiver event handlers ───────────────────────────────────────────────

    def _on_receiver_error(self, message: str) -> None:
        self.client_disconnected.emit()

    def _on_receiver_finished(self) -> None:
        pass  # handled after receiver.wait() in the accept loop
