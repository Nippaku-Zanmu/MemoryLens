# MemoryLens — Model Files Setup

All model files must be placed in `app/src/main/assets/models/` before building.
The app is fully offline; no model is downloaded at runtime.

---

## 1. MediaPipe Face Detection (Quantized)

**Filename:** `mediapipe_face_detection.tflite`  
**Destination:** `app/src/main/assets/models/mediapipe_face_detection.tflite`

**Source — Qualcomm AI Hub:**
- Visit: https://aihub.qualcomm.com/models/mediapipe_face  
- Select the **Quantized** variant
- Download the `.tflite` export
- Rename if needed to `mediapipe_face_detection.tflite`

**Alternative (MediaPipe official):**
- Visit: https://developers.google.com/mediapipe/solutions/vision/face_detector  
- Download `blaze_face_short_range.tflite`
- Rename to `mediapipe_face_detection.tflite`

---

## 2. CavaFace Face Embedding Model

**Filename (primary):** `cavaface.pte`  ← ExecuTorch format  
**Filename (fallback):** `cavaface.tflite`  ← TensorFlow Lite format  
**Destination:** `app/src/main/assets/models/`

**Source — Qualcomm AI Hub:**
- Visit: https://aihub.qualcomm.com/models/cavaface  
- Export for Android (select ExecuTorch / `.pte` format for primary runtime)
- Also export `.tflite` for fallback
- Place both files in assets/models/

**Notes:**
- Input: `[1, 3, 112, 112]` float32 (CHW, normalised to [-1, 1])
- Output: `[1, 512]` float32 — L2-normalised embedding vector
- The app averages 5 embeddings during enrollment

---

## 3. Llama 3.2 1B-Instruct (Quantized w4/w8)

**Filename:** `llama3_2_1b_instruct.pte`  
**Destination:** `app/src/main/assets/models/llama3_2_1b_instruct.pte`

**Source — Qualcomm AI Hub + qai_hub_models export:**

### Step 1 — Install qai_hub_models
```bash
pip install qai-hub-models
```

### Step 2 — Export the model
```bash
python -m qai_hub_models.models.llama_v3_2_1b_instruct.export \
    --device "Samsung Galaxy S23" \
    --target-runtime executorch \
    --quantize w4a16
```
This produces a `.pte` file. Rename it to `llama3_2_1b_instruct.pte`.

**Alternative — Meta's ExecuTorch export:**
- Follow: https://github.com/pytorch/executorch/tree/main/examples/models/llama  
- Use `export_llama.py` with `--model llama3_2` and `--quantization 4bit`

### Step 3 — Copy to assets
```bash
cp llama3_2_1b_instruct.pte <project>/app/src/main/assets/models/
```

**File size:** ~700 MB (w4 quantized). Ensure you have enough storage.

---

## 4. ExecuTorch Android AAR

**Filename:** `executorch-android-release.aar` (or similar)  
**Destination:** `app/libs/`

**Download:**
- Releases page: https://github.com/pytorch/executorch/releases  
- Look for `executorch-android-*.aar` in the latest stable release assets
- Place the `.aar` file in `app/libs/`
- The `build.gradle` already includes `fileTree(dir: 'libs', include: ['*.aar'])` to pick it up automatically

---

## Summary Table

| File | Size (approx) | Runtime |
|------|--------------|---------|
| `mediapipe_face_detection.tflite` | ~1 MB | MediaPipe Tasks |
| `cavaface.pte` | ~20 MB | ExecuTorch |
| `cavaface.tflite` | ~20 MB | TFLite (fallback) |
| `llama3_2_1b_instruct.pte` | ~700 MB | ExecuTorch |
| `executorch-android-release.aar` | ~50 MB | build-time dependency |

---

## Verification

After placing files, build the app. On first launch:
1. Models are copied from assets to the app's cache directory
2. Each model loads in background (check Logcat for "loaded via ExecuTorch" messages)
3. If a model file is missing, the app shows an error dialog with these instructions

For questions about model exports, see the Qualcomm AI Hub documentation at https://aihub.qualcomm.com
