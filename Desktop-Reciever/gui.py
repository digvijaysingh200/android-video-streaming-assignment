"""
gui.py — PyQt5 main window for the Android Video Streamer desktop receiver.

Layout (top → bottom):
    ┌──────────────────────────────────────────┐
    │  Header bar  (title + status dot)        │
    ├──────────────────────────────────────────┤
    │                                          │
    │         Video display area               │
    │     (scales to fill, keeps ratio)        │
    │                                          │
    ├──────────────────────────────────────────┤
    │  Stats bar   (FPS · Resolution · Remote) │
    ├──────────────────────────────────────────┤
    │  Control bar (Port · Start · Stop)       │
    └──────────────────────────────────────────┘
"""

from __future__ import annotations

import numpy as np
import cv2

from PyQt5.QtCore import Qt, QSize, pyqtSlot
from PyQt5.QtGui import (
    QColor, QFont, QImage, QPainter, QPalette, QPixmap, QIcon
)
from PyQt5.QtWidgets import (
    QApplication, QFrame, QHBoxLayout, QLabel, QMainWindow,
    QPushButton, QSizePolicy, QSpinBox, QStatusBar, QVBoxLayout,
    QWidget,
)

from server import StreamServer


# ── Palette ───────────────────────────────────────────────────────────────────
_BG         = "#0D1117"
_SURFACE    = "#161B22"
_CARD       = "#1C2333"
_BORDER     = "#30363D"
_TEAL       = "#00BFA5"
_TEAL_DARK  = "#00897B"
_TEXT_PRI   = "#E6EDF3"
_TEXT_SEC   = "#8B949E"
_GREEN      = "#4CAF50"
_RED        = "#EF5350"
_AMBER      = "#FF9800"


# ── Helper: apply dark palette to QApplication ────────────────────────────────

def apply_dark_palette(app: QApplication) -> None:
    app.setStyle("Fusion")
    pal = QPalette()
    pal.setColor(QPalette.Window,          QColor(_BG))
    pal.setColor(QPalette.WindowText,      QColor(_TEXT_PRI))
    pal.setColor(QPalette.Base,            QColor(_SURFACE))
    pal.setColor(QPalette.AlternateBase,   QColor(_CARD))
    pal.setColor(QPalette.ToolTipBase,     QColor(_CARD))
    pal.setColor(QPalette.ToolTipText,     QColor(_TEXT_PRI))
    pal.setColor(QPalette.Text,            QColor(_TEXT_PRI))
    pal.setColor(QPalette.Button,          QColor(_CARD))
    pal.setColor(QPalette.ButtonText,      QColor(_TEXT_PRI))
    pal.setColor(QPalette.BrightText,      QColor(_TEAL))
    pal.setColor(QPalette.Highlight,       QColor(_TEAL))
    pal.setColor(QPalette.HighlightedText, QColor(_BG))
    pal.setColor(QPalette.Link,            QColor(_TEAL))
    app.setPalette(pal)


# ── VideoLabel ────────────────────────────────────────────────────────────────

class VideoLabel(QLabel):
    """
    A QLabel that:
    - Shows a placeholder when no stream is active.
    - Scales incoming frames to fill the widget while preserving aspect ratio.
    """

    _PLACEHOLDER_TEXT = "Waiting for stream…"

    def __init__(self, parent=None) -> None:
        super().__init__(parent)
        self.setAlignment(Qt.AlignCenter)
        self.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Expanding)
        self.setMinimumSize(640, 360)
        self.setStyleSheet(f"""
            QLabel {{
                background-color: {_BG};
                color: {_TEXT_SEC};
                font-size: 18px;
                border: 2px solid {_BORDER};
                border-radius: 8px;
            }}
        """)
        self._current_pixmap: QPixmap | None = None
        self.setText(self._PLACEHOLDER_TEXT)

    # ── Public API ─────────────────────────────────────────────────────────

    def display_frame(self, frame_bgr: np.ndarray) -> None:
        """Convert a BGR ndarray to QPixmap and repaint."""
        rgb = cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2RGB)
        h, w, ch = rgb.shape
        qimg = QImage(rgb.data, w, h, ch * w, QImage.Format_RGB888)
        self._current_pixmap = QPixmap.fromImage(qimg)
        self.setText("")
        self._repaint_scaled()

    def clear_frame(self) -> None:
        """Return to the placeholder state."""
        self._current_pixmap = None
        self.setText(self._PLACEHOLDER_TEXT)
        self.setPixmap(QPixmap())

    # ── Overrides ──────────────────────────────────────────────────────────

    def resizeEvent(self, event) -> None:
        super().resizeEvent(event)
        self._repaint_scaled()

    def _repaint_scaled(self) -> None:
        if self._current_pixmap is None:
            return
        scaled = self._current_pixmap.scaled(
            self.size(), Qt.KeepAspectRatio, Qt.SmoothTransformation
        )
        self.setPixmap(scaled)


# ── StatusDot ─────────────────────────────────────────────────────────────────

class StatusDot(QLabel):
    """A small circular coloured indicator dot."""

    _SIZE = 14

    def __init__(self, parent=None) -> None:
        super().__init__(parent)
        self.setFixedSize(self._SIZE, self._SIZE)
        self.set_disconnected()

    def set_disconnected(self) -> None:
        self._set_color(_RED)

    def set_connecting(self) -> None:
        self._set_color(_AMBER)

    def set_connected(self) -> None:
        self._set_color(_GREEN)

    def set_streaming(self) -> None:
        self._set_color(_TEAL)

    def _set_color(self, hex_color: str) -> None:
        self.setStyleSheet(f"""
            QLabel {{
                background-color: {hex_color};
                border-radius: {self._SIZE // 2}px;
            }}
        """)


# ── MainWindow ────────────────────────────────────────────────────────────────

class MainWindow(QMainWindow):
    """
    Top-level application window.

    Owns the StreamServer instance and wires all signals to UI slots.
    """

    def __init__(self) -> None:
        super().__init__()
        self._server: StreamServer | None = None
        self._frame_count = 0

        self._build_ui()
        self._apply_stylesheet()

    # ── UI construction ───────────────────────────────────────────────────────

    def _build_ui(self) -> None:
        self.setWindowTitle("Android Video Streamer — Desktop Receiver")
        self.resize(1000, 700)

        central = QWidget()
        self.setCentralWidget(central)
        root = QVBoxLayout(central)
        root.setContentsMargins(12, 12, 12, 12)
        root.setSpacing(10)

        root.addWidget(self._build_header())
        root.addWidget(self._build_video_area(), stretch=1)
        root.addWidget(self._build_stats_bar())
        root.addWidget(self._build_control_bar())

    # ── Header ────────────────────────────────────────────────────────────────

    def _build_header(self) -> QWidget:
        frame = QFrame()
        frame.setObjectName("headerFrame")
        layout = QHBoxLayout(frame)
        layout.setContentsMargins(16, 10, 16, 10)

        # Title
        title = QLabel("📡  Android Video Streamer")
        title.setObjectName("titleLabel")
        layout.addWidget(title)

        layout.addStretch()

        # Status dot + text
        self._status_dot  = StatusDot()
        self._status_label = QLabel("Idle — server not started")
        self._status_label.setObjectName("statusLabel")
        layout.addWidget(self._status_dot)
        layout.addSpacing(6)
        layout.addWidget(self._status_label)

        return frame

    # ── Video area ────────────────────────────────────────────────────────────

    def _build_video_area(self) -> VideoLabel:
        self._video_label = VideoLabel()
        return self._video_label

    # ── Stats bar ─────────────────────────────────────────────────────────────

    def _build_stats_bar(self) -> QWidget:
        frame = QFrame()
        frame.setObjectName("statsFrame")
        layout = QHBoxLayout(frame)
        layout.setContentsMargins(16, 8, 16, 8)
        layout.setSpacing(32)

        self._fps_label        = self._make_stat("FPS",        "—")
        self._resolution_label = self._make_stat("Resolution", "—")
        self._remote_label     = self._make_stat("Client",     "—")
        self._frames_label     = self._make_stat("Frames",     "0")

        for widget in (
            self._fps_label,
            self._resolution_label,
            self._remote_label,
            self._frames_label,
        ):
            layout.addWidget(widget)

        layout.addStretch()
        return frame

    @staticmethod
    def _make_stat(caption: str, value: str) -> QLabel:
        label = QLabel(f"<span style='color:{_TEXT_SEC};font-size:11px;"
                       f"text-transform:uppercase;letter-spacing:1px;'>"
                       f"{caption}</span><br>"
                       f"<span style='color:{_TEXT_PRI};font-size:14px;"
                       f"font-weight:600;'>{value}</span>")
        label.setObjectName(f"stat_{caption.lower()}")
        label.setTextFormat(Qt.RichText)
        label.setAlignment(Qt.AlignLeft | Qt.AlignVCenter)
        return label

    def _update_stat(self, label: QLabel, caption: str, value: str) -> None:
        label.setText(
            f"<span style='color:{_TEXT_SEC};font-size:11px;"
            f"text-transform:uppercase;letter-spacing:1px;'>"
            f"{caption}</span><br>"
            f"<span style='color:{_TEXT_PRI};font-size:14px;"
            f"font-weight:600;'>{value}</span>"
        )

    # ── Control bar ───────────────────────────────────────────────────────────

    def _build_control_bar(self) -> QWidget:
        frame = QFrame()
        frame.setObjectName("controlFrame")
        layout = QHBoxLayout(frame)
        layout.setContentsMargins(16, 10, 16, 10)
        layout.setSpacing(12)

        # Port label + spinbox
        port_label = QLabel("Port:")
        port_label.setObjectName("portLabel")
        self._port_spin = QSpinBox()
        self._port_spin.setObjectName("portSpin")
        self._port_spin.setRange(1024, 65535)
        self._port_spin.setValue(8080)
        self._port_spin.setFixedWidth(90)
        self._port_spin.setAlignment(Qt.AlignCenter)

        # Start button
        self._start_btn = QPushButton("▶  Start Server")
        self._start_btn.setObjectName("startBtn")
        self._start_btn.setFixedHeight(38)
        self._start_btn.clicked.connect(self._on_start_clicked)

        # Stop button
        self._stop_btn = QPushButton("■  Stop Server")
        self._stop_btn.setObjectName("stopBtn")
        self._stop_btn.setFixedHeight(38)
        self._stop_btn.setEnabled(False)
        self._stop_btn.clicked.connect(self._on_stop_clicked)

        layout.addWidget(port_label)
        layout.addWidget(self._port_spin)
        layout.addSpacing(8)
        layout.addWidget(self._start_btn)
        layout.addWidget(self._stop_btn)
        layout.addStretch()

        # Host IP hint
        import socket as _sock
        try:
            hostname = _sock.gethostname()
            local_ip = _sock.gethostbyname(hostname)
        except Exception:
            local_ip = "127.0.0.1"
        hint = QLabel(f"Your IP: <b>{local_ip}</b>")
        hint.setObjectName("hintLabel")
        hint.setTextFormat(Qt.RichText)
        layout.addWidget(hint)

        return frame

    # ── Stylesheet ────────────────────────────────────────────────────────────

    def _apply_stylesheet(self) -> None:
        self.setStyleSheet(f"""
            QMainWindow, QWidget {{
                background-color: {_BG};
                color: {_TEXT_PRI};
                font-family: 'Segoe UI', sans-serif;
            }}

            /* ── Header ── */
            QFrame#headerFrame {{
                background-color: {_SURFACE};
                border: 1px solid {_BORDER};
                border-radius: 10px;
            }}
            QLabel#titleLabel {{
                font-size: 18px;
                font-weight: 700;
                color: {_TEXT_PRI};
            }}
            QLabel#statusLabel {{
                font-size: 13px;
                color: {_TEXT_SEC};
            }}

            /* ── Stats bar ── */
            QFrame#statsFrame {{
                background-color: {_SURFACE};
                border: 1px solid {_BORDER};
                border-radius: 10px;
            }}

            /* ── Control bar ── */
            QFrame#controlFrame {{
                background-color: {_SURFACE};
                border: 1px solid {_BORDER};
                border-radius: 10px;
            }}
            QLabel#portLabel {{
                font-size: 13px;
                color: {_TEXT_SEC};
            }}
            QLabel#hintLabel {{
                font-size: 12px;
                color: {_TEXT_SEC};
            }}
            QSpinBox#portSpin {{
                background-color: {_CARD};
                color: {_TEXT_PRI};
                border: 1px solid {_BORDER};
                border-radius: 6px;
                padding: 4px 8px;
                font-size: 14px;
            }}
            QSpinBox#portSpin::up-button, QSpinBox#portSpin::down-button {{
                width: 16px;
                background-color: {_CARD};
            }}

            /* ── Buttons ── */
            QPushButton#startBtn {{
                background-color: {_TEAL};
                color: {_BG};
                border: none;
                border-radius: 8px;
                padding: 0 20px;
                font-size: 14px;
                font-weight: 600;
            }}
            QPushButton#startBtn:hover {{
                background-color: {_TEAL_DARK};
            }}
            QPushButton#startBtn:disabled {{
                background-color: {_BORDER};
                color: {_TEXT_SEC};
            }}
            QPushButton#stopBtn {{
                background-color: {_RED};
                color: white;
                border: none;
                border-radius: 8px;
                padding: 0 20px;
                font-size: 14px;
                font-weight: 600;
            }}
            QPushButton#stopBtn:hover {{
                background-color: #C62828;
            }}
            QPushButton#stopBtn:disabled {{
                background-color: {_BORDER};
                color: {_TEXT_SEC};
            }}
        """)

    # ── Button handlers ───────────────────────────────────────────────────────

    @pyqtSlot()
    def _on_start_clicked(self) -> None:
        port = self._port_spin.value()
        self._server = StreamServer(port)

        # Wire server signals
        self._server.server_started.connect(self._on_server_started)
        self._server.server_stopped.connect(self._on_server_stopped)
        self._server.server_error.connect(self._on_server_error)
        self._server.client_connected.connect(self._on_client_connected)
        self._server.client_disconnected.connect(self._on_client_disconnected)
        self._server.frame_ready.connect(self._on_frame_ready)
        self._server.stats_updated.connect(self._on_stats_updated)

        self._server.start()

        # Update UI immediately (server_started will confirm)
        self._start_btn.setEnabled(False)
        self._port_spin.setEnabled(False)
        self._stop_btn.setEnabled(True)
        self._status_dot.set_connecting()

    @pyqtSlot()
    def _on_stop_clicked(self) -> None:
        self._shutdown_server()

    # ── Server signal handlers ────────────────────────────────────────────────

    @pyqtSlot(int)
    def _on_server_started(self, port: int) -> None:
        self._status_dot.set_connecting()
        self._status_label.setText(f"Listening on port {port} — waiting for client…")

    @pyqtSlot()
    def _on_server_stopped(self) -> None:
        self._status_dot.set_disconnected()
        self._status_label.setText("Idle — server not started")
        self._start_btn.setEnabled(True)
        self._port_spin.setEnabled(True)
        self._stop_btn.setEnabled(False)
        self._video_label.clear_frame()
        self._reset_stats()

    @pyqtSlot(str)
    def _on_server_error(self, message: str) -> None:
        self._status_dot.set_disconnected()
        self._status_label.setText(f"Error: {message}")
        self._start_btn.setEnabled(True)
        self._port_spin.setEnabled(True)
        self._stop_btn.setEnabled(False)

    @pyqtSlot(str)
    def _on_client_connected(self, remote_addr: str) -> None:
        self._status_dot.set_streaming()
        self._status_label.setText(f"Connected — {remote_addr}")
        self._frame_count = 0
        self._update_stat(self._remote_label, "Client", remote_addr)

    @pyqtSlot()
    def _on_client_disconnected(self) -> None:
        port = self._port_spin.value()
        self._status_dot.set_connecting()
        self._status_label.setText(
            f"Client disconnected — listening on port {port}…"
        )
        self._video_label.clear_frame()
        self._update_stat(self._fps_label,        "FPS",        "—")
        self._update_stat(self._resolution_label, "Resolution", "—")
        self._update_stat(self._remote_label,     "Client",     "—")

    # ── Frame / stats signal handlers ─────────────────────────────────────────

    @pyqtSlot(np.ndarray)
    def _on_frame_ready(self, frame_bgr: np.ndarray) -> None:
        self._frame_count += 1
        self._video_label.display_frame(frame_bgr)
        self._update_stat(
            self._frames_label, "Frames", f"{self._frame_count:,}"
        )

    @pyqtSlot(float, int, int)
    def _on_stats_updated(self, fps: float, width: int, height: int) -> None:
        self._update_stat(self._fps_label, "FPS", f"{fps:.1f}")
        self._update_stat(
            self._resolution_label, "Resolution", f"{width} × {height}"
        )

    # ── Helpers ───────────────────────────────────────────────────────────────

    def _shutdown_server(self) -> None:
        if self._server is not None:
            self._server.stop()
            self._server.wait()
            self._server = None

    def _reset_stats(self) -> None:
        self._frame_count = 0
        self._update_stat(self._fps_label,        "FPS",        "—")
        self._update_stat(self._resolution_label, "Resolution", "—")
        self._update_stat(self._remote_label,     "Client",     "—")
        self._update_stat(self._frames_label,     "Frames",     "0")

    # ── Window close ─────────────────────────────────────────────────────────

    def closeEvent(self, event) -> None:
        self._shutdown_server()
        super().closeEvent(event)
