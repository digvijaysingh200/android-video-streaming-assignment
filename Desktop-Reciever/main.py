"""
main.py — Entry point for the Android Video Streamer Desktop Receiver.

Run with:
    python main.py

Dependencies:
    pip install PyQt5 opencv-python numpy
"""

import sys
from PyQt5.QtWidgets import QApplication
from PyQt5.QtCore import Qt
from gui import MainWindow, apply_dark_palette


def main() -> None:
    # High-DPI support (must be set before QApplication is created)
    QApplication.setAttribute(Qt.AA_EnableHighDpiScaling, True)
    QApplication.setAttribute(Qt.AA_UseHighDpiPixmaps, True)

    app = QApplication(sys.argv)
    app.setApplicationName("Android Video Streamer — Receiver")
    app.setOrganizationName("VideoStreamer")

    apply_dark_palette(app)

    window = MainWindow()
    window.show()

    sys.exit(app.exec_())


if __name__ == "__main__":
    main()
