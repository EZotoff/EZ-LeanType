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
import java.util.concurrent.atomic.AtomicInteger

class ParakeetTdtEngine {

    private val nativeLock = Any()
    @Volatile private var recognizer: OfflineRecognizer? = null
    @Volatile private var loadedModelDirPath: String? = null
    @Volatile private var vadModelPath: String? = null

    private class FloatBuffer(initialCapacity: Int = 16000 * 10) {
        private var data = FloatArray(initialCapacity)
        var size: Int = 0
            private set

        fun append(samples: ShortArray, count: Int) {
            ensureCapacity(size + count)
            for (i in 0 until count) {
                data[size + i] = samples[i] / 32768.0f
            }
            size += count
        }

        fun copyWindow(offset: Int, window: FloatArray, length: Int = window.size) {
            System.arraycopy(data, offset, window, 0, length)
        }

        fun toFloatArray(): FloatArray {
            return data.copyOf(size)
        }

        fun removeFirst(count: Int) {
            if (count >= size) {
                size = 0
                return
            }
            val remaining = size - count
            System.arraycopy(data, count, data, 0, remaining)
            size = remaining
        }

        fun clear() {
            size = 0
        }

        private fun ensureCapacity(minCapacity: Int) {
            if (minCapacity <= data.size) return
            var newCap = data.size * 2
            if (newCap < minCapacity) newCap = minCapacity
            data = data.copyOf(newCap)
        }
    }

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
                        minSilenceDuration = 0.6f,
                        minSpeechDuration = 0.15f,
                        windowSize = 512,
                        maxSpeechDuration = 30.0f
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
            val buffer = FloatBuffer(16000 * 15)
            val currentSegmentId = AtomicInteger(0)
            val isPartialDecoding = AtomicBoolean(false)
            var lastEmittedPartial = ""
            var lastPartialSampleCount = 0
            var leftoverByte = -1
            val windowSize = 512
            val window = FloatArray(windowSize)
            var vadOffset = 0
            var speechStarted = false
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

                    buffer.append(shortBuffer, samplesRead)

                    if (vad != null) {
                        // VAD mode: process audio in 512-sample frames
                        while (vadOffset + windowSize <= buffer.size && !session.cancelled.get()) {
                            buffer.copyWindow(vadOffset, window)
                            vad.acceptWaveform(window)
                            if (!speechStarted && vad.isSpeechDetected()) {
                                speechStarted = true
                                lastPartialSampleCount = vadOffset
                            }
                            vadOffset += windowSize
                        }

                        // Pre-roll management: discard initial silence before speech starts, retaining ~320ms context
                        // Dropping in exact multiples of windowSize (512) preserves strict window alignment
                        if (!speechStarted) {
                            val maxPreRoll = 10 * windowSize
                            if (buffer.size > maxPreRoll) {
                                val drop = buffer.size - maxPreRoll
                                val alignedDrop = (drop / windowSize) * windowSize
                                if (alignedDrop > 0) {
                                    buffer.removeFirst(alignedDrop)
                                    vadOffset = maxOf(0, vadOffset - alignedDrop)
                                }
                            }
                        }

                        // Periodic partial decoding during ongoing speech
                        if (speechStarted && (buffer.size - lastPartialSampleCount >= 4800) && !session.cancelled.get()) {
                            lastPartialSampleCount = buffer.size
                            if (isPartialDecoding.compareAndSet(false, true)) {
                                val snapshot = buffer.toFloatArray()
                                val targetSegId = currentSegmentId.get()
                                decoderExecutor.execute {
                                    try {
                                        if (session.running.get() && !session.cancelled.get() && currentSegmentId.get() == targetSegId) {
                                            val partialText = decodeWaveform(currentRecognizer, snapshot)
                                            if (partialText.isNotEmpty() && partialText != lastEmittedPartial && currentSegmentId.get() == targetSegId && !session.cancelled.get()) {
                                                lastEmittedPartial = partialText
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
                                currentSegmentId.incrementAndGet()
                                val fallbackPartial = lastEmittedPartial
                                decoderExecutor.execute {
                                    if (session.running.get() && !session.cancelled.get()) {
                                        val committedText = decodeWaveform(currentRecognizer, segSamples)
                                        val textToEmit = if (committedText.isNotEmpty()) committedText else fallbackPartial
                                        if (textToEmit.isNotEmpty() && !session.cancelled.get()) {
                                            Log.i(TAG, "Segment committed via VAD (${segSamples.size} samples): '$textToEmit'")
                                            callback.onFinal(textToEmit)
                                        }
                                    }
                                }
                            }

                            // Keep any unconsumed remainder beyond vadOffset so speech is never lost
                            if (buffer.size > vadOffset) {
                                buffer.removeFirst(vadOffset)
                                vadOffset = 0
                            } else {
                                buffer.clear()
                                vadOffset = 0
                            }
                            speechStarted = false
                            lastPartialSampleCount = 0
                            lastEmittedPartial = ""
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
                            speechStarted = true
                            consecutiveSilenceSamples = 0
                        } else if (speechStarted) {
                            consecutiveSilenceSamples += samplesRead
                        }

                        if (!speechStarted) {
                            val maxPreRoll = 6400
                            if (buffer.size > maxPreRoll) {
                                buffer.removeFirst(buffer.size - maxPreRoll)
                            }
                        }

                        val shouldFinalize = speechStarted && (consecutiveSilenceSamples >= 11200 || buffer.size >= 480000)

                        if (shouldFinalize && !session.cancelled.get()) {
                            val segSamples = buffer.toFloatArray()
                            currentSegmentId.incrementAndGet()
                            val fallbackPartial = lastEmittedPartial
                            decoderExecutor.execute {
                                if (session.running.get() && !session.cancelled.get()) {
                                    val committedText = decodeWaveform(currentRecognizer, segSamples)
                                    val textToEmit = if (committedText.isNotEmpty()) committedText else fallbackPartial
                                    if (textToEmit.isNotEmpty() && !session.cancelled.get()) {
                                        Log.i(TAG, "Segment committed via RMS fallback (${segSamples.size} samples): '$textToEmit'")
                                        callback.onFinal(textToEmit)
                                    }
                                }
                            }
                            buffer.clear()
                            speechStarted = false
                            consecutiveSilenceSamples = 0
                            lastPartialSampleCount = 0
                            lastEmittedPartial = ""
                        } else if (speechStarted && (buffer.size - lastPartialSampleCount >= 4800) && !session.cancelled.get()) {
                            lastPartialSampleCount = buffer.size
                            if (isPartialDecoding.compareAndSet(false, true)) {
                                val snapshot = buffer.toFloatArray()
                                val targetSegId = currentSegmentId.get()
                                decoderExecutor.execute {
                                    try {
                                        if (session.running.get() && !session.cancelled.get() && currentSegmentId.get() == targetSegId) {
                                            val partialText = decodeWaveform(currentRecognizer, snapshot)
                                            if (partialText.isNotEmpty() && partialText != lastEmittedPartial && currentSegmentId.get() == targetSegId && !session.cancelled.get()) {
                                                lastEmittedPartial = partialText
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
                        var flushedCount = 0
                        while (!vad.empty() && !session.cancelled.get()) {
                            val segment = vad.front()
                            vad.pop()
                            val segSamples = segment.samples
                            if (segSamples.isNotEmpty()) {
                                flushedCount++
                                currentSegmentId.incrementAndGet()
                                val fallbackPartial = lastEmittedPartial
                                decoderExecutor.execute {
                                    if (!session.cancelled.get()) {
                                        val committedText = decodeWaveform(currentRecognizer, segSamples)
                                        val textToEmit = if (committedText.isNotEmpty()) committedText else fallbackPartial
                                        if (textToEmit.isNotEmpty() && !session.cancelled.get()) {
                                            Log.i(TAG, "Final flush segment committed via VAD: '$textToEmit'")
                                            callback.onFinal(textToEmit)
                                        }
                                    }
                                }
                            }
                        }
                        if (flushedCount == 0 && speechStarted && buffer.size > 0 && !session.cancelled.get()) {
                            val remainingSamples = buffer.toFloatArray()
                            currentSegmentId.incrementAndGet()
                            val fallbackPartial = lastEmittedPartial
                            decoderExecutor.execute {
                                if (!session.cancelled.get()) {
                                    val committedText = decodeWaveform(currentRecognizer, remainingSamples)
                                    val textToEmit = if (committedText.isNotEmpty()) committedText else fallbackPartial
                                    if (textToEmit.isNotEmpty() && !session.cancelled.get()) {
                                        Log.i(TAG, "Final lingering segment committed: '$textToEmit'")
                                        callback.onFinal(textToEmit)
                                    }
                                }
                            }
                        }
                    } else {
                        if (buffer.size > 0 && !session.cancelled.get()) {
                            val finalSamples = buffer.toFloatArray()
                            currentSegmentId.incrementAndGet()
                            val fallbackPartial = lastEmittedPartial
                            decoderExecutor.execute {
                                if (!session.cancelled.get()) {
                                    val committedText = decodeWaveform(currentRecognizer, finalSamples)
                                    val textToEmit = if (committedText.isNotEmpty()) committedText else fallbackPartial
                                    if (textToEmit.isNotEmpty() && !session.cancelled.get()) {
                                        Log.i(TAG, "Final session commit via RMS fallback: '$textToEmit'")
                                        callback.onFinal(textToEmit)
                                    }
                                }
                            }
                        }
                    }

                    // Complete session notification strictly after all pending decodes finish
                    decoderExecutor.execute {
                        if (!session.cancelled.get()) {
                            try { callback.onSessionEnded() } catch (_: Throwable) {}
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
                session.running.set(false)
                if (activeSession === session) {
                    activeSession = null
                }
                decoderExecutor.execute {
                    try { vad?.release() } catch (_: Throwable) {}
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
