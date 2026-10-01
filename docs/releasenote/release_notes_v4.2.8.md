## 🚀 What's New in EZ LeanType v4.2.8

### ✨ Highlights
- **Suggestion Backspace Desync Fix**: Fixed the issue where tapping backspace twice immediately after picking a word from the suggestion strip deleted the space *before* the word (`"helloworld"` instead of `"hello worl"`).
  - **Composing Text Bounds Safety**: In [`RichInputConnection`](file:///workspace/wise-bose/app/src/main/java/helium314/keyboard/latin/RichInputConnection.kt), `deleteSurroundingText` and `deleteTextBeforeCursor` now safely handle active composing spans (`SPAN_COMPOSING`). Previously, Android's platform `deleteSurroundingText` strictly deleted characters outside/before the active composing span, causing the preceding space to be deleted instead of the intended character.
  - **Manual Pick Deactivation**: Restored `mLastComposedWord.deactivate()` in [`onPickSuggestionManually`](file:///workspace/wise-bose/app/src/main/java/helium314/keyboard/latin/inputlogic/InputLogic.kt) to prevent manual suggestion selections from being mangled by autocorrect revert logic.
  - **Space State Integrity**: Fixed space state initialization on immediate auto-space to `SpaceState.NONE` instead of `SpaceState.DOUBLE`.
- **Direct Voice Model Downloads**: Included verified, prepackaged voice model archives directly in release assets for 1-click downloads:
  - **GigaAM v3 RNN-T INT8** (`gigaam-v3-e2e-rnnt.zip`) for Russian ASR (8.4% WER, native punctuation & casing).
  - **Parakeet TDT 110M INT8** (`parakeet-tdt-110m.zip`) for real-time English streaming ASR (RTF < 0.05).
- **Voice Engine Stability & Spoken Punctuation**:
  - Eliminated runtime ART regex crashes during voice recognition startup.
  - Added spoken punctuation mapping for Russian and English voice input.
- **Branding & Maintenance**:
  - Rebranded as **EZ LeanType** maintained by Evgeny Zotov ([@EZotoff](https://github.com/EZotoff)).

---

### 📦 Release Assets

| Asset | Type | Description |
|:---|:---|:---|
| **`EZ-LeanType-v4.2.8-debug.apk`** | App APK | EZ LeanType v4.2.8 debug build with offline voice, handwriting, and layout engines |
| **`gigaam-v3-e2e-rnnt.zip`** | Voice Model | Sber GigaAM v3 INT8 offline model for Russian |
| **`parakeet-tdt-110m.zip`** | Voice Model | NeMo Parakeet TDT 110M INT8 streaming model for English |
