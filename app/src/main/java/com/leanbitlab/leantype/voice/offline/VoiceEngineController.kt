// SPDX-License-Identifier: GPL-3.0-only
package com.leanbitlab.leantype.voice.offline

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import com.leanbitlab.leantype.voice.IVoiceCallback
import com.leanbitlab.leantype.voice.IVoiceEngine
import com.leanbitlab.leantype.voice.ModelImportRequest
import com.leanbitlab.leantype.voice.ModelState
import com.leanbitlab.leantype.voice.VoiceConstants
import com.leanbitlab.leantype.voice.VoiceEngineInfo
import com.leanbitlab.leantype.voice.VoiceSessionConfig
import com.leanbitlab.leantype.voice.offline.engine.ParakeetTdtEngine
import com.leanbitlab.leantype.voice.offline.engine.WhisperEngine
import com.leanbitlab.leantype.voice.offline.model.ModelManager
import helium314.keyboard.latin.utils.prefs
import java.io.File

class VoiceEngineController(private val context: Context) : IVoiceEngine.Stub() {

    val whisperEngine = WhisperEngine()
    val parakeetEngine = ParakeetTdtEngine()
    val gigaamEngine = ParakeetTdtEngine()
    val modelManager = ModelManager(context) { engineType ->
        try {
            when (engineType) {
                VoiceConstants.ENGINE_WHISPER -> whisperEngine.releaseContext()
                VoiceConstants.ENGINE_PARAKEET -> parakeetEngine.releaseContext()
                VoiceConstants.ENGINE_GIGAAM -> gigaamEngine.releaseContext()
            }
        } catch (_: Throwable) {}
    }

    @Volatile private var isSessionActive = false

    override fun getInfo(): VoiceEngineInfo {
        return VoiceEngineInfo(
            contractVersion = VoiceConstants.VOICE_CONTRACT_VERSION,
            pluginId = context.packageName,
            displayName = "LeanType Voice (GigaAM, Parakeet & Whisper)",
            supportsVosk = false,
            supportsWhisper = true,
            supportsHybrid = false
        )
    }

    override fun getModelState(engineType: String?): ModelState {
        val type = engineType ?: VoiceConstants.ENGINE_WHISPER
        return try {
            modelManager.getModelState(type)
        } catch (t: Throwable) {
            Log.e(TAG, "Error getting model state for $type", t)
            ModelState(type, ModelState.STATE_ERROR, t.message)
        }
    }

    fun importModelDirectly(request: ModelImportRequest): Pair<Boolean, String> {
        return modelManager.importModelDirectly(request)
    }

    override fun importModel(request: ModelImportRequest?) {
        if (request != null) {
            try {
                modelManager.importModelDirectly(request)
            } catch (t: Throwable) {
                Log.e(TAG, "importModel failed", t)
            }
        }
    }

    override fun unloadModel(engineType: String?) {
        val type = engineType ?: VoiceConstants.ENGINE_WHISPER
        try {
            when (type) {
                VoiceConstants.ENGINE_PARAKEET -> parakeetEngine.releaseContext()
                VoiceConstants.ENGINE_GIGAAM -> gigaamEngine.releaseContext()
                else -> whisperEngine.releaseContext()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error unloading model $type", t)
        }
    }

    override fun deleteModel(engineType: String?) {
        val type = engineType ?: VoiceConstants.ENGINE_WHISPER
        try {
            unloadModel(type)
            modelManager.deleteModel(type)
        } catch (t: Throwable) {
            Log.e(TAG, "Error deleting model $type", t)
        }
    }

    override fun startSession(
        config: VoiceSessionConfig?,
        audioInput: ParcelFileDescriptor?,
        callback: IVoiceCallback?
    ) {
        if (audioInput == null || callback == null) return

        try {
            if (isSessionActive) {
                try { whisperEngine.cancelSession() } catch (_: Throwable) {}
                try { parakeetEngine.cancelSession() } catch (_: Throwable) {}
                try { gigaamEngine.cancelSession() } catch (_: Throwable) {}
                isSessionActive = false
            }

            val wrappedCallback = object : IVoiceCallback.Stub() {
                override fun onSessionStarted() {
                    try { callback.onSessionStarted() } catch (_: Throwable) {}
                }

                override fun onPartial(partialText: String?) {
                    try { callback.onPartial(partialText) } catch (_: Throwable) {}
                }

                override fun onFinal(finalText: String?) {
                    try { callback.onFinal(finalText) } catch (_: Throwable) {}
                }

                override fun onError(code: Int, message: String?) {
                    isSessionActive = false
                    try { callback.onError(code, message) } catch (_: Throwable) {}
                }

                override fun onSessionEnded() {
                    isSessionActive = false
                    try { callback.onSessionEnded() } catch (_: Throwable) {}
                }
            }

            val gigaamReady = modelManager.isModelReady(VoiceConstants.ENGINE_GIGAAM)
            val parakeetReady = modelManager.isModelReady(VoiceConstants.ENGINE_PARAKEET)
            val whisperReady = modelManager.isModelReady(VoiceConstants.ENGINE_WHISPER)

            val offlineEnginePref = try {
                context.prefs().getString(
                    VoiceConstants.PREF_OFFLINE_ENGINE,
                    VoiceConstants.OFFLINE_ENGINE_AUTO
                ) ?: VoiceConstants.OFFLINE_ENGINE_AUTO
            } catch (_: Throwable) {
                VoiceConstants.OFFLINE_ENGINE_AUTO
            }

            val lang = (config?.languageTag ?: "").lowercase().trim()
            val isExplicitRussian = lang.startsWith("ru") || lang == "rus"
            val isExplicitEnglish = lang.startsWith("en") || lang == "eng"

            val activeLanguage: String = if (lang.isNotEmpty() && lang != "auto") {
                lang
            } else {
                try {
                    helium314.keyboard.latin.RichInputMethodManager.getInstance().currentSubtypeLocale.language.lowercase()
                } catch (_: Throwable) {
                    java.util.Locale.getDefault().language.lowercase()
                }
            }
            val isRussian = isExplicitRussian || activeLanguage.startsWith("ru")
            val isEnglish = isExplicitEnglish || activeLanguage.startsWith("en")

            Log.i(TAG, "Engine routing: pref=$offlineEnginePref, lang=$lang, activeLang=$activeLanguage, isRussian=$isRussian, gigaamReady=$gigaamReady, parakeetReady=$parakeetReady, whisperReady=$whisperReady")

            // 1. Explicit user engine preference
            if (offlineEnginePref == VoiceConstants.OFFLINE_ENGINE_GIGAAM) {
                if (gigaamReady) {
                    startGigaAmSession(audioInput, wrappedCallback, config, callback)
                    return
                }
                try { audioInput.close() } catch (_: Throwable) {}
                callback.onError(VoiceConstants.VOICE_ERROR_MODEL_MISSING, "GigaAM v3 model not ready. Please download it in Voice Settings.")
                return
            } else if (offlineEnginePref == VoiceConstants.OFFLINE_ENGINE_PARAKEET) {
                if (parakeetReady) {
                    startParakeetSession(audioInput, wrappedCallback, config, callback)
                    return
                }
                try { audioInput.close() } catch (_: Throwable) {}
                callback.onError(VoiceConstants.VOICE_ERROR_MODEL_MISSING, "Parakeet TDT model not ready. Please download it in Voice Settings.")
                return
            } else if (offlineEnginePref == VoiceConstants.OFFLINE_ENGINE_WHISPER) {
                if (whisperReady) {
                    startWhisperSession(audioInput, wrappedCallback, config, callback)
                    return
                }
                try { audioInput.close() } catch (_: Throwable) {}
                callback.onError(VoiceConstants.VOICE_ERROR_MODEL_MISSING, "Whisper model not ready. Please download it in Voice Settings.")
                return
            }

            // 2. Auto-routing: match language to best model
            if (isRussian) {
                // Russian: GigaAM is state-of-the-art; fallback to Whisper
                if (gigaamReady) {
                    startGigaAmSession(audioInput, wrappedCallback, config, callback)
                    return
                }
                if (whisperReady) {
                    Log.i(TAG, "GigaAM not installed, using Whisper for Russian")
                    startWhisperSession(audioInput, wrappedCallback, config, callback)
                    return
                }
                try { audioInput.close() } catch (_: Throwable) {}
                callback.onError(VoiceConstants.VOICE_ERROR_MODEL_MISSING, "Russian voice typing requires GigaAM v3 or Whisper. Please download a model in Voice Settings.")
                return
            } else if (isEnglish) {
                // English: Parakeet TDT streaming is fastest; fallback to Whisper, then GigaAM
                if (parakeetReady) {
                    startParakeetSession(audioInput, wrappedCallback, config, callback)
                    return
                }
                if (whisperReady) {
                    startWhisperSession(audioInput, wrappedCallback, config, callback)
                    return
                }
                if (gigaamReady) {
                    startGigaAmSession(audioInput, wrappedCallback, config, callback)
                    return
                }
            } else {
                // Multilingual / other
                if (whisperReady) {
                    startWhisperSession(audioInput, wrappedCallback, config, callback)
                    return
                }
                if (gigaamReady) {
                    startGigaAmSession(audioInput, wrappedCallback, config, callback)
                    return
                }
                if (parakeetReady) {
                    startParakeetSession(audioInput, wrappedCallback, config, callback)
                    return
                }
            }

            try { audioInput.close() } catch (_: Throwable) {}
            callback.onError(VoiceConstants.VOICE_ERROR_MODEL_MISSING, "Voice model not ready. Please download a voice model in Voice Settings.")
        } catch (t: Throwable) {
            Log.e(TAG, "Fatal error in startSession", t)
            try { audioInput.close() } catch (_: Throwable) {}
            try {
                callback.onError(VoiceConstants.VOICE_ERROR_PLUGIN_CRASHED, "Voice session error: ${t.message}")
            } catch (_: Throwable) {}
        }
    }

    private fun startGigaAmSession(
        audioInput: ParcelFileDescriptor,
        wrappedCallback: IVoiceCallback,
        config: VoiceSessionConfig?,
        callback: IVoiceCallback
    ) {
        val gigaamModelDir = modelManager.getModelDir(VoiceConstants.ENGINE_GIGAAM)
        var gigaamLoaded = false
        try {
            gigaamLoaded = gigaamEngine.loadModel(gigaamModelDir, context)
        } catch (t: Throwable) {
            Log.e(TAG, "Exception loading GigaAM model", t)
        }

        if (gigaamLoaded) {
            Log.i(TAG, "Starting GigaAM v3 E2E RNN-T session")
            isSessionActive = true
            gigaamEngine.startSession(audioInput, wrappedCallback, config)
            return
        }

        val whisperReady = modelManager.isModelReady(VoiceConstants.ENGINE_WHISPER)
        if (whisperReady) {
            Log.w(TAG, "GigaAM load failed, falling back to Whisper")
            startWhisperSession(audioInput, wrappedCallback, config, callback)
            return
        }
        try { audioInput.close() } catch (_: Throwable) {}
        callback.onError(VoiceConstants.VOICE_ERROR_MODEL_INVALID, "Failed to initialize GigaAM model.")
    }

    private fun startParakeetSession(
        audioInput: ParcelFileDescriptor,
        wrappedCallback: IVoiceCallback,
        config: VoiceSessionConfig?,
        callback: IVoiceCallback
    ) {
        val parakeetModelDir = modelManager.getModelDir(VoiceConstants.ENGINE_PARAKEET)
        var parakeetLoaded = false
        try {
            parakeetLoaded = parakeetEngine.loadModel(parakeetModelDir, context)
        } catch (t: Throwable) {
            Log.e(TAG, "Exception loading Parakeet model", t)
        }

        if (parakeetLoaded) {
            Log.i(TAG, "Starting Parakeet TDT session")
            isSessionActive = true
            parakeetEngine.startSession(audioInput, wrappedCallback, config)
            return
        }

        val whisperReady = modelManager.isModelReady(VoiceConstants.ENGINE_WHISPER)
        if (whisperReady) {
            Log.w(TAG, "Parakeet load failed, falling back to Whisper")
            startWhisperSession(audioInput, wrappedCallback, config, callback)
            return
        }
        try { audioInput.close() } catch (_: Throwable) {}
        callback.onError(VoiceConstants.VOICE_ERROR_MODEL_INVALID, "Failed to initialize Parakeet TDT model.")
    }

    private fun startWhisperSession(
        audioInput: ParcelFileDescriptor,
        wrappedCallback: IVoiceCallback,
        config: VoiceSessionConfig?,
        callback: IVoiceCallback
    ) {
        Log.i(TAG, "Starting Whisper session")
        val whisperBaseDir = modelManager.getModelDir(VoiceConstants.ENGINE_WHISPER)
        val whisperModelFile = File(whisperBaseDir, "model.bin").takeIf { it.exists() } ?: whisperBaseDir

        var whisperLoaded = false
        try {
            whisperLoaded = whisperEngine.loadModel(whisperModelFile)
        } catch (t: Throwable) {
            Log.e(TAG, "Exception loading Whisper model", t)
        }

        if (!whisperLoaded) {
            try { audioInput.close() } catch (_: Throwable) {}
            callback.onError(VoiceConstants.VOICE_ERROR_MODEL_INVALID, "Failed to initialize Whisper model")
            return
        }

        isSessionActive = true
        whisperEngine.startSession(audioInput, wrappedCallback, config)
    }

    override fun stopSession() {
        isSessionActive = false
    }

    override fun cancelSession() {
        isSessionActive = false
        try { parakeetEngine.cancelSession() } catch (_: Throwable) {}
        try { gigaamEngine.cancelSession() } catch (_: Throwable) {}
        try { whisperEngine.cancelSession() } catch (_: Throwable) {}
    }

    override fun release() {
        isSessionActive = false
        try { parakeetEngine.releaseContext() } catch (_: Throwable) {}
        try { gigaamEngine.releaseContext() } catch (_: Throwable) {}
        try { whisperEngine.releaseContext() } catch (_: Throwable) {}
    }

    companion object {
        private const val TAG = "VoiceEngineController"
    }
}
