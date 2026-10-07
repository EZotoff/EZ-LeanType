// SPDX-License-Identifier: GPL-3.0-only
package com.leanbitlab.leantype.voice.offline.engine

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
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
import java.nio.LongBuffer
import java.nio.IntBuffer
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phonon-2 (Fermion Research, CC-BY-4.0; derivative of NVIDIA parakeet-tdt-0.6b-v3)
 * offline engine, running the community ONNX export (tiyuvta/Phonon-2-ONNX) with
 * onnxruntime-android.
 *
 * Model layout in filesDir/models/phonon/:
 *   preprocessor-model.onnx       log-mel, 128 dims, input waveforms [B,N] float32 @16 kHz
 *   encoder-model.int8.onnx       FastConformer, in audio_signal [B,128,T], out [B,1024,T']
 *   decoder_joint-model.int8.onnx prediction net + joint, see decode loop below
 *   vocab.txt                     SentencePiece-style, "id token" per line
 *
 * Greedy TDT decode loop follows the export's verified reference
 * (github.com/avifenesh/phonon2-onnx README, "Decoding").
 */
class PhononEngine {

    private val nativeLock = Any()
    private var env: OrtEnvironment? = null
    private var preprocessSession: OrtSession? = null
    private var encoderSession: OrtSession? = null
    private var decoderJointSession: OrtSession? = null
    @Volatile private var vocab: List<String> = emptyList()
    @Volatile private var loadedModelDirPath: String? = null
    @Volatile private var vadModelPath: String? = null
    @Volatile private var numThreads = 4

    // Cross-segment context (same rationale as ParakeetTdtEngine).
    @Volatile private var previousTail = FloatArray(0)
    @Volatile private var previousTailTranscript = ""
    private val contextTailSamples = 16000 * 4

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

        fun toFloatArray(): FloatArray = data.copyOf(size)

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
        Thread(r, "PhononAudioReaderThread").apply {
            priority = Thread.NORM_PRIORITY + 1
            isDaemon = true
        }
    }

    private val decoderExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "PhononDecoderThread").apply {
            priority = Thread.NORM_PRIORITY + 1
            isDaemon = true
        }
    }

    fun isModelLoaded(): Boolean =
        synchronized(nativeLock) { encoderSession != null && decoderJointSession != null && preprocessSession != null }

    fun loadModel(modelDir: File, context: Context? = null): Boolean {
        if (!modelDir.exists() || !modelDir.isDirectory) {
            Log.e(TAG, "Model directory not found: ${modelDir.absolutePath}")
            return false
        }

        synchronized(nativeLock) {
            if (isModelLoaded() && loadedModelDirPath == modelDir.absolutePath) return true

            val preprocessFile = File(modelDir, "preprocessor-model.onnx").takeIf { it.isFile && it.length() > 0 }
            val encoderFile = findModelFile(modelDir, listOf("encoder-model.int8.onnx", "encoder-model.onnx"))
            val decoderJointFile = findModelFile(modelDir, listOf("decoder_joint-model.int8.onnx", "decoder_joint-model.onnx"))
            val vocabFile = File(modelDir, "vocab.txt").takeIf { it.isFile && it.length() > 0 }

            if (preprocessFile == null || encoderFile == null || decoderJointFile == null || vocabFile == null) {
                Log.e(TAG, "Missing required Phonon-2 ONNX model components in ${modelDir.absolutePath}")
                return false
            }

            val newVocab = try {
                vocabFile.readLines().mapNotNull { line ->
                    val idx = line.lastIndexOf(' ')
                    if (idx <= 0) null else line.substring(idx + 1)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to read vocab.txt", t)
                return false
            }
            if (newVocab.size != 8193) {
                Log.e(TAG, "Unexpected vocab size ${newVocab.size}, expected 8193")
                return false
            }

            var vadFile = findModelFile(modelDir, listOf("silero_vad.onnx"))
            if (vadFile == null && context != null) {
                vadFile = extractVadAssetIfNeeded(context)
            }
            vadModelPath = vadFile?.absolutePath

            releaseContextSync()

            return try {
                val ortEnv = OrtEnvironment.getEnvironment()
                val opts = OrtSession.SessionOptions().apply {
                    // NNAPI/XNNPACK left out deliberately: int8 dynamic-quant graphs
                    // from the export run on the CPU EP; extra EPs risk silent
                    // fallback or unsupported-op failures on the 614 MB encoder.
                    setIntraOpNumThreads(numThreads)
                }
                preprocessSession = ortEnv.createSession(preprocessFile.absolutePath, opts)
                encoderSession = ortEnv.createSession(encoderFile.absolutePath, opts)
                decoderJointSession = ortEnv.createSession(decoderJointFile.absolutePath, opts)
                env = ortEnv
                vocab = newVocab
                loadedModelDirPath = modelDir.absolutePath
                Log.i(TAG, "Phonon-2 models loaded from ${modelDir.name} (vocab ${newVocab.size}, VAD ${vadModelPath != null})")
                true
            } catch (t: Throwable) {
                Log.e(TAG, "Exception initializing Phonon-2 native engine", t)
                releaseContextSync()
                false
            }
        }
    }

    fun setNumThreads(threads: Int) {
        numThreads = threads.coerceIn(1, 8)
    }

    private fun extractVadAssetIfNeeded(context: Context): File? {
        val dest = File(context.filesDir, "silero_vad.onnx")
        if (dest.exists() && dest.length() > 0) return dest
        return try {
            context.assets.open("silero_vad.onnx").use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
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
        decoderExecutor.execute { releaseContextSync() }
    }

    private fun releaseContextSync() {
        synchronized(nativeLock) {
            try { preprocessSession?.close() } catch (_: Throwable) {}
            try { encoderSession?.close() } catch (_: Throwable) {}
            try { decoderJointSession?.close() } catch (_: Throwable) {}
            preprocessSession = null
            encoderSession = null
            decoderJointSession = null
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
        val hasModels = isModelLoaded()
        if (!hasModels) {
            try { audioInput.close() } catch (_: Throwable) {}
            callback.onError(VoiceConstants.VOICE_ERROR_MODEL_MISSING, "Phonon-2 model not loaded")
            return
        }

        cancelSession()

        val session = SessionState(config?.sessionId ?: UUID.randomUUID().toString())
        activeSession = session

        config?.cpuThreads?.let { if (it in 1..8) numThreads = it }

        val currentVadConfig = vadModelPath?.let { path ->
            try {
                VadModelConfig().apply {
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = path,
                        threshold = 0.35f,
                        minSilenceDuration = 2.5f,
                        minSpeechDuration = 0.1f,
                        windowSize = 512,
                        maxSpeechDuration = 25.0f
                    )
                    sampleRate = 16000
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
            val buffer = FloatBuffer(16000 * 12)
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
                        while (vadOffset + windowSize <= buffer.size && !session.cancelled.get()) {
                            buffer.copyWindow(vadOffset, window)
                            vad.acceptWaveform(window)
                            if (!speechStarted && vad.isSpeechDetected()) {
                                speechStarted = true
                                lastPartialSampleCount = vadOffset
                            }
                            vadOffset += windowSize
                        }

                        if (!speechStarted) {
                            val maxPreRoll = 15 * windowSize
                            if (buffer.size > maxPreRoll) {
                                val drop = buffer.size - maxPreRoll
                                val alignedDrop = (drop / windowSize) * windowSize
                                if (alignedDrop > 0) {
                                    buffer.removeFirst(alignedDrop)
                                    vadOffset = maxOf(0, vadOffset - alignedDrop)
                                }
                            }
                        }

                        // Phonon-2 decode is heavy (~0.5-1 s per 25 s segment on a phone);
                        // partial interval widened to 1.2 s of audio.
                        if (speechStarted && (buffer.size - lastPartialSampleCount >= 19200) && !session.cancelled.get()) {
                            lastPartialSampleCount = buffer.size
                            if (isPartialDecoding.compareAndSet(false, true)) {
                                val snapshot = buffer.toFloatArray()
                                val targetSegId = currentSegmentId.get()
                                decoderExecutor.execute {
                                    try {
                                        if (session.running.get() && !session.cancelled.get() && currentSegmentId.get() == targetSegId) {
                                            val partialText = decodeWaveform(snapshot)
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

                        while (!vad.empty() && !session.cancelled.get()) {
                            val segment = vad.front()
                            val segSamples = segment.samples
                            vad.pop()

                            if (segSamples.isNotEmpty()) {
                                val targetSegId = currentSegmentId.incrementAndGet()
                                val fallback = lastEmittedPartial
                                val contextForDecode = previousTail
                                val contextTranscript = previousTailTranscript
                                decoderExecutor.execute {
                                    if (session.running.get() && !session.cancelled.get()) {
                                        val fullText = decodeWaveform(contextForDecode + segSamples)
                                        val committedText = if (contextForDecode.isEmpty()) fullText
                                        else stripContextOverlap(fullText, contextTranscript)
                                        val textToEmit = if (committedText.isNotEmpty()) committedText else fallback
                                        if (textToEmit.isNotEmpty() && !session.cancelled.get()) {
                                            Log.i(TAG, "Segment committed via VAD (${segSamples.size} samples, ctx ${contextForDecode.size}): '$textToEmit'")
                                            callback.onFinal(textToEmit)
                                        }
                                        previousTailTranscript = decodeWaveform(previousTail)
                                    }
                                }
                                previousTail = segSamples.takeLast(minOf(segSamples.size, contextTailSamples)).toFloatArray()
                            }

                            val keepTail = 24000
                            if (buffer.size > keepTail) {
                                buffer.removeFirst(buffer.size - keepTail)
                            }
                            vadOffset = buffer.size
                            speechStarted = false
                            lastPartialSampleCount = 0
                            lastEmittedPartial = ""
                        }
                    } else {
                        // Energy-based fallback
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
                            val maxPreRoll = 7680
                            if (buffer.size > maxPreRoll) {
                                buffer.removeFirst(buffer.size - maxPreRoll)
                            }
                        }

                        val shouldFinalize = speechStarted && (consecutiveSilenceSamples >= 5600 || buffer.size >= 128000)

                        if (shouldFinalize && !session.cancelled.get()) {
                            val segSamples = buffer.toFloatArray()
                            currentSegmentId.incrementAndGet()
                            val contextForDecode = previousTail
                            val contextTranscript = previousTailTranscript
                            val fullText = decodeWaveform(contextForDecode + segSamples)
                            val committedText = if (contextForDecode.isEmpty()) fullText
                            else stripContextOverlap(fullText, contextTranscript)
                            val textToEmit = if (committedText.isNotEmpty()) committedText else lastEmittedPartial
                            if (textToEmit.isNotEmpty() && !session.cancelled.get()) {
                                Log.i(TAG, "Segment committed via RMS fallback (${segSamples.size} samples, ctx ${contextForDecode.size}): '$textToEmit'")
                                callback.onFinal(textToEmit)
                            }
                            previousTail = segSamples.takeLast(minOf(segSamples.size, contextTailSamples)).toFloatArray()
                            previousTailTranscript = decodeWaveform(previousTail)
                            buffer.clear()
                            speechStarted = false
                            consecutiveSilenceSamples = 0
                            lastPartialSampleCount = 0
                            lastEmittedPartial = ""
                        } else if (speechStarted && (buffer.size - lastPartialSampleCount >= 9600) && !session.cancelled.get()) {
                            lastPartialSampleCount = buffer.size
                            if (isPartialDecoding.compareAndSet(false, true)) {
                                val snapshot = buffer.toFloatArray()
                                val targetSegId = currentSegmentId.get()
                                decoderExecutor.execute {
                                    try {
                                        if (session.running.get() && !session.cancelled.get() && currentSegmentId.get() == targetSegId) {
                                            val partialText = decodeWaveform(snapshot)
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
                            val segSamples = segment.samples
                            vad.pop()
                            if (segSamples.isNotEmpty()) {
                                flushedCount++
                                val targetSegId = currentSegmentId.incrementAndGet()
                                val fallback = lastEmittedPartial
                                decoderExecutor.execute {
                                    if (session.running.get() && !session.cancelled.get()) {
                                        val committedText = decodeWaveform(segSamples)
                                        val textToEmit = if (committedText.isNotEmpty()) committedText else fallback
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
                            val targetSegId = currentSegmentId.incrementAndGet()
                            val fallback = lastEmittedPartial
                            decoderExecutor.execute {
                                if (session.running.get() && !session.cancelled.get()) {
                                    val committedText = decodeWaveform(remainingSamples)
                                    val textToEmit = if (committedText.isNotEmpty()) committedText else fallback
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
                            val targetSegId = currentSegmentId.incrementAndGet()
                            val fallback = lastEmittedPartial
                            decoderExecutor.execute {
                                if (session.running.get() && !session.cancelled.get()) {
                                    val committedText = decodeWaveform(finalSamples)
                                    val textToEmit = if (committedText.isNotEmpty()) committedText else fallback
                                    if (textToEmit.isNotEmpty() && !session.cancelled.get()) {
                                        Log.i(TAG, "Final session commit via RMS fallback: '$textToEmit'")
                                        callback.onFinal(textToEmit)
                                    }
                                }
                            }
                        }
                    }

                    decoderExecutor.execute {
                        if (!session.cancelled.get()) {
                            try { callback.onSessionEnded() } catch (_: Throwable) {}
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Phonon-2 audio stream error", t)
                try {
                    callback.onError(VoiceConstants.VOICE_ERROR_AUDIO_START_FAILED, t.message ?: "Streaming error")
                } catch (_: Throwable) {}
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

    // --- ONNX decoding -----------------------------------------------------

    private class OrtException(message: String) : Exception(message)

    private fun requireEnv(): OrtEnvironment =
        env ?: throw OrtException("ORT environment not initialized")

    private fun requireSessions(): Triple<OrtSession, OrtSession, OrtSession> {
        val p = preprocessSession ?: throw OrtException("preprocessor not loaded")
        val e = encoderSession ?: throw OrtException("encoder not loaded")
        val d = decoderJointSession ?: throw OrtException("decoder_joint not loaded")
        return Triple(p, e, d)
    }

    /** Greedy TDT decode of a 16 kHz mono float waveform in [-1, 1]. */
    private fun decodeWaveformImpl(samples: FloatArray): String {
        val (pre, enc, dec) = requireSessions()
        val ortEnv = requireEnv()
        val t0 = System.currentTimeMillis()

        // 1. Preprocessor: waveforms [1,N] float32, waveforms_lens [1] int64
        val features: FloatArray
        val numFeatFrames: Long
        OnnxTensor.createTensor(ortEnv, java.nio.FloatBuffer.wrap(samples), longArrayOf(1, samples.size.toLong())).use { w ->
            OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(longArrayOf(samples.size.toLong())), longArrayOf(1)).use { wl ->
                pre.run(mapOf("waveforms" to w, "waveforms_lens" to wl)).use { out ->
                    @Suppress("UNCHECKED_CAST")
                    val featTensor = out["features"].get() as OnnxTensor
                    val shape = featTensor.info.shape // [1, 128, F]
                    val f = shape[2].toInt()
                    val buf = featTensor.floatBuffer
                    features = FloatArray(128 * f)
                    buf.get(features)
                    numFeatFrames = f.toLong()
                }
            }
        }

        // 2. Encoder: audio_signal [1,128,F], length [1] int64 -> [1,1024,T']
        val encoded: FloatArray
        val numEncFrames: Int
        OnnxTensor.createTensor(ortEnv, java.nio.FloatBuffer.wrap(features), longArrayOf(1, 128, numFeatFrames)).use { a ->
            OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(longArrayOf(numFeatFrames)), longArrayOf(1)).use { l ->
                enc.run(mapOf("audio_signal" to a, "length" to l)).use { out ->
                    @Suppress("UNCHECKED_CAST")
                    val encTensor = out["outputs"].get() as OnnxTensor
                    val shape = encTensor.info.shape // [1, 1024, T']
                    val t = shape[2].toInt()
                    val buf = encTensor.floatBuffer
                    encoded = FloatArray(1024 * t)
                    buf.get(encoded)
                    numEncFrames = t
                }
            }
        }
        val tEnc = System.currentTimeMillis()

        // 3. Greedy TDT loop over decoder_joint
        val vocabLocal = vocab
        val sb = StringBuilder()
        var h = FloatArray(2 * 640) // [2,1,640] flattened
        var c = FloatArray(2 * 640)
        var last = 8192
        var t = 0
        var nsym = 0
        val outBuf = FloatArray(8198)

        while (t < numEncFrames) {
            val frame = FloatArray(1024)
            System.arraycopy(encoded, t * 1024, frame, 0, 1024)

            var newH = h
            var newC = c
            OnnxTensor.createTensor(ortEnv, java.nio.FloatBuffer.wrap(frame), longArrayOf(1, 1024, 1)).use { eo ->
                OnnxTensor.createTensor(ortEnv, java.nio.IntBuffer.wrap(intArrayOf(last)), longArrayOf(1, 1)).use { tg ->
                    OnnxTensor.createTensor(ortEnv, java.nio.IntBuffer.wrap(intArrayOf(1)), longArrayOf(1)).use { tl ->
                        OnnxTensor.createTensor(ortEnv, java.nio.FloatBuffer.wrap(h), longArrayOf(2, 1, 640)).use { hs1 ->
                            OnnxTensor.createTensor(ortEnv, java.nio.FloatBuffer.wrap(c), longArrayOf(2, 1, 640)).use { hs2 ->
                                dec.run(mapOf(
                                    "encoder_outputs" to eo,
                                    "targets" to tg,
                                    "target_length" to tl,
                                    "input_states_1" to hs1,
                                    "input_states_2" to hs2
                                )).use { out ->
                                    @Suppress("UNCHECKED_CAST")
                                    val o = out["outputs"].get() as OnnxTensor
                                    o.floatBuffer.get(outBuf)
                                    @Suppress("UNCHECKED_CAST")
                                    val hsOut1 = out["output_states_1"].get() as OnnxTensor
                                    newH = FloatArray(2 * 640)
                                    hsOut1.floatBuffer.get(newH)
                                    @Suppress("UNCHECKED_CAST")
                                    val hsOut2 = out["output_states_2"].get() as OnnxTensor
                                    newC = FloatArray(2 * 640)
                                    hsOut2.floatBuffer.get(newC)
                                }
                            }
                        }
                    }
                }
            }

            var bestTok = 0
            var bestVal = outBuf[0]
            for (i in 1 until 8193) {
                if (outBuf[i] > bestVal) {
                    bestVal = outBuf[i]
                    bestTok = i
                }
            }
            var bestDur = 0
            var bestDurVal = outBuf[8193]
            for (i in 8194 until 8198) {
                if (outBuf[i] > bestDurVal) {
                    bestDurVal = outBuf[i]
                    bestDur = i - 8193
                }
            }
            var dur = bestDur
            if (bestTok == 8192 && dur == 0) dur = 1
            Log.d(TAG, "TDT frame $t: tok=$bestTok piece='${if (bestTok < vocabLocal.size) vocabLocal[bestTok] else "?"}' dur=$bestDur top=${outBuf[bestTok]}")
            if (bestTok != 8192) {
                val piece = vocabLocal[bestTok]
                if (!piece.startsWith("<")) sb.append(piece)
                last = bestTok
                h = newH
                c = newC
            }
            if (dur == 0) {
                nsym++
                if (nsym >= 10) {
                    dur = 1
                    nsym = 0
                }
            } else {
                nsym = 0
            }
            t += dur
        }

        val text = sb.toString().replace("\u2581", " ").replace(Regex("\\s+"), " ").trim()
        val t1 = System.currentTimeMillis()
        Log.i(TAG, "Decode: ${samples.size / 16000.0}s audio, enc ${tEnc - t0} ms, tdt ${t1 - tEnc} ms, ${numEncFrames} frames, text='$text'")
        return text
    }

    fun decodeWaveform(samples: FloatArray): String {
        if (samples.isEmpty()) return ""
        return synchronized(nativeLock) {
            try {
                decodeWaveformImpl(samples)
            } catch (t: Throwable) {
                Log.e(TAG, "decodeWaveform error", t)
                ""
            }
        }
    }

    /**
     * When decoding [contextTail + segment], the transcript includes the context
     * audio's words too; strip them from the front of the combined text.
     * Mirrors ParakeetTdtEngine.stripContextOverlap.
     */
    private fun stripContextOverlap(fullText: String, contextTranscript: String): String {
        if (contextTranscript.isBlank()) return fullText
        val norm = { s: String -> s.lowercase().replace(Regex("[^\\p{L}\\p{N}\\s]"), "").split(Regex("\\s+")).filter { it.isNotEmpty() } }
        val ctxWords = norm(contextTranscript)
        if (ctxWords.isEmpty()) return fullText
        val fullWords = fullText.split(Regex("\\s+")).filter { it.isNotEmpty() }
        var matched = 0
        val normFull = norm(fullText)
        var i = 0
        while (i < ctxWords.size && i < normFull.size && normFull[i] == ctxWords[i]) { matched++; i++ }
        if (matched == 0) return fullText
        var remaining = matched
        val result = fullWords.dropWhile { w ->
            val isWord = w.any { it.isLetterOrDigit() }
            if (isWord && remaining > 0) { remaining--; true } else { !isWord && remaining > 0 }
        }
        return result.joinToString(" ").trim()
    }

    companion object {
        private const val TAG = "PhononEngine"
    }
}
