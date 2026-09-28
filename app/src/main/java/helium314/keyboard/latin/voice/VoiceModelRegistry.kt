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
            downloadUrl = "http://127.0.0.1:8080/gigaam-v3-e2e-rnnt.zip",
            backupDownloadUrl = "http://192.168.50.52:8080/gigaam-v3-e2e-rnnt.zip",
            browserUrl = "http://127.0.0.1:8080/gigaam-v3-e2e-rnnt.zip",
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
            downloadUrl = "http://127.0.0.1:8080/parakeet-tdt-110m.zip",
            backupDownloadUrl = "http://192.168.50.52:8080/parakeet-tdt-110m.zip",
            browserUrl = "http://127.0.0.1:8080/parakeet-tdt-110m.zip",
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
            downloadUrl = "http://127.0.0.1:8080/ggml-large-v3-turbo-q5_0.bin",
            backupDownloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin",
            browserUrl = "http://127.0.0.1:8080/ggml-large-v3-turbo-q5_0.bin",
            description = "809M parameter flagship Whisper model. High accuracy for multilingual speech."
        ),
        VoiceModelItem(
            id = "whisper-small-q5_1",
            displayName = "Whisper Small (High Accuracy)",
            engineType = VoiceConstants.ENGINE_WHISPER,
            language = "Multilingual",
            languageCode = "mul",
            sizeMb = "182 MB",
            downloadUrl = "http://127.0.0.1:8080/ggml-small-q5_1.bin",
            backupDownloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin",
            browserUrl = "http://127.0.0.1:8080/ggml-small-q5_1.bin",
            description = "Maximum balance of accuracy and size for multilingual vocabulary."
        ),
        VoiceModelItem(
            id = "whisper-base-q5_1",
            displayName = "Whisper Base",
            engineType = VoiceConstants.ENGINE_WHISPER,
            language = "Multilingual",
            languageCode = "mul",
            sizeMb = "57 MB",
            downloadUrl = "http://127.0.0.1:8080/ggml-base-q5_1.bin",
            backupDownloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin",
            browserUrl = "http://127.0.0.1:8080/ggml-base-q5_1.bin",
            description = "Compact multilingual Whisper model."
        ),
        VoiceModelItem(
            id = "whisper-tiny-q5_1",
            displayName = "Whisper Tiny (Ultra-Fast)",
            engineType = VoiceConstants.ENGINE_WHISPER,
            language = "Multilingual",
            languageCode = "mul",
            sizeMb = "32 MB",
            downloadUrl = "http://127.0.0.1:8080/ggml-tiny-q5_1.bin",
            backupDownloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin",
            browserUrl = "http://127.0.0.1:8080/ggml-tiny-q5_1.bin",
            description = "Ultra-fast on Snapdragon 8+ Gen 1 (~100ms response). Pre-quantized Q5_1 GGML."
        )
    )

    fun findById(id: String): VoiceModelItem? {
        return gigaamModels.firstOrNull { it.id == id }
            ?: parakeetModels.firstOrNull { it.id == id }
            ?: whisperModels.firstOrNull { it.id == id }
    }
}
