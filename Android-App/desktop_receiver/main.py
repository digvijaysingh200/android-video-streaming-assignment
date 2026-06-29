"""
main.py — Entry point for the Android Video Streamer Desktop Receiver.

Bootstraps the QApplication and launches the MainWindow.
"""

import sys
from PyQt5.QtWidgets import QApplication
from PyQt5.QtCore import Qt
from gui import MainWindow


def main() -> None:
    # Enable High-DPI scaling
    QApplication.setAttribute(Qt.AA_EnableHighDpiScaling, True)
    QApplication.setAttribute(Qt.AA_UseHighDpiPixmaps, True)

    app = QApplication(sys.argv)
    app.setApplicationName("Android Video Streamer — Receiver")
    app.setOrganizationName("VideoStreamer")

    window = MainWindow()
    window.show()

    sys.exit(app.exec_())


if __name__ == "__main__":
    main()
