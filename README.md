Demo - Video Link

https://drive.google.com/file/d/1siMVpOOYBTbqAPFhM_MywKM72meQnES-/view?usp=drive_link





# Android Real-Time Video Streaming System

A low-latency, real-time video streaming system featuring a Kotlin/Jetpack Compose Android app that captures frames using CameraX, and a desktop receiver application written in Python (PyQt5 + OpenCV) that acts as the TCP server to display the live feed.

---

## 1. Project Overview

This repository contains two major components designed to work in tandem over a local area network (LAN):
1. **Android Client App**: Captures live feed from the rear camera, compresses frames to JPEG at 70% quality, and streams them over a raw TCP socket using a custom lightweight framing protocol.
2. **Python Desktop Receiver**: A PyQt5-based desktop interface running a multi-threaded TCP server. It listens for incoming streams, decodes JPEG payloads in real time using OpenCV, and provides live statistics (FPS, Resolution, Frame Counts, and Connection states).

### Key Features
* **Real-time CameraX Pipeline**: Uses CameraX `ImageAnalysis` bound to the lifecycle of the Compose UI.
* **Low-Latency TCP Streaming**: Optimized socket properties (disabled Nagle's algorithm via `tcpNoDelay` and buffered output streams).
* **Robust Multi-threaded Desktop Server**: PyQt5 application running independent server and frame decoding threads to keep the GUI responsive at high frame rates.
* **Auto-Teardown & Connection Monitoring**: The client auto-detects server shutdowns or network packet drops and stops frame analysis instantly to preserve battery and CPU.
* **Performance Dashboard**: Real-time stats showing active frame counts, exact resolution, client remote IP, and moving-average FPS.

---

## 2. System Architecture

The system uses a server-client architecture where the **Desktop Receiver acts as the Server** (listening on a configurable port) and the **Android App acts as the Client** (initiating the connection).

```
         +-----------------------------------------------------------+
         |                     ANDROID CLIENT APP                    |
         |                                                           |
         |  CameraX (ImageAnalysis)  --->  FrameAnalyzer (JPEG 70%)  |
         +---------------------------------------+-------------------+
                                                 |
                                                 | (SocketManager.send)
                                                 | TCP Connection
                                                 v
         +---------------------------------------+-------------------+
         |                  PYTHON DESKTOP RECEIVER (SERVER)         |
         |                                                           |
         |  [StreamServer Thread] ---> [FrameReceiver Thread]        |
         |                                     |                     |
         |                                     v                     |
         |                              [OpenCV Decoder]             |
         |                                     |                     |
         |                                     v                     |
         |                              [PyQt5 GUI Loop]             |
         +-----------------------------------------------------------+
```

---

## 3. Technical Design

### Android Frame Pipeline
The Android app utilizes CameraX `ImageAnalysis` configured with `STRATEGY_KEEP_ONLY_LATEST` to avoid memory backing issues if the network queue blocks.
* **YUV to RGB Conversion**: Utilizes `ImageProxy.toBitmap()` for zero-overhead, highly optimized internal hardware-accelerated color space conversion.
* **Rate Limiting**: Throttles analysis output to exactly **15 FPS** using an internal monotonic time-gate to save network bandwidth and processing cycles.
* **Concurrency**: Networking is dispatched off the Main thread onto `Dispatchers.IO`, protected by a Kotlin Coroutines `Mutex` lock to prevent interleaving of frame bytes.

### Python Receiver Threading Model
* **Main UI Thread**: Renders the PyQt5 window, custom aspect-ratio-scaled `VideoLabel`, and dark style sheets.
* **StreamServer Thread (`QThread`)**: Non-blocking TCP socket listener that loops `accept()`. It guarantees the app stays responsive even when starting/stopping the listener.
* **FrameReceiver Thread (`QThread`)**: Spawned on demand per connection. Reads size headers, streams payload chunks into memory buffers, converts byte buffers into NumPy arrays, decodes them via `cv2.imdecode`, and calculates a rolling-average FPS.

---

## 4. Network Protocol

The communication protocol is implemented directly over a raw TCP socket. There is no protocol overhead (like HTTP or WebSockets) to ensure minimal latency.

### Frame Wire Format
Each frame is sent sequentially as a single contiguous packet conforming to the following structure:

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                      Frame Size (N Bytes)                     |  <- 4-Byte Big-Endian Unsigned Integer
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                                                               |
|                       JPEG Encoded Payload                    |  <- N Bytes of binary JPEG data
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

* **Frame Size Header**: A 4-byte big-endian unsigned integer (equivalent to Java's `writeInt()` / Python's `struct.unpack(">I")`) representing the length of the trailing JPEG payload in bytes.
* **Frame Payload**: The raw JPEG byte array of length `N`.

---

## 5. Folder Structure

```
AndroidVideoStreamer/
├── app/                              # Android Application Module
│   ├── build.gradle.kts              # App-level dependencies (CameraX, Compose, etc.)
│   └── src/main/
│       ├── AndroidManifest.xml       # Permissions (Camera, Internet, Network state)
│       ├── java/com/example/androidvideostreamer/
│       │   ├── MainActivity.kt       # Permission request lifecycle & UI host
│       │   ├── camera/
│       │   │   ├── CameraManager.kt  # CameraX binding (Preview + ImageAnalysis)
│       │   │   └── FrameAnalyzer.kt  # FPS limiter & Bitmap JPEG compressor
│       │   ├── network/
│       │   │   ├── SocketManager.kt  # TCP socket & raw stream handling
│       │   │   └── VideoStreamingClient.kt # Stream session control state
│       │   ├── ui/
│       │   │   ├── StreamingScreen.kt # Compose UI layout, buttons & fields
│       │   │   └── theme/            # Material Theme styles (teal/dark schema)
│       │   └── viewmodel/
│       │       └── StreamingViewModel.kt # MVVM ViewModel managing states
│       └── res/
│           └── values/
│               └── themes.xml        # Theme configuration (no status-bar white flashes)
├── desktop_receiver/                 # Python Desktop Receiver Module
│   ├── main.py                       # Python App entry point
│   ├── gui.py                        # PyQt5 interface, widgets, and stylesheet
│   ├── server.py                     # Non-blocking StreamServer QThread
│   ├── receiver.py                   # Custom TCP frame payload consumer QThread
│   └── requirements.txt              # Python project dependencies
├── settings.gradle.kts
└── build.gradle.kts
```

---

## 6. Installation & Build Steps

### Prerequisite Specs
* **Android**: Android Studio Hedgehog (2023.1.1) or higher. Minimum SDK: API level 26 (Android 8.0).
* **Python**: Python 3.12 (older Python 3.x releases may work but are untested).

---

### Part A: Running the Desktop Receiver

1. **Navigate to the receiver directory**:
   ```bash
   cd desktop_receiver/
   ```

2. **Set up a Python virtual environment (Optional but Recommended)**:
   ```bash
   python -m venv venv
   # On Windows:
   venv\Scripts\activate
   # On macOS/Linux:
   source venv/bin/activate
   ```

3. **Install Dependencies**:
   ```bash
   pip install -r requirements.txt
   ```

4. **Launch the Receiver**:
   ```bash
   python main.py
   ```

5. **Start Server**: 
   * Note down the IP address displayed at the bottom right corner (e.g. `192.168.1.15`).
   * Select a port (default: `8080`) and click **▶ Start Server**.

---

### Part B: Running the Android App

1. Open Android Studio and click **File -> Open**. Select the `AndroidVideoStreamer` folder.
2. Wait for Gradle sync to finish.
3. Connect a physical Android device with **USB Debugging** enabled (using an emulator is supported, but front/back camera simulation performance varies).
4. Tap **Run** (`Shift + F10`) to compile and install on your device.
5. **Permissions**: Accept the runtime Camera permission pop-up.
6. **Connecting to Server**:
   * Enter the Desktop IP address noted from the Python GUI into the **IP Address** field.
   * Enter the matching port (default: `8080`).
   * Tap **Connect**. Status indicator should turn green and show **Connected**.
7. **Start Streaming**:
   * Tap **Start Streaming**. The viewport boundary will glow amber, a flashing **LIVE** badge will appear, and frames will start projecting onto your desktop monitor.

---

## 7. Performance Results

* **Latency**: Under **100ms** latency over typical standard home 5GHz Wi-Fi LAN setups.
* **Throughput**: ~1.5 - 3 Mbps depending on view complexity.
* **Rate Limits**: Locked at ~15 FPS.
* **Resolution**: ~640x480 (ideal balance between image clarity and low-latency throughput).

---

## 8. Limitations
* **Local Area Network Only**: Does not support NAT traversal or relay servers (STUN/TURN) out-of-the-box.
* **Unencrypted TCP**: Video stream data is sent unencrypted over cleartext TCP.
* **Single Client Restriction**: The Desktop TCP server accepts and processes only one connection at a time.

---

## 9. Future Improvements
* **H.264 Hardware Encoding**: Transition from JPEG compression to H.264/MediaCodec byte stream streaming to save massive network bandwidth.
* **RTP/RTSP Support**: Implement standard protocol compliance for integration with media players (VLC, OBS).
* **QUIC / UDP Transport**: Migrate the underlying connection layer to UDP/QUIC to prevent video stuttering caused by head-of-line blocking on lossy Wi-Fi networks.
* **End-to-End Encryption**: Wrap the TCP socket in TLS/SSL.

---

## 10. Screenshots Section

*(Place screenshot images of the Android view and Python Desktop app here)*

| Jetpack Compose App UI | Desktop Receiver UI |
|---|---|
| ![Android UI Mockup](https://images.placeholder.tech/640x480/0d1117/e6edf3?text=Android+UI) | ![Desktop GUI Mockup](https://images.placeholder.tech/640x480/0d1117/e6edf3?text=Desktop+Receiver+UI) |
