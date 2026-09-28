# LeanType Offline Speech-to-Text (STT) Models Guide

LeanType integrates a multi-engine on-device speech-to-text pipeline that operates completely offline without network permissions or external companion apps.

---

## 🎯 Supported Engines & Models

### 1. Sber GigaAM v3 E2E RNN-T (Russian SOTA)
- **Primary Language**: Russian (`ru`)
- **Architecture**: Conformer Encoder + RNN-T Decoder/Joint with built-in capitalization and punctuation
- **Accuracy**: **8.4% WER** on Russian benchmarks (surpassing Whisper models by up to 3x)
- **Quantization**: INT8
- **Inference Runtime**: Sherpa-ONNX / ONNX Runtime Mobile
- **Model Size**: ~205 MB compressed (.zip), ~326 MB uncompressed
- **Files inside archive**:
  - `encoder.int8.onnx` (`gigaam_v3_e2e_rnnt_encoder_int8.onnx`)
  - `decoder.onnx` (`gigaam_v3_e2e_rnnt_decoder.onnx`)
  - `joiner.onnx` (`gigaam_v3_e2e_rnnt_joint.onnx`)
  - `tokens.txt` (`gigaam_v3_e2e_rnnt_tokens.txt`)
  - `silero_vad.onnx` (for continuous VAD silence segmentation)
- **Links**:
  - **Hugging Face**: [pantinor/gigaam-v3](https://huggingface.co/pantinor/gigaam-v3)
  - **Direct Download**: [`gigaam-v3-e2e-rnnt.zip`](https://github.com/LeanBitLab/LeanType/releases/download/beta-427-1/gigaam-v3-e2e-rnnt.zip)

### 2. NeMo Parakeet TDT 110M (English Streaming)
- **Primary Language**: English (`en`)
- **Architecture**: FastConformer Transducer with Token-and-Duration Transducer (TDT) decoding
- **Latency / Performance**: Real-time streaming word-by-word with **RTF < 0.05** (sub-50ms latency)
- **Quantization**: INT8
- **Inference Runtime**: Sherpa-ONNX / ONNX Runtime Mobile
- **Model Size**: ~131 MB compressed (.zip), ~137 MB uncompressed
- **Files inside archive**:
  - `encoder.int8.onnx`
  - `decoder.int8.onnx`
  - `joiner.int8.onnx`
  - `tokens.txt`
  - `silero_vad.onnx`
- **Links**:
  - **Hugging Face**: [csukuangfj/sherpa-onnx-nemo-fast-conformer-tdt-en-110m](https://huggingface.co/csukuangfj/sherpa-onnx-nemo-fast-conformer-tdt-en-110m)
  - **Direct Download**: [`parakeet-tdt-110m.zip`](https://github.com/LeanBitLab/LeanType/releases/download/beta-427-1/parakeet-tdt-110m.zip)

### 3. Whisper (Multilingual)
- **Primary Languages**: 99+ languages (`mul`)
- **Architecture**: OpenAI Whisper encoder-decoder architecture
- **Inference Runtime**: `whisper.cpp` (native ARM NEON / FP16 assembly)
- **Quantization**: Q5_0 / Q5_1 GGML
- **Recommended Models**:
  - **Large-v3-Turbo (548 MB)**: [ggerganov/whisper.cpp (ggml-large-v3-turbo-q5_0.bin)](https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin)
  - **Small (182 MB)**: [ggerganov/whisper.cpp (ggml-small-q5_1.bin)](https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin)

---

## 📲 Step-by-Step Installation Instructions

### Step 1: Install LeanType APK
1. Download `1-LeanType_4.2.7-standard-debug.apk` (or the latest release APK).
2. Install the APK on your Android device (Android 6.0+ supported, Android 8.0+ recommended for Sherpa-ONNX).
3. Follow the onboarding setup to enable **LeanType** as an active input method.

### Step 2: Grant Microphone Permission
1. When you first tap the microphone icon on the keyboard toolbar, Android prompts for **Microphone permission**.
2. Tap **"While using the app"** (or grant manually via **Android Settings → Apps → LeanType → Permissions → Microphone**).

### Step 3: Download & Import Models
1. Download the model `.zip` file for your language:
   - For Russian: [`gigaam-v3-e2e-rnnt.zip`](https://github.com/LeanBitLab/LeanType/releases/download/beta-427-1/gigaam-v3-e2e-rnnt.zip)
   - For English: [`parakeet-tdt-110m.zip`](https://github.com/LeanBitLab/LeanType/releases/download/beta-427-1/parakeet-tdt-110m.zip)
2. Open LeanType Settings:
   - Long-press `,` (comma) or tap the Settings gear on the keyboard toolbar.
   - Navigate to **Voice typing** (or **Speech & Voice**).
   - Tap **Manage & Download Models**.
3. Under **Custom GigaAM Model** (or **Custom Parakeet Model**), tap **Import File**.
4. Use the system file picker to select the downloaded `.zip` file.
5. LeanType unpacks the model into the app's internal sandbox, verifies the ONNX files and tokens, and marks the model as **Ready**.

### Step 4: Configure Engine Routing
1. In **Settings → Voice typing → Offline Voice Engine**, choose:
   - **Auto** (Recommended): LeanType dynamically selects **GigaAM v3** when typing in Russian, **Parakeet TDT** when typing in English, and **Whisper** for other languages.
   - **GigaAM v3**: Forces Sber GigaAM v3 for all input.
   - **Parakeet TDT**: Forces Parakeet streaming for English.
   - **Whisper**: Uses whisper.cpp.

---

## ⌨️ Typing & UX Enhancements in this Build

- **Spacebar Cursor Navigation**: Long-pressing or resting on the spacebar no longer pops up the "Choose input method" picker dialog, preserving uninterrupted fluid cursor gliding.
- **Russian Hint Priority**: Long-tapping keys with hint labels (e.g. `е` with hint `5`, `ь` with hint `?`) puts the displayed hint symbol at position 0 (the default selected key on release), while keeping alternate Cyrillic letters (`ё`, `ъ`) immediately adjacent in the popup menu.
- **Personal Dictionary Learning**: Fixed word learning threshold and Russian/multilingual dictionary syncing.
