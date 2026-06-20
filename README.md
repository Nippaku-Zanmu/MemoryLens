# MemoryLens

A private, fully **offline** AI memory assistant for Android that helps people with cognitive decline recognize faces and recall conversation context in real time.

Point the phone camera at a person → face is detected and matched → a spoken memory cue is generated from past conversation transcripts. No cloud, no internet, no data leaves the device.

---

## Demo pipeline

```
Camera frame
   │
   ▼
ML Kit Face Detection  ──► bounding box overlay on screen
   │ (cropped 112×112)
   ▼
MobileFaceNet (TFLite)  ──► 192-dim embedding  ──► cosine similarity vs. enrolled faces
   │                                                        │
   │                                              Room SQLite DB lookup
   │                                                        │
   ▼                                                        ▼
On-device SpeechRecognizer  ──► session transcript ──► LlmRunner template
   │                                                        │
   └──────────────────────────────────────────────────────► ▼
                                                   Android TTS → spoken cue
```

Everything runs on-device. Airplane mode works.

---

## Requirements

| Tool | Version |
|------|---------|
| Android device | Android 12+ (API 31+), ~200 MB free |
| ADB (for sideloading) | any recent version |
| Docker (to build without Android Studio) | 24+ |
| Android Studio (alternative build path) | Hedgehog or newer |
| Java | 21 (bundled with Android Studio) |

The app has been tested on **Samsung Galaxy A34** (Android 16, MediaTek Dimensity 1080).

---

## Quick start — Docker (recommended for friends)

### 1. Clone
```bash
git clone https://github.com/adityashah841/MemoryLens.git
cd MemoryLens
```

### 2. Build the APK inside Docker
```bash
docker compose up --build
```
This downloads the Android SDK, compiles the project, and places the APK at:
```
output/memorylens-debug.apk
```
First build takes ~10–15 minutes (SDK + Gradle dependency download). Subsequent builds use Docker layer cache and finish in ~2 minutes.

### 3. Install on your Android phone
Enable **Developer Options → USB Debugging** on your Android phone, then:
```bash
adb install output/memorylens-debug.apk
```
Or copy `memorylens-debug.apk` to your phone and open it with a file manager (enable "Install unknown apps" in Settings → Security).

---

## Quick start — Android Studio

1. Open Android Studio → **File → Open** → select the `MemoryLens/` folder
2. Let Gradle sync complete (first sync downloads ~300 MB of dependencies)
3. Connect your Android phone via USB with USB Debugging enabled
4. Press ▶ **Run**

---

## Model files

Three TFLite model files are included in `app/src/main/assets/models/`:

| File | Size | Purpose |
|------|------|---------|
| `mediapipe_face_detection.tflite` | 570 KB | Face bounding box (unused at runtime — replaced by ML Kit) |
| `mediapipe_face_landmark_detection.tflite` | 2.4 MB | Landmark reference |
| `cavaface.tflite` | 5.2 MB | Face embedding (MobileFaceNet, 192-dim) |

See [MODELS_README.md](MODELS_README.md) for download sources and instructions for the hackathon Llama 3.2 1B model (not included — too large for git).

---

## App usage

### Enrolling a person
1. Open the app → tap the **☰ menu** (top-left) → **Enroll New Person**
2. Enter the person's **name** and **relationship** (e.g. "daughter", "doctor")
3. Tap **Capture Face Sample** five times while the person faces the camera
4. Tap **Confirm Enrollment**

### Recognising a person
1. Point the camera at an enrolled person
2. A bounding box appears → name card shows → spoken cue is generated
3. Press the green **Speak** button to hear the cue aloud
4. While the face is in frame the app transcribes the conversation in the background
5. When the person leaves for 5 seconds the transcript is saved automatically — no prompts

### Latency dashboard
Tap the **⏱** icon (top-right) to expand the live latency panel showing per-stage ms timings.

---

## Architecture

```
com.memorylens.app/
├── capture/
│   ├── CameraManager.kt        CameraX binding, frame throttler
│   ├── FrameThrottler.kt       Drop frames while pipeline is busy
│   ├── MicrophoneManager.kt    Legacy PCM ring buffer (unused in current build)
│   └── AudioTranscriber.kt     On-device SpeechRecognizer, session chaining
├── inference/
│   ├── FaceDetector.kt         ML Kit face detection
│   ├── CavaFaceRunner.kt       MobileFaceNet TFLite, GPU→CPU fallback
│   └── LlmRunner.kt            Template cue generator (Llama placeholder)
├── pipeline/
│   ├── PipelineOrchestrator.kt Full pipeline: detect→embed→lookup→cue→transcribe
│   └── PipelineState.kt        IDLE / FACE_DETECTED / IDENTIFIED / SPEAKING / UNKNOWN
├── storage/
│   ├── AppDatabase.kt          Room database
│   ├── PersonEntity.kt + Dao   Enrolled persons + embeddings
│   └── ConversationEntity.kt + Dao  Per-session transcripts
├── ui/
│   ├── MainActivity.kt         Camera preview, speak button, observers
│   ├── MainViewModel.kt        LiveData bridge, model loading
│   ├── EnrollmentActivity.kt   5-sample face enrollment
│   ├── EnrolledPersonsActivity.kt  Person list + delete
│   ├── FaceOverlayView.kt      Bounding box canvas overlay
│   └── LatencyDashboardView.kt Collapsible ⏱ panel
└── utils/
    ├── LatencyTracker.kt       Thread-safe per-stage ns→ms tracker
    ├── TtsManager.kt           Android TextToSpeech singleton
    └── ModelVerifier.kt        Pre-flight asset check before model load
```

---

## Hackathon roadmap (S25 Ultra / Snapdragon 8 Elite)

These features are implemented with `// TODO (Hackathon)` markers and need to be re-enabled on the target device:

- [ ] **CavaFace** (Qualcomm AI Hub) via ExecuTorch `.pte` — replaces MobileFaceNet  
- [ ] **Llama 3.2 1B-Instruct** (quantized w4a16) via ExecuTorch — replaces template cue  
- [ ] **QNN delegate** for Snapdragon NPU — replaces CPU/GPU TFLite  

See [MODELS_README.md](MODELS_README.md) for exact export commands.

---

## Permissions

| Permission | Purpose |
|------------|---------|
| `CAMERA` | Live face detection |
| `RECORD_AUDIO` | Session speech transcription |
| `FOREGROUND_SERVICE` | Continuous capture |

No internet permission is requested. The app cannot make network calls.

---

## License

MIT — see [LICENSE](LICENSE) for details.  
Model weights retain their original licenses (MediaPipe Apache 2.0, MobileFaceNet MIT).
