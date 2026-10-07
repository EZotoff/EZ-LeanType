// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import com.leanbitlab.leantype.voice.VoiceConstants

data class VoiceModelItem(
    val id: String,
    val displayName: String,
    val engineType: String,
    val language: String,
    val languageCode: String = "",
    val sizeMb: String,
    val downloadUrl: String,
    val backupDownloadUrl: String = "",
    val browserUrl: String,
    val description: String = ""
)

object VoiceModelRegistry {
    val gigaamModels = listOf(
        VoiceModelItem(
            id = "gigaam-v3-e2e-rnnt-int8",
            displayName = "GigaAM v3 E2E RNN-T (Russian SOTA)",
            engineType = VoiceConstants.ENGINE_GIGAAM,
            language = "Russian",
            languageCode = "ru",
            sizeMb = "205 MB",
            downloadUrl = "https://github.com/EZotoff/EZ-LeanType/releases/download/v4.2.7/gigaam-v3-e2e-rnnt.zip",
            backupDownloadUrl = "http://127.0.0.1:8080/gigaam-v3-e2e-rnnt.zip",
            browserUrl = "https://github.com/EZotoff/EZ-LeanType/releases/download/v4.2.7/gigaam-v3-e2e-rnnt.zip",
            description = "Sber GigaAM v3 E2E RNN-T INT8: State-of-the-art Russian ASR with native punctuation and capitalization. 8.4% WER, runs on Sherpa-ONNX with real-time speed."
        )
    )

    val parakeetModels = listOf(
        VoiceModelItem(
            id = "parakeet-tdt-110m-int8",
            displayName = "Parakeet TDT 110M (Streaming)",
            engineType = VoiceConstants.ENGINE_PARAKEET,
            language = "English",
            languageCode = "en",
            sizeMb = "131 MB",
            downloadUrl = "https://github.com/EZotoff/EZ-LeanType/releases/download/v4.2.7/parakeet-tdt-110m.zip",
            backupDownloadUrl = "http://127.0.0.1:8080/parakeet-tdt-110m.zip",
            browserUrl = "https://github.com/EZotoff/EZ-LeanType/releases/download/v4.2.7/parakeet-tdt-110m.zip",
            description = "FastConformer TDT v3: Real-time word streaming with RTF < 0.05 on Nothing Phone (2)."
        )
    )

    val whisperModels = listOf(
        VoiceModelItem(
            id = "whisper-large-v3-turbo-q5_0",
            displayName = "Whisper Large-v3-Turbo",
            engineType = VoiceConstants.ENGINE_WHISPER,
            language = "Multilingual",
            languageCode = "mul",
            sizeMb = "548 MB",
            downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin",
            backupDownloadUrl = "http://127.0.0.1:8080/ggml-large-v3-turbo-q5_0.bin",
            browserUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin",
            description = "809M parameter flagship Whisper model. High accuracy for multilingual speech."
        ),
        VoiceModelItem(
            id = "whisper-small-q5_1",
            displayName = "Whisper Small (High Accuracy)",
            engineType = VoiceConstants.ENGINE_WHISPER,
            language = "Multilingual",
            languageCode = "mul",
            sizeMb = "182 MB",
            downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin",
            backupDownloadUrl = "http://127.0.0.1:8080/ggml-small-q5_1.bin",
            browserUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin",
            description = "Maximum balance of accuracy and size for multilingual vocabulary."
        ),
        VoiceModelItem(
            id = "whisper-base-q5_1",
            displayName = "Whisper Base",
            engineType = VoiceConstants.ENGINE_WHISPER,
            language = "Multilingual",
            languageCode = "mul",
            sizeMb = "57 MB",
            downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin",
            backupDownloadUrl = "http://127.0.0.1:8080/ggml-base-q5_1.bin",
            browserUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin",
            description = "Compact multilingual Whisper model."
        ),
        VoiceModelItem(
            id = "whisper-tiny-q5_1",
            displayName = "Whisper Tiny (Ultra-Fast)",
            engineType = VoiceConstants.ENGINE_WHISPER,
            language = "Multilingual",
            languageCode = "mul",
            sizeMb = "32 MB",
            downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin",
            backupDownloadUrl = "http://127.0.0.1:8080/ggml-tiny-q5_1.bin",
            browserUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin",
            description = "Ultra-fast on Snapdragon 8+ Gen 1 (~100ms response). Pre-quantized Q5_1 GGML."
        )
    )

    val phononModels = listOf(
        VoiceModelItem(
            id = "phonon-2-onnx-int8",
            displayName = "Phonon-2 (English, High Accuracy)",
            engineType = VoiceConstants.ENGINE_PHONON,
            language = "English",
            languageCode = "en",
            sizeMb = "690 MB",
            downloadUrl = "https://huggingface.co/tiyuvta/Phonon-2-ONNX/resolve/main/encoder-model.int8.onnx",
            backupDownloadUrl = "",
            browserUrl = "https://huggingface.co/tiyuvta/Phonon-2-ONNX",
            description = "Fermion Phonon-2 (CC-BY-4.0), int8 ONNX export: 5.21% WER English ASR. Runs on onnxruntime. Note: the full set needs preprocessor-model.onnx, encoder-model.int8.onnx, decoder_joint-model.int8.onnx and vocab.txt from the export repo (zip them together for import)."
        )
    )

    fun findById(id: String): VoiceModelItem? {
        return gigaamModels.firstOrNull { it.id == id }
            ?: parakeetModels.firstOrNull { it.id == id }
            ?: phononModels.firstOrNull { it.id == id }
            ?: whisperModels.firstOrNull { it.id == id }
    }
}
