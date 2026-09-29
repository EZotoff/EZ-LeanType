// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.leanbitlab.leantype.voice.IVoiceCallback
import com.leanbitlab.leantype.voice.VoiceConstants
import com.leanbitlab.leantype.voice.VoiceSessionConfig
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.RichInputMethodManager
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.ProofreadService
import helium314.keyboard.latin.utils.prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class VoiceInputManager(
    private val ims: LatinIME,
    private val pluginManager: VoicePluginManager
) {

    enum class VoiceState {
        IDLE,
        CONNECTING_PLUGIN,
        STARTING_SESSION,
        RECORDING,
        PROCESSING_FINAL,
        ERROR
    }

    interface VoiceInputListener {
        fun onStateChanged(state: VoiceState)
        fun onError(message: String)
    }

    private var state = VoiceState.IDLE
    private var activeSessionId: String? = null

    private var audioRecord: AudioRecord? = null
    private var audioPipeWriteSide: ParcelFileDescriptor? = null
    private var audioPipeReadSide: ParcelFileDescriptor? = null

    private val isRecording = AtomicBoolean(false)
    private var audioThread: Thread? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastPartialText: String? = null
    private var handshakeTimeoutRunnable: Runnable? = null
    private var needsCapitalStart = true
    private var currentSegmentEmittedLength = 0
    private var lastFinalText: String? = null
    private var lastCommittedTrailingSpace = false

    private var isCurrentSessionOnline = false
    private val onlineAudioBuffer = ByteArrayOutputStream()
    private var onlineTranscriptionJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    private var listener: VoiceInputListener? = null

    fun setListener(listener: VoiceInputListener?) {
        this.listener = listener
    }

    fun getState(): VoiceState = state

    fun isVoiceActive(): Boolean = state != VoiceState.IDLE && state != VoiceState.ERROR

    fun isRecording(): Boolean = state == VoiceState.RECORDING || state == VoiceState.STARTING_SESSION

    fun canStartVoice(): Boolean {
        val provider = RichInputMethodManager.getInstance().currentVoiceProvider
        val offlineEnabled = provider == VoiceConstants.VOICE_PROVIDER_OFFLINE
        val onlineEnabled = provider == VoiceConstants.VOICE_PROVIDER_ONLINE
        if (!offlineEnabled && !onlineEnabled) {
            Log.w(TAG, "canStartVoice: Voice input not enabled in preferences")
            return false
        }
        if (ContextCompat.checkSelfPermission(ims, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "canStartVoice: Missing RECORD_AUDIO permission")
            return false
        }
        if (isBlockedEditor(ims.currentInputEditorInfo)) {
            Log.w(TAG, "canStartVoice: Blocked editor (password)")
            return false
        }
        return true
    }

    fun startVoice() {
        if (state == VoiceState.RECORDING) {
            stopVoice()
            return
        }

        if (state != VoiceState.IDLE && state != VoiceState.ERROR) {
            Log.w(TAG, "Resetting previous state $state for new voice session")
            cancelVoice()
        }

        if (!canStartVoice()) {
            notifyError("Voice input not available or permission missing")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                ims.requestShowSelf(0)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to requestShowSelf", e)
            }
        }

        val onlineEnabled = RichInputMethodManager.getInstance().currentVoiceProvider == VoiceConstants.VOICE_PROVIDER_ONLINE
        if (onlineEnabled) {
            val service = ProofreadService(ims)
            val provider = service.getProvider()
            val hasKey = when (provider) {
                ProofreadService.AIProvider.GEMINI -> service.hasApiKey()
                ProofreadService.AIProvider.GROQ -> !service.getGroqToken().isNullOrBlank()
                ProofreadService.AIProvider.OPENAI -> !service.getHuggingFaceToken().isNullOrBlank()
            }
            if (!hasKey) {
                notifyError("API key for ${provider.name} not configured. Set it in Settings → AI Integration")
                return
            }
            startOnlineVoice()
            return
        }

        val isConnected = pluginManager.isPluginConnected()
        Log.i(TAG, "startVoice: isConnected=$isConnected")

        try {
            ims.handler.cancelResumeSuggestions()
            ims.handler.cancelUpdateSuggestionStrip()
            ims.inputLogic.finishInput()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to finishInput before starting voice", e)
        }

        pluginManager.cancelSession()
        val sessionId = UUID.randomUUID().toString()
        activeSessionId = sessionId
        needsCapitalStart = true
        currentSegmentEmittedLength = 0
        lastFinalText = null
        lastPartialText = null
        lastCommittedTrailingSpace = false
        isCurrentSessionOnline = false

        val initialIc = ims.currentInputConnection
        initialIc?.finishComposingText()
        val beforeCursor = initialIc?.getTextBeforeCursor(2, 0)?.toString()
        if (!beforeCursor.isNullOrEmpty()) {
            val lastChar = beforeCursor.last()
            if (!Character.isWhitespace(lastChar)) {
                initialIc.commitText(" ", 1)
                lastCommittedTrailingSpace = true
            }
            val trimmedBefore = beforeCursor.trimEnd()
            if (trimmedBefore.isNotEmpty()) {
                val lastNonSpace = trimmedBefore.last()
                needsCapitalStart = lastNonSpace in ".!?"
            }
        }

        if (!isConnected) {
            updateState(VoiceState.CONNECTING_PLUGIN)
            pluginManager.setConnectionListener(object : VoicePluginManager.PluginConnectionListener {
                override fun onPluginConnected(info: com.leanbitlab.leantype.voice.VoiceEngineInfo?) {
                    mainHandler.post {
                        if (activeSessionId == sessionId && state == VoiceState.CONNECTING_PLUGIN) {
                            initiateSessionHandshake(sessionId)
                        }
                    }
                }

                override fun onPluginDisconnected() {
                    mainHandler.post {
                        if (activeSessionId == sessionId) {
                            notifyError("Plugin disconnected unexpectedly")
                            cleanupSession()
                            updateState(VoiceState.ERROR)
                        }
                    }
                }
            })

            val bound = pluginManager.bindIfNeeded()
            if (!bound) {
                notifyError("Failed to bind to voice plugin")
                updateState(VoiceState.ERROR)
                return
            }
        } else {
            initiateSessionHandshake(sessionId)
        }
    }

    private fun startOnlineVoice() {
        try {
            ims.handler.cancelResumeSuggestions()
            ims.handler.cancelUpdateSuggestionStrip()
            ims.inputLogic.finishInput()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to finishInput before starting online voice", e)
        }

        val sessionId = UUID.randomUUID().toString()
        activeSessionId = sessionId
        needsCapitalStart = true
        currentSegmentEmittedLength = 0
        lastFinalText = null
        lastPartialText = null
        lastCommittedTrailingSpace = false
        isCurrentSessionOnline = true

        val initialIc = ims.currentInputConnection
        initialIc?.finishComposingText()
        val beforeCursor = initialIc?.getTextBeforeCursor(2, 0)?.toString()
        if (!beforeCursor.isNullOrEmpty()) {
            val lastChar = beforeCursor.last()
            if (!Character.isWhitespace(lastChar)) {
                initialIc.commitText(" ", 1)
                lastCommittedTrailingSpace = true
            }
            val trimmedBefore = beforeCursor.trimEnd()
            if (trimmedBefore.isNotEmpty()) {
                val lastNonSpace = trimmedBefore.last()
                needsCapitalStart = lastNonSpace in ".!?"
            }
        }

        synchronized(onlineAudioBuffer) {
            onlineAudioBuffer.reset()
        }

        val started = startAudioRecordingThread()
        if (started) {
            updateState(VoiceState.RECORDING)
        } else {
            notifyError("Failed to start audio recording")
            cleanupSession()
            updateState(VoiceState.ERROR)
        }
    }

    private fun initiateSessionHandshake(sessionId: String) {
        if (state == VoiceState.CONNECTING_PLUGIN) {
            updateState(VoiceState.STARTING_SESSION)
        }

        val pipe: Array<ParcelFileDescriptor>
        try {
            pipe = ParcelFileDescriptor.createPipe()
            audioPipeReadSide = pipe[0]
            audioPipeWriteSide = pipe[1]
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create audio pipe", e)
            notifyError("Failed to create audio pipe")
            updateState(VoiceState.ERROR)
            return
        }

        val prefLang = ims.prefs().getString(VoiceConstants.PREF_VOICE_LANGUAGE, VoiceConstants.VOICE_LANG_FOLLOW_KEYBOARD)
            ?: VoiceConstants.VOICE_LANG_FOLLOW_KEYBOARD
        val languageTag = when (prefLang) {
            VoiceConstants.VOICE_LANG_AUTO -> "auto"
            VoiceConstants.VOICE_LANG_FOLLOW_KEYBOARD, "" -> {
                try {
                    RichInputMethodManager.getInstance().currentSubtypeLocale.toLanguageTag()
                } catch (_: Exception) {
                    java.util.Locale.getDefault().toLanguageTag()
                }
            }
            else -> prefLang
        }

        val threads = ims.prefs().getString(VoiceConstants.PREF_VOICE_CPU_THREADS, "4")?.toIntOrNull() ?: 4
        val customPrompt = ims.prefs().getString(VoiceConstants.PREF_VOICE_CUSTOM_PROMPT, "")?.trim()?.takeIf { it.isNotEmpty() }

        val config = VoiceSessionConfig(
            sessionId = sessionId,
            mode = VoiceConstants.MODE_ACCURATE,
            languageTag = languageTag,
            sampleRate = SAMPLE_RATE,
            enablePartial = true,
            maxSegmentMs = 5000,
            hybridTimeoutMs = 0,
            hybridFallbackToVosk = false,
            cpuThreads = threads,
            customPrompt = customPrompt
        )

        val callback = object : IVoiceCallback.Stub() {
            override fun onSessionStarted() {
                mainHandler.post {
                    if (activeSessionId == sessionId) {
                        cancelHandshakeTimeout()
                        updateState(VoiceState.RECORDING)
                    }
                }
            }

            override fun onPartial(text: String?) {
                Log.i(TAG, "Received onPartial from plugin: '$text'")
                mainHandler.post {
                    if (activeSessionId == sessionId && isRecording.get()) {
                        syncRecognizedText(text.orEmpty(), isFinal = false)
                    }
                }
            }

            override fun onFinal(text: String?) {
                Log.i(TAG, "Received onFinal: '$text' (isRecording=${isRecording.get()})")
                mainHandler.post {
                    if (activeSessionId == sessionId) {
                        syncRecognizedText(text.orEmpty(), isFinal = true)
                        lastPartialText = null

                        if (!isRecording.get()) {
                            Log.i(TAG, "Final session commit complete, transitioning to IDLE")
                            cleanupSession()
                            updateState(VoiceState.IDLE)
                        } else {
                            Log.i(TAG, "Segment refined & committed. Continuing continuous recording.")
                        }
                    }
                }
            }

            override fun onError(code: Int, message: String?) {
                Log.e(TAG, "Received onError: code=$code, message='$message'")
                mainHandler.post {
                    if (activeSessionId == sessionId) {
                        clearComposingText()
                        notifyError(message ?: "Voice error ($code)")
                        cleanupSession()
                        updateState(VoiceState.ERROR)
                    }
                }
            }

            override fun onSessionEnded() {
                Log.i(TAG, "Received onSessionEnded, state=$state")
                mainHandler.post {
                    if (activeSessionId == sessionId) {
                        val ic = ims.currentInputConnection
                        if (ic != null && currentSegmentEmittedLength > 0) {
                            ic.beginBatchEdit()
                            try {
                                ic.commitText(" ", 1)
                            } finally {
                                ic.endBatchEdit()
                            }
                            currentSegmentEmittedLength = 0
                        }
                        cleanupSession()
                        if (state != VoiceState.ERROR) {
                            updateState(VoiceState.IDLE)
                        }
                    }
                }
            }
        }

        // Set handshake timeout guard (8000 ms)
        handshakeTimeoutRunnable = Runnable {
            if (activeSessionId == sessionId && state == VoiceState.STARTING_SESSION) {
                Log.e(TAG, "Session handshake timed out")
                notifyError("Voice session handshake timed out")
                pluginManager.cancelSession()
                cleanupSession()
                updateState(VoiceState.ERROR)
            }
        }
        handshakeTimeoutRunnable?.let {
            mainHandler.postDelayed(it, HANDSHAKE_TIMEOUT_MS)
        }

        // Start hardware audio capture IMMEDIATELY so the green mic privacy dot appears without IPC delay
        startAudioRecordingThread()

        try {
            val pfdForPlugin = audioPipeReadSide
            if (pfdForPlugin != null) {
                audioPipeReadSide = null
                pluginManager.startSession(config, pfdForPlugin, callback)
            } else {
                throw IllegalStateException("Read-side pipe descriptor is null")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Remote exception starting voice session", e)
            cancelHandshakeTimeout()
            notifyError("Failed to start voice session with plugin")
            cleanupSession()
            updateState(VoiceState.ERROR)
        }
    }

    private fun startAudioRecordingThread(): Boolean {
        stopAudioLoop() // Prevent zombie thread overlap on rapid re-entry
        if (ContextCompat.checkSelfPermission(ims, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "startAudioRecordingThread: Missing RECORD_AUDIO permission")
            return false
        }

        val audioManager = ims.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
        try {
            if (audioManager?.isMicrophoneMute == true) {
                Log.w(TAG, "Microphone was muted in AudioManager, unmuting...")
                audioManager.isMicrophoneMute = false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unmute via AudioManager", e)
        }

        val minBufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufSize <= 0) {
            Log.e(TAG, "startAudioRecordingThread: Invalid min buffer size: $minBufSize")
            return false
        }

        // Multiply by 4 (at least 8192) to prevent hardware buffer overruns during Whisper inference blocks
        val bufferSize = maxOf(minBufSize * 4, FRAME_SIZE_BYTES * 8, 8192)

        val sources = intArrayOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.DEFAULT,
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        )
        var record: AudioRecord? = null
        for (source in sources) {
            try {
                val candidate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val audioFormat = AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                    val builder = AudioRecord.Builder()
                        .setAudioSource(source)
                        .setAudioFormat(audioFormat)
                        .setBufferSizeInBytes(bufferSize)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        builder.setContext(ims)
                    }
                    builder.build()
                } else {
                    AudioRecord(
                        source,
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize
                    )
                }
                if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                    Log.i(TAG, "AudioRecord initialized successfully with source: $source")
                    record = candidate
                    break
                } else {
                    candidate.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create AudioRecord with source $source", e)
            }
        }

        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize (state=${record?.state})")
            record?.release()
            return false
        }

        audioRecord = record
        try {
            audioRecord?.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "Exception in AudioRecord.startRecording", e)
            audioRecord?.release()
            audioRecord = null
            return false
        }

        isRecording.set(true)
        val isOnline = isCurrentSessionOnline
        val writePfd = if (!isOnline) {
            audioPipeWriteSide ?: return false
        } else null

        val silenceTimeoutSec = ims.prefs().getString(VoiceConstants.PREF_VOICE_SILENCE_TIMEOUT_SECONDS, "5")?.toIntOrNull() ?: 5
        val silenceTimeoutMs = if (silenceTimeoutSec > 0) silenceTimeoutSec * 1000L else 0L
        val initialTimeoutMs = if (silenceTimeoutSec > 0) maxOf(silenceTimeoutSec * 2000L, 6000L) else 0L

        val maxDurationSec = ims.prefs().getString(VoiceConstants.PREF_VOICE_MAX_DURATION_SECONDS, "30")?.toIntOrNull() ?: 30
        val maxDurationMs = if (maxDurationSec > 0) maxDurationSec * 1000L else 0L

        val sensitivity = ims.prefs().getString(VoiceConstants.PREF_VOICE_MIC_SENSITIVITY, "normal")
        val speechRmsThreshold = when (sensitivity) {
            "high" -> 60.0
            "low" -> 250.0
            else -> 120.0
        }

        audioThread = Thread({
            val buffer = ByteArray(FRAME_SIZE_BYTES)
            var outputStream: FileOutputStream? = null
            var totalBytesWritten = 0L
            val sessionStartTime = System.currentTimeMillis()
            var lastSpeechTime = sessionStartTime
            var hasSpoken = false

            try {
                if (!isOnline && writePfd != null) {
                    outputStream = FileOutputStream(writePfd.fileDescriptor)
                }
                while (isRecording.get()) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (read > 0) {
                        if (isOnline) {
                            synchronized(onlineAudioBuffer) {
                                onlineAudioBuffer.write(buffer, 0, read)
                            }
                        } else {
                            outputStream?.write(buffer, 0, read)
                            outputStream?.flush()
                        }
                        totalBytesWritten += read

                        val now = System.currentTimeMillis()
                        if (maxDurationMs > 0L && (now - sessionStartTime >= maxDurationMs)) {
                            Log.i(TAG, "Max recording duration (${maxDurationMs}ms) reached. Stopping voice input.")
                            mainHandler.post { stopVoice() }
                            break
                        }

                        if (silenceTimeoutMs > 0L) {
                            var sum = 0.0
                            var sampleCount = 0
                            var i = 0
                            while (i < read - 1) {
                                val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
                                sum += sample.toDouble() * sample.toDouble()
                                sampleCount++
                                i += 2
                            }
                            val rms = if (sampleCount > 0) kotlin.math.sqrt(sum / sampleCount) else 0.0
                            if (rms > speechRmsThreshold) {
                                lastSpeechTime = now
                                hasSpoken = true
                            }

                            if (hasSpoken && (now - lastSpeechTime > silenceTimeoutMs)) {
                                Log.i(TAG, "Silence timeout (${silenceTimeoutMs}ms) detected after speech. Stopping voice input.")
                                mainHandler.post { stopVoice() }
                                break
                            } else if (!hasSpoken && (now - sessionStartTime > initialTimeoutMs)) {
                                Log.i(TAG, "Initial silence timeout (${initialTimeoutMs}ms) detected. Stopping voice input.")
                                mainHandler.post { stopVoice() }
                                break
                            }
                        }
                    } else if (read < 0) {
                        Log.e(TAG, "AudioRecord read error: $read")
                        break
                    }
                }
            } catch (e: Exception) {
                if (isRecording.get()) {
                    Log.e(TAG, "Exception in audio write loop", e)
                }
            } finally {
                try { outputStream?.close() } catch (_: Exception) {}
                Log.i(TAG, "Audio loop ended. Total wrote: $totalBytesWritten bytes")
            }
        }, "VoiceAudioThread").apply {
            isDaemon = true
            start()
        }

        return true
    }

    private fun cancelHandshakeTimeout() {
        handshakeTimeoutRunnable?.let {
            mainHandler.removeCallbacks(it)
            handshakeTimeoutRunnable = null
        }
    }

    fun stopVoice() {
        Log.i(TAG, "stopVoice() called, state=$state, isOnline=$isCurrentSessionOnline")
        if (state == VoiceState.RECORDING || state == VoiceState.STARTING_SESSION || state == VoiceState.CONNECTING_PLUGIN) {
            updateState(VoiceState.PROCESSING_FINAL)
            stopAudioLoop()
            if (isCurrentSessionOnline) {
                processOnlineTranscription()
            } else {
                pluginManager.stopSession()
            }
        }
    }

    private fun processOnlineTranscription() {
        val pcmBytes = synchronized(onlineAudioBuffer) {
            onlineAudioBuffer.toByteArray().also { onlineAudioBuffer.reset() }
        }
        val sessionId = activeSessionId

        if (pcmBytes.size < 3200) { // Less than 100ms of audio
            Log.i(TAG, "Online voice audio too short (${pcmBytes.size} bytes), skipping")
            cleanupSession()
            updateState(VoiceState.IDLE)
            return
        }

        val wavBytes = AudioUtils.pcmToWav(pcmBytes, SAMPLE_RATE, 1, 16)
        val prefLang = ims.prefs().getString(VoiceConstants.PREF_VOICE_LANGUAGE, VoiceConstants.VOICE_LANG_FOLLOW_KEYBOARD)
            ?: VoiceConstants.VOICE_LANG_FOLLOW_KEYBOARD
        val languageTag = when (prefLang) {
            VoiceConstants.VOICE_LANG_AUTO -> "auto"
            VoiceConstants.VOICE_LANG_FOLLOW_KEYBOARD, "" -> {
                try {
                    RichInputMethodManager.getInstance().currentSubtypeLocale.toLanguageTag()
                } catch (_: Exception) {
                    java.util.Locale.getDefault().toLanguageTag()
                }
            }
            else -> prefLang
        }

        val service = ProofreadService(ims)
        onlineTranscriptionJob?.cancel()
        onlineTranscriptionJob = coroutineScope.launch(Dispatchers.IO) {
            val result = try {
                kotlinx.coroutines.withTimeout(25_000L) {
                    service.transcribeAudio(wavBytes, languageTag)
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Result.failure(Exception("Transcription timed out (25s)"))
            } catch (e: Exception) {
                Result.failure(e)
            }
            mainHandler.post {
                if (activeSessionId == sessionId) {
                    result.onSuccess { transcribedText ->
                        Log.i(TAG, "Online transcription success: '$transcribedText'")
                        syncRecognizedText(transcribedText, isFinal = true)
                        lastPartialText = null
                        cleanupSession()
                        updateState(VoiceState.IDLE)
                    }.onFailure { ex ->
                        val err = ex.message ?: "Transcription failed"
                        Log.e(TAG, "Online transcription error: $err", ex)
                        notifyError(err)
                        cleanupSession()
                        updateState(VoiceState.ERROR)
                    }
                }
            }
        }
    }

    fun cancelVoice() {
        Log.i(TAG, "cancelVoice() called, state=$state, isOnline=$isCurrentSessionOnline")
        if (state != VoiceState.IDLE) {
            cleanupSession()
            pluginManager.cancelSession()
            clearComposingText()
            updateState(VoiceState.IDLE)
        }
    }

    private fun stopAudioLoop() {
        if (!isRecording.getAndSet(false)) return
        Log.i(TAG, "stopAudioLoop() executing")

        // 1. Unblock the blocking native read() call by stopping AudioRecord
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord", e)
        }

        // 2. Wait for background VoiceAudioThread to fully exit native read() and terminate
        audioThread?.let { thread ->
            try {
                thread.join(1000)
                // 3. Edge-case guard: if driver hung, skip release to avoid native SIGABRT proxy crash
                if (thread.isAlive) {
                    Log.w(TAG, "VoiceAudioThread hung. Skipping release() to avoid native proxy crash.")
                    audioThread = null
                    audioRecord = null
                    closeQuietly(audioPipeWriteSide)
                    audioPipeWriteSide = null
                    return
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                Log.w(TAG, "Interrupted while joining audioThread", e)
            }
        }
        audioThread = null

        // 4. Safe to destroy native proxy ONLY after thread is confirmed dead
        try {
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioRecord", e)
        }
        audioRecord = null

        closeQuietly(audioPipeWriteSide)
        audioPipeWriteSide = null
    }

    private fun syncRecognizedText(rawText: String, isFinal: Boolean) {
        val ic = ims.currentInputConnection
        if (ic == null) {
            Log.e(TAG, "syncRecognizedText: InputConnection lost! (ic is null, isFinal=$isFinal)")
            return
        }
        if (!isRecording.get() && !isFinal) return

        val isSmartPunctuationEnabled = ims.prefs().getBoolean(VoiceConstants.PREF_VOICE_SMART_PUNCTUATION, true)
        val isCommandsEnabled = ims.prefs().getBoolean(VoiceConstants.PREF_VOICE_COMMANDS_ENABLED, true)

        val processedRaw = if (isSmartPunctuationEnabled) {
            VoiceTextProcessor.applySpokenPunctuation(rawText)
        } else {
            rawText.replace(Regex("[,.?!;:]"), "")
        }
        val trimmed = processedRaw.trim().replace(Regex("\\s+"), " ")

        if (isFinal) {
            if (isCommandsEnabled && trimmed.isNotEmpty()) {
                val commandAction = VoiceTextProcessor.COMMANDS[trimmed.lowercase(Locale.ROOT)]
                if (commandAction != null) {
                    executeVoiceCommand(commandAction, ic)
                    lastPartialText = null
                    return
                }
            }

            if (trimmed.isEmpty()) {
                if (currentSegmentEmittedLength > 0) {
                    ic.beginBatchEdit()
                    try {
                        ic.commitText(" ", 1)
                        lastCommittedTrailingSpace = true
                    } finally {
                        ic.endBatchEdit()
                    }
                    currentSegmentEmittedLength = 0
                }
                lastPartialText = null
                return
            }

            var displayText = if (needsCapitalStart && trimmed.isNotEmpty()) {
                trimmed.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
            } else {
                trimmed
            }

            // Question mark preservation:
            // 1. If live streaming partial had detected a question mark ('?') but offline final flipped it to '.',
            //    preserve the question mark.
            // 2. If the sentence begins with an interrogative starter in English or Russian and ends with '.',
            //    convert '.' to '?'.
            if (displayText.endsWith(".")) {
                if (lastPartialText?.trimEnd()?.endsWith("?") == true) {
                    displayText = displayText.dropLast(1) + "?"
                } else {
                    val lower = displayText.lowercase(Locale.ROOT)
                    if (QUESTION_PREFIXES.any { lower.startsWith(it) }) {
                        displayText = displayText.dropLast(1) + "?"
                    }
                }
            }

            ic.beginBatchEdit()
            try {
                // If there was an active partial preview on screen, replace it
                if (currentSegmentEmittedLength > 0) {
                    ic.deleteSurroundingText(currentSegmentEmittedLength, 0)
                    currentSegmentEmittedLength = 0
                }

                // If starting with punctuation right after a trailing space emitted by voice,
                // remove only that voice-added trailing space so punctuation attaches cleanly to the preceding word
                val firstChar = displayText.first()
                if (firstChar in ",.?!;:" && lastCommittedTrailingSpace) {
                    val before = ic.getTextBeforeCursor(1, 0)?.toString()
                    if (before == " ") {
                        ic.deleteSurroundingText(1, 0)
                    }
                }

                ic.commitText("$displayText ", 1)
                lastCommittedTrailingSpace = true
                val lastChar = displayText.lastOrNull()
                needsCapitalStart = lastChar != null && lastChar in ".!?"
                lastFinalText = displayText
                lastPartialText = null
                currentSegmentEmittedLength = 0
            } finally {
                ic.endBatchEdit()
            }
        } else {
            // Streaming partial update
            if (trimmed.isEmpty()) return
            if (trimmed == lastPartialText) return

            // Guard against obsolete partial echos that arrive right after onFinal
            val finalRef = lastFinalText
            if (finalRef != null && currentSegmentEmittedLength == 0) {
                if (trimmed == finalRef || finalRef.startsWith(trimmed)) {
                    Log.d(TAG, "Ignoring obsolete partial echo: '$trimmed' vs final '$finalRef'")
                    return
                }
            }
            lastPartialText = trimmed

            var displayText = if (needsCapitalStart && trimmed.isNotEmpty()) {
                trimmed.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
            } else {
                trimmed
            }

            if (displayText.endsWith(".")) {
                val lower = displayText.lowercase(Locale.ROOT)
                if (QUESTION_PREFIXES.any { lower.startsWith(it) }) {
                    displayText = displayText.dropLast(1) + "?"
                }
            }

            ic.beginBatchEdit()
            try {
                // Atomically delete the previous preview of this segment and commit the new preview
                if (currentSegmentEmittedLength > 0) {
                    ic.deleteSurroundingText(currentSegmentEmittedLength, 0)
                } else {
                    // First partial of a new segment: if it starts with punctuation after a voice trailing space, attach it
                    val firstChar = displayText.first()
                    if (firstChar in ",.?!;:" && lastCommittedTrailingSpace) {
                        val before = ic.getTextBeforeCursor(1, 0)?.toString()
                        if (before == " ") {
                            ic.deleteSurroundingText(1, 0)
                            lastCommittedTrailingSpace = false
                        }
                    }
                }
                ic.commitText(displayText, 1)
                currentSegmentEmittedLength = displayText.length
            } finally {
                ic.endBatchEdit()
            }
        }
    }

    fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
    }

    private fun executeVoiceCommand(action: VoiceTextProcessor.Action, ic: InputConnection) {
        if (currentSegmentEmittedLength > 0) {
            ic.deleteSurroundingText(currentSegmentEmittedLength, 0)
            currentSegmentEmittedLength = 0
        }
        when (action) {
            VoiceTextProcessor.Action.NEW_LINE -> {
                ic.commitText("\n", 1)
            }
            VoiceTextProcessor.Action.NEW_PARAGRAPH -> {
                ic.commitText("\n\n", 1)
            }
            VoiceTextProcessor.Action.DELETE_LAST_WORD -> {
                val before = ic.getTextBeforeCursor(100, 0)?.toString() ?: return
                val trimmed = before.trimEnd()
                val lastSpace = trimmed.lastIndexOf(' ')
                val wordLen = if (lastSpace == -1) trimmed.length else trimmed.length - lastSpace - 1
                val totalDelete = wordLen + (before.length - trimmed.length)
                if (totalDelete > 0) {
                    ic.deleteSurroundingText(totalDelete, 0)
                }
            }
            VoiceTextProcessor.Action.CLEAR_ALL -> {
                ic.beginBatchEdit()
                ic.performContextMenuAction(android.R.id.selectAll)
                ic.commitText("", 1)
                ic.endBatchEdit()
            }
            VoiceTextProcessor.Action.SEND -> {
                val editorInfo = ims.currentInputEditorInfo
                if (editorInfo != null) {
                    val actionId = editorInfo.imeOptions and EditorInfo.IME_MASK_ACTION
                    if (actionId != EditorInfo.IME_ACTION_NONE && actionId != EditorInfo.IME_ACTION_UNSPECIFIED) {
                        ic.performEditorAction(actionId)
                    } else {
                        ic.performEditorAction(EditorInfo.IME_ACTION_SEND)
                    }
                }
            }
        }
        Toast.makeText(ims, R.string.voice_command_executed, Toast.LENGTH_SHORT).show()
    }

    private fun clearComposingText() {
        val ic = ims.currentInputConnection
        if (ic != null && currentSegmentEmittedLength > 0) {
            ic.beginBatchEdit()
            try {
                ic.deleteSurroundingText(currentSegmentEmittedLength, 0)
            } finally {
                ic.endBatchEdit()
            }
        }
        currentSegmentEmittedLength = 0
        lastPartialText = null
    }

    private fun cleanupSession() {
        try {
            ims.inputLogic.finishInput()
            ims.inputLogic.connection.tryFixIncorrectCursorPosition()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to reset inputLogic/connection in cleanupSession", e)
        }
        onlineTranscriptionJob?.cancel()
        onlineTranscriptionJob = null
        synchronized(onlineAudioBuffer) {
            onlineAudioBuffer.reset()
        }
        isCurrentSessionOnline = false
        cancelHandshakeTimeout()
        stopAudioLoop()
        closeQuietly(audioPipeReadSide)
        audioPipeReadSide = null
        closeQuietly(audioPipeWriteSide)
        audioPipeWriteSide = null
        activeSessionId = null
        currentSegmentEmittedLength = 0
        lastFinalText = null
        lastPartialText = null
        lastCommittedTrailingSpace = false
    }

    @Synchronized
    private fun updateState(newState: VoiceState) {
        val oldState = this.state
        if (oldState == newState) {
            Log.d(TAG, "State dedup: $oldState -> $newState (ignored)")
            return
        }
        Log.i(TAG, "State transition: $oldState -> $newState")
        this.state = newState
        mainHandler.post {
            listener?.onStateChanged(newState)
        }
    }

    private fun notifyError(message: String) {
        mainHandler.post {
            listener?.onError(message)
        }
    }

    fun release() {
        cancelVoice()
        coroutineScope.cancel()
        pluginManager.release()
    }

    companion object {
        private const val TAG = "VoiceInputManager"
        private const val SAMPLE_RATE = 16000
        private const val FRAME_SIZE_MS = 30
        private const val FRAME_SIZE_SHORTS = SAMPLE_RATE * FRAME_SIZE_MS / 1000 // 480 shorts
        private const val FRAME_SIZE_BYTES = FRAME_SIZE_SHORTS * 2 // 960 bytes
        private const val HANDSHAKE_TIMEOUT_MS = 8000L

        private val QUESTION_PREFIXES = arrayOf(
            // English question words / auxiliaries
            "what ", "why ", "how ", "when ", "where ", "who ", "which ", "whose ", "whom ",
            "is ", "are ", "am ", "was ", "were ", "do ", "does ", "did ",
            "can ", "could ", "should ", "would ", "will ", "won't ", "shall ",
            "has ", "have ", "had ", "isn't ", "aren't ", "wasn't ", "weren't ",
            "don't ", "doesn't ", "didn't ", "can't ", "couldn't ", "shouldn't ", "wouldn't ",
            // Russian question words / interrogatives
            "почему ", "зачем ", "как ", "где ", "куда ", "откуда ", "когда ",
            "кто ", "что ", "чей ", "чья ", "чье ", "чьё ", "чьи ",
            "какой ", "какая ", "какое ", "какие ", "какого ", "какому ", "каким ",
            "сколько ", "насколько ",
            "правда ли ", "неужели ", "разве ",
            "а почему ", "а зачем ", "а как ", "а где ", "а куда ", "а откуда ", "а когда ",
            "а кто ", "а что ", "а разве ", "а правда "
        )

        fun isBlockedEditor(info: EditorInfo?): Boolean {
            if (info == null) return false

            val variation = info.inputType and InputType.TYPE_MASK_VARIATION

            return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
    }

    private fun closeQuietly(pfd: ParcelFileDescriptor?) {
        try {
            pfd?.close()
        } catch (_: Exception) {}
    }
}
