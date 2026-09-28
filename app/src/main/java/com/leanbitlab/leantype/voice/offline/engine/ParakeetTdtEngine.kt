// SPDX-License-Identifier: GPL-3.0-only
package com.leanbitlab.leantype.voice.offline.engine

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.leanbitlab.leantype.voice.IVoiceCallback
import com.leanbitlab.leantype.voice.VoiceConstants
import com.leanbitlab.leantype.voice.VoiceSessionConfig
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ParakeetTdtEngine {

    private val nativeLock = Any()
    @Volatile private var recognizer: OfflineRecognizer? = null
    @Volatile private var loadedModelDirPath: String? = null
    @Volatile private var vadModelPath: String? = null

    private class SessionState(
        val sessionId: String,
        val running: AtomicBoolean = AtomicBoolean(true),
        val cancelled: AtomicBoolean = AtomicBoolean(false)
    )
    @Volatile private var activeSession: SessionState? = null

    private val audioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ParakeetAudioReaderThread").apply {
            priority = Thread.NORM_PRIORITY + 1
            isDaemon = true
        }
    }

    private val decoderExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ParakeetDecoderThread").apply {
            priority = Thread.NORM_PRIORITY
            isDaemon = true
        }
    }

    fun isModelLoaded(): Boolean = recognizer != null

    fun loadModel(modelDir: File, context: Context? = null): Boolean {
        if (!modelDir.exists() || !modelDir.isDirectory) {
            Log.e(TAG, "Model directory not found: ${modelDir.absolutePath}")
            return false
        }

        synchronized(nativeLock) {
            if (recognizer != null && loadedModelDirPath == modelDir.absolutePath) {
                return true
            }

            val encoderFile = findModelFile(modelDir, listOf("encoder.int8.onnx", "encoder.onnx", "model.int8.onnx", "gigaam_v3_e2e_rnnt_encoder_int8.onnx"))
            val decoderFile = findModelFile(modelDir, listOf("decoder.int8.onnx", "decoder.onnx", "gigaam_v3_e2e_rnnt_decoder.onnx"))
            val joinerFile = findModelFile(modelDir, listOf("joiner.int8.onnx", "joiner.onnx", "gigaam_v3_e2e_rnnt_joint.onnx", "joint.onnx"))
            val tokensFile = findModelFile(modelDir, listOf("tokens.txt", "gigaam_v3_e2e_rnnt_tokens.txt"))

            if (encoderFile == null || tokensFile == null) {
                Log.e(TAG, "Missing required Transducer/GigaAM ONNX model components in ${modelDir.absolutePath}")
                return false
            }

            var vadFile = findModelFile(modelDir, listOf("silero_vad.onnx"))
            if (vadFile == null && context != null) {
                vadFile = extractVadAssetIfNeeded(context)
            }
            vadModelPath = vadFile?.absolutePath
            Log.i(TAG, "VAD model path: $vadModelPath")

            releaseContextSync()

            return try {
                val transducerConfig = OfflineTransducerModelConfig(
                    encoder = encoderFile.absolutePath,
                    decoder = decoderFile?.absolutePath ?: "",
                    joiner = joinerFile?.absolutePath ?: ""
                )
                val modelConfig = OfflineModelConfig().apply {
                    transducer = transducerConfig
                    tokens = tokensFile.absolutePath
                    numThreads = 4
                    provider = "cpu"
                    modelType = "nemo_transducer"
                }
                val recConfig = OfflineRecognizerConfig().apply {
                    featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80)
                    this.modelConfig = modelConfig
                    decodingMethod = "greedy_search"
                }
                recognizer = OfflineRecognizer(null, recConfig)
                loadedModelDirPath = modelDir.absolutePath
                Log.i(TAG, "Transducer model loaded successfully from ${modelDir.name}")
                true
            } catch (t: Throwable) {
                Log.e(TAG, "Exception initializing Parakeet TDT native engine", t)
                recognizer = null
                loadedModelDirPath = null
                false
            }
        }
    }

    private fun extractVadAssetIfNeeded(context: Context): File? {
        val dest = File(context.filesDir, "silero_vad.onnx")
        if (dest.exists() && dest.length() > 0) {
            return dest
        }
        return try {
            context.assets.open("silero_vad.onnx").use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            if (dest.exists() && dest.length() > 0) dest else null
        } catch (t: Throwable) {
            Log.w(TAG, "Could not extract silero_vad.onnx from assets", t)
            null
        }
    }

    private fun findModelFile(dir: File, candidateNames: List<String>): File? {
        for (name in candidateNames) {
            val f = File(dir, name)
            if (f.exists() && f.isFile) return f
        }
        return null
    }

    fun releaseContext() {
        cancelSession()
        decoderExecutor.execute {
            releaseContextSync()
        }
    }

    private fun releaseContextSync() {
        synchronized(nativeLock) {
            recognizer?.let {
                try {
                    it.release()
                } catch (t: Throwable) {
                    Log.e(TAG, "Error releasing OfflineRecognizer", t)
                }
            }
            recognizer = null
            loadedModelDirPath = null
        }
    }

    fun cancelSession() {
        activeSession?.let {
            it.cancelled.set(true)
            it.running.set(false)
        }
        activeSession = null
    }

    fun startSession(
        audioInput: ParcelFileDescriptor,
        callback: IVoiceCallback,
        config: VoiceSessionConfig?
    ) {
        val currentRecognizer = recognizer
        if (currentRecognizer == null) {
            try { audioInput.close() } catch (_: Throwable) {}
            callback.onError(VoiceConstants.VOICE_ERROR_MODEL_MISSING, "Parakeet TDT model not loaded")
            return
        }

        // Abort previous session before starting new one
        cancelSession()

        val session = SessionState(config?.sessionId ?: UUID.randomUUID().toString())
        activeSession = session

        // Initialize VAD for this session if model is available
        val currentVadConfig = vadModelPath?.let { path ->
            try {
                VadModelConfig().apply {
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = path,
                        threshold = 0.5f,
                        minSilenceDuration = 0.25f,
                        minSpeechDuration = 0.25f,
                        windowSize = 512,
                        maxSpeechDuration = 5.0f
                    )
                    sampleRate = 16000
                    numThreads = 1
                    provider = "cpu"
                    debug = false
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Error configuring VAD", t)
                null
            }
        }
        val vad = currentVadConfig?.let {
            try {
                Vad(null, it)
            } catch (t: Throwable) {
                Log.e(TAG, "Error instantiating Vad", t)
                null
            }
        }

        audioExecutor.execute {
            var inputStream: FileInputStream? = null
            val activeSegmentSamples = ArrayList<Float>()
            var lastEmittedText = ""
            var lastPartialSampleCount = 0
            val isPartialDecoding = AtomicBoolean(false)
            var leftoverByte = -1
            val windowSize = 512
            var vadOffset = 0
            var speechDetected = false
            var consecutiveSilenceSamples = 0

            try {
                callback.onSessionStarted()

                inputStream = FileInputStream(audioInput.fileDescriptor)
                // 100ms frames: 1600 samples = 3200 bytes @ 16kHz 16-bit mono
                val chunkSize = 3200
                val byteBuffer = ByteArray(chunkSize)
                val shortBuffer = ShortArray(chunkSize / 2 + 1)

                while (session.running.get() && !session.cancelled.get()) {
                    val bytesRead = inputStream.read(byteBuffer)
                    if (bytesRead <= 0) break

                    var offsetBytes = 0
                    var availableBytes = bytesRead
                    var samplesRead = 0

                    if (leftoverByte != -1 && availableBytes > 0) {
                        val b0 = leftoverByte
                        val b1 = byteBuffer[0].toInt() and 0xFF
                        shortBuffer[samplesRead++] = ((b1 shl 8) or b0).toShort()
                        offsetBytes = 1
                        availableBytes--
                        leftoverByte = -1
                    }

                    val fullPairs = availableBytes / 2
                    if (fullPairs > 0) {
                        ByteBuffer.wrap(byteBuffer, offsetBytes, fullPairs * 2)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .asShortBuffer()
                            .get(shortBuffer, samplesRead, fullPairs)
                        samplesRead += fullPairs
                    }

                    if (availableBytes % 2 == 1) {
                        leftoverByte = byteBuffer[offsetBytes + fullPairs * 2].toInt() and 0xFF
                    }

                    if (samplesRead == 0) continue

                    synchronized(activeSegmentSamples) {
                        for (i in 0 until samplesRead) {
                            activeSegmentSamples.add(shortBuffer[i] / 32768.0f)
                        }
                    }

                    if (vad != null) {
                        // VAD mode: process audio in 512-sample frames
                        var currentSegmentSize = synchronized(activeSegmentSamples) { activeSegmentSamples.size }
                        while (vadOffset + windowSize <= currentSegmentSize && !session.cancelled.get()) {
                            val window = FloatArray(windowSize)
                            synchronized(activeSegmentSamples) {
                                for (k in 0 until windowSize) {
                                    window[k] = activeSegmentSamples[vadOffset + k]
                                }
                            }
                            vad.acceptWaveform(window)
                            if (!speechDetected && vad.isSpeechDetected()) {
                                speechDetected = true
                                lastPartialSampleCount = vadOffset + windowSize
                            }
                            vadOffset += windowSize
                            currentSegmentSize = synchronized(activeSegmentSamples) { activeSegmentSamples.size }
                        }

                        // Pre-roll management: discard initial silence before speech starts, retaining ~320ms context
                        if (!speechDetected) {
                            val maxPreRoll = 10 * windowSize
                            synchronized(activeSegmentSamples) {
                                if (activeSegmentSamples.size > maxPreRoll) {
                                    val drop = activeSegmentSamples.size - maxPreRoll
                                    activeSegmentSamples.subList(0, drop).clear()
                                    vadOffset = maxOf(0, vadOffset - drop)
                                }
                            }
                        }

                        // Periodic partial decoding during ongoing speech
                        val activeCount = synchronized(activeSegmentSamples) { activeSegmentSamples.size }
                        if (speechDetected && (activeCount - lastPartialSampleCount >= 4800) && !session.cancelled.get()) {
                            lastPartialSampleCount = activeCount
                            if (isPartialDecoding.compareAndSet(false, true)) {
                                val snapshot = synchronized(activeSegmentSamples) { activeSegmentSamples.toFloatArray() }
                                decoderExecutor.execute {
                                    try {
                                        if (session.running.get() && !session.cancelled.get()) {
                                            val partialText = decodeWaveform(currentRecognizer, snapshot)
                                            if (partialText.isNotEmpty() && partialText != lastEmittedText && !session.cancelled.get()) {
                                                lastEmittedText = partialText
                                                callback.onPartial(partialText)
                                            }
                                        }
                                    } finally {
                                        isPartialDecoding.set(false)
                                    }
                                }
                            }
                        }

                        // Segment finalization: VAD detected natural speech pause or maxSpeechDuration
                        while (!vad.empty() && !session.cancelled.get()) {
                            val segment = vad.front()
                            vad.pop()
                            val segSamples = segment.samples
                            if (segSamples.isNotEmpty()) {
                                val committedText = decodeWaveform(currentRecognizer, segSamples)
                                val textToEmit = if (committedText.isNotEmpty()) committedText else lastEmittedText
                                if (textToEmit.isNotEmpty() && !session.cancelled.get()) {
                                    Log.i(TAG, "Segment committed via VAD (${segSamples.size} samples): '$textToEmit'")
                                    callback.onFinal(textToEmit)
                                }
                            }
                            // Wipe audio buffer clean for next utterance so model never degrades
                            synchronized(activeSegmentSamples) {
                                activeSegmentSamples.clear()
                            }
                            vadOffset = 0
                            speechDetected = false
                            lastPartialSampleCount = 0
                            lastEmittedText = ""
                        }
                    } else {
                        // Energy-based fallback mode when VAD model is unavailable
                        var sum = 0.0
                        for (i in 0 until samplesRead) {
                            val s = shortBuffer[i].toDouble()
                            sum += s * s
                        }
                        val rms = if (samplesRead > 0) kotlin.math.sqrt(sum / samplesRead) else 0.0
                        val isSpeech = rms > 120.0

                        if (isSpeech) {
                            speechDetected = true
                            consecutiveSilenceSamples = 0
                        } else if (speechDetected) {
                            consecutiveSilenceSamples += samplesRead
                        }

                        val activeCount = synchronized(activeSegmentSamples) { activeSegmentSamples.size }
                        val shouldFinalize = speechDetected && (consecutiveSilenceSamples >= 6400 || activeCount >= 80000)

                        if (shouldFinalize && !session.cancelled.get()) {
                            val segSamples = synchronized(activeSegmentSamples) { activeSegmentSamples.toFloatArray() }
                            if (segSamples.isNotEmpty()) {
                                val committedText = decodeWaveform(currentRecognizer, segSamples)
                                val textToEmit = if (committedText.isNotEmpty()) committedText else lastEmittedText
                                if (textToEmit.isNotEmpty()) {
                                    Log.i(TAG, "Segment committed via RMS fallback (${segSamples.size} samples): '$textToEmit'")
                                    callback.onFinal(textToEmit)
                                }
                            }
                            synchronized(activeSegmentSamples) {
                                activeSegmentSamples.clear()
                            }
                            speechDetected = false
                            consecutiveSilenceSamples = 0
                            lastPartialSampleCount = 0
                            lastEmittedText = ""
                        } else if (speechDetected && (activeCount - lastPartialSampleCount >= 4800) && !session.cancelled.get()) {
                            lastPartialSampleCount = activeCount
                            if (isPartialDecoding.compareAndSet(false, true)) {
                                val snapshot = synchronized(activeSegmentSamples) { activeSegmentSamples.toFloatArray() }
                                decoderExecutor.execute {
                                    try {
                                        if (session.running.get() && !session.cancelled.get()) {
                                            val partialText = decodeWaveform(currentRecognizer, snapshot)
                                            if (partialText.isNotEmpty() && partialText != lastEmittedText && !session.cancelled.get()) {
                                                lastEmittedText = partialText
                                                callback.onPartial(partialText)
                                            }
                                        }
                                    } finally {
                                        isPartialDecoding.set(false)
                                    }
                                }
                            }
                        }
                    }
                }

                // Finalize utterance on EOF
                if (!session.cancelled.get()) {
                    if (vad != null) {
                        vad.flush()
                        while (!vad.empty() && !session.cancelled.get()) {
                            val segment = vad.front()
                            vad.pop()
                            val segSamples = segment.samples
                            if (segSamples.isNotEmpty()) {
                                val committedText = decodeWaveform(currentRecognizer, segSamples)
                                val textToEmit = if (committedText.isNotEmpty()) committedText else lastEmittedText
                                if (textToEmit.isNotEmpty()) {
                                    Log.i(TAG, "Final flush segment committed via VAD: '$textToEmit'")
                                    callback.onFinal(textToEmit)
                                }
                            }
                        }
                        val remainingSamples = synchronized(activeSegmentSamples) { activeSegmentSamples.toFloatArray() }
                        if (remainingSamples.isNotEmpty() && speechDetected && !session.cancelled.get()) {
                            val committedText = decodeWaveform(currentRecognizer, remainingSamples)
                            val textToEmit = if (committedText.isNotEmpty()) committedText else lastEmittedText
                            if (textToEmit.isNotEmpty()) {
                                Log.i(TAG, "Final lingering segment committed: '$textToEmit'")
                                callback.onFinal(textToEmit)
                            }
                        }
                    } else {
                        val finalSamples = synchronized(activeSegmentSamples) { activeSegmentSamples.toFloatArray() }
                        if (finalSamples.isNotEmpty() && !session.cancelled.get()) {
                            val committedText = decodeWaveform(currentRecognizer, finalSamples)
                            val textToEmit = if (committedText.isNotEmpty()) committedText else lastEmittedText
                            if (textToEmit.isNotEmpty()) {
                                Log.i(TAG, "Final session commit via RMS fallback: '$textToEmit'")
                                callback.onFinal(textToEmit)
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Parakeet TDT audio stream error", t)
                if (!session.cancelled.get()) {
                    try {
                        callback.onError(VoiceConstants.VOICE_ERROR_AUDIO_START_FAILED, t.message ?: "Streaming error")
                    } catch (_: Throwable) {}
                }
            } finally {
                try { vad?.release() } catch (_: Throwable) {}
                session.running.set(false)
                if (activeSession === session) {
                    activeSession = null
                }
                if (!session.cancelled.get()) {
                    try { callback.onSessionEnded() } catch (_: Throwable) {}
                }
                try { inputStream?.close() } catch (_: Throwable) {}
                try { audioInput.close() } catch (_: Throwable) {}
            }
        }
    }

    private fun decodeWaveform(rec: OfflineRecognizer, samples: FloatArray): String {
        if (samples.isEmpty()) return ""
        return synchronized(nativeLock) {
            try {
                val stream = rec.createStream()
                try {
                    stream.acceptWaveform(samples, 16000)
                    rec.decode(stream)
                    val result = rec.getResult(stream)
                    val raw = result.text.trim()
                    raw.replace("\u2581", " ").replace(Regex("\\s+"), " ").trim()
                } finally {
                    try { stream.release() } catch (_: Throwable) {}
                }
            } catch (t: Throwable) {
                Log.e(TAG, "decodeWaveform error", t)
                ""
            }
        }
    }

    companion object {
        private const val TAG = "ParakeetTdtEngine"
    }
}
