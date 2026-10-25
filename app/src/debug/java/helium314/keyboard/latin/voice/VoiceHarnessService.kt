// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import com.leanbitlab.leantype.voice.IVoiceCallback
import com.leanbitlab.leantype.voice.VoiceConstants
import com.leanbitlab.leantype.voice.VoiceSessionConfig
import com.leanbitlab.leantype.voice.offline.VoiceEngineLocal
import helium314.keyboard.latin.utils.prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Debug-build-only corpus harness: feeds a 16 kHz mono 16-bit WAV file into the
 * REAL offline engine session (same VoiceEngineController path as dictation) and
 * mirrors VoiceInputManager.syncRecognizedText's final-segment text stage using
 * the shared VoiceTranscriptPipeline, so a recorded block can be replayed
 * without live dictation. Never present in release builds.
 *
 * Start via Shizuku/adb:
 *   am startservice -n com.leanbitlab.leantype/helium314.keyboard.latin.voice.VoiceHarnessService \
 *     --es file /path/to/B1.wav [--es lang en-US] [--ei pace 30] [--ez wait_ai true]
 * Result JSON: files/voice_harness/<name>.json, completion logged as
 * "VTHARNESS: DONE <path>".
 */
class VoiceHarnessService : Service() {

    companion object {
        private const val TAG = "VTHARNESS"
        private const val SAMPLE_RATE = 16000
        private const val FRAME_BYTES = 960 // 30 ms @ 16 kHz mono s16
        private val busy = AtomicBoolean(false)
    }

    private val scope = CoroutineScope(Dispatchers.Default)
    private var refineJobs = mutableListOf<Job>()

    // Text-stage state, mirroring VoiceInputManager session state
    private var needsCapitalStart = true
    private var lastPartialText: String? = null
    private var lastFinalText: String? = null
    private var lastFinalPreAi: String? = null
    private var lastFinalTimestamp = 0L

    private val committed = StringBuilder()
    private val segmentsJson = JSONArray()
    private val commandsJson = JSONArray()
    private val echoesJson = JSONArray()
    private val partialsJson = JSONArray()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val file = intent?.getStringExtra("file")
        if (file == null) {
            Log.e(TAG, "missing 'file' extra")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!busy.compareAndSet(false, true)) {
            Log.e(TAG, "another harness run is active")
            stopSelf()
            return START_NOT_STICKY
        }
        val lang = intent.getStringExtra("lang") ?: "en-US"
        val paceMs = intent.getIntExtra("pace", 30)
        val waitAi = intent.getBooleanExtra("wait_ai", true)

        Thread({
            run(file, lang, paceMs.toLong(), waitAi)
            busy.set(false)
            stopSelf()
        }, "voice-harness").start()
        return START_NOT_STICKY
    }

    private fun run(file: String, lang: String, paceMs: Long, waitAi: Boolean) {
        val t0 = SystemClock.elapsedRealtime()
        val wav = File(file)
        val pcm = readWavPcm(wav)
        if (pcm == null) {
            Log.e(TAG, "cannot read WAV $file")
            return
        }
        Log.i(TAG, "harness start file=$file pcm=${pcm.size} bytes lang=$lang pace=${paceMs}ms")

        val ctx = applicationContext
        val threads = ctx.prefs().getString(VoiceConstants.PREF_VOICE_CPU_THREADS, "4")?.toIntOrNull() ?: 4
        val config = VoiceSessionConfig(
            sessionId = UUID.randomUUID().toString(),
            mode = VoiceConstants.MODE_ACCURATE,
            languageTag = lang,
            sampleRate = SAMPLE_RATE,
            enablePartial = true,
            maxSegmentMs = 5000,
            hybridTimeoutMs = 0,
            hybridFallbackToVosk = false,
            cpuThreads = threads,
            customPrompt = VoiceInputPrefs.whisperPromptWithVocabulary(ctx)
        )

        val pipe = ParcelFileDescriptor.createPipe()
        val done = CountDownLatch(1)
        var engineError: String? = null

        val callback = object : IVoiceCallback.Stub() {
            override fun onSessionStarted() {
                Log.i(TAG, "engine session started")
            }

            override fun onPartial(text: String?) {
                val raw = text ?: return
                partialsJson.put(raw)
                // partials only drive the '?'-preservation state, as in the IME
                val norm = VoiceTranscriptPipeline.normalize(
                    VoiceTranscriptPipeline.preTransform(raw, false, freshFlags())
                )
                synchronized(lock) { lastPartialText = norm }
            }

            override fun onFinal(text: String?) {
                val raw = text ?: return
                handleFinal(raw)
            }

            override fun onError(code: Int, message: String?) {
                engineError = "code=$code msg=$message"
                Log.e(TAG, "engine error $engineError")
                done.countDown()
            }

            override fun onSessionEnded() {
                Log.i(TAG, "engine session ended")
                // Mirror VoiceInputManager.onSessionEnded: if the engine never
                // emitted a final for the pending segment (Phonon when stopping
                // without a trailing pause), promote the last partial preview.
                val pending = lastPartialText
                if (pending != null) {
                    Log.i(TAG, "onSessionEnded without final: promoting partial")
                    handleFinal(pending)
                }
                done.countDown()
            }
        }

        try {
            VoiceEngineLocal.getInstance(ctx).startSession(config, pipe[0], callback)
        } catch (t: Throwable) {
            Log.e(TAG, "startSession failed", t)
            pipe[0].close(); pipe[1].close()
            writeResult(wav, t0, "startSession failed: $t", freshFlags())
            return
        }

        // Feed the WAV PCM into the pipe at dictation pace (or as fast as the
        // engine drinks it when pace=0), same FileOutputStream route the mic
        // thread uses. Trailing ~1s of silence mirrors the user pausing before
        // tapping stop, so the VAD can close the last segment naturally.
        var feedError: Throwable? = null
        try {
            val out = FileOutputStream(pipe[1].fileDescriptor)
            var off = 0
            while (off < pcm.size) {
                val len = minOf(FRAME_BYTES, pcm.size - off)
                out.write(pcm, off, len)
                out.flush()
                off += len
                if (paceMs > 0) SystemClock.sleep(paceMs)
            }
            val silence = ByteArray(FRAME_BYTES)
            repeat(32) {
                out.write(silence, 0, silence.size)
                out.flush()
                if (paceMs > 0) SystemClock.sleep(paceMs)
            }
            out.close() // EOF, like the mic thread stopping
            // Production signals EOF via ParcelFileDescriptor.close() on the
            // write side (stopAudioLoop) — closing only the stream does not
            // unblock the engine's read().
            pipe[1].close()
        } catch (t: Throwable) {
            Log.e(TAG, "feed failed", t)
            feedError = t
            try { pipe[1].close() } catch (_: Throwable) {}
        }

        // Mirror stopVoice(): EOF above + stopSession flag -> engine flushes
        // and fires onSessionEnded.
        try {
            VoiceEngineLocal.getInstance(ctx).stopSession()
        } catch (t: Throwable) {
            Log.e(TAG, "stopSession failed", t)
        }

        done.await(180, TimeUnit.SECONDS)
        try { pipe[0].close() } catch (_: Throwable) {}
        if (engineError == null && feedError != null) engineError = "feed failed: $feedError"

        if (waitAi && refineJobs.isNotEmpty()) {
            Log.i(TAG, "waiting for ${refineJobs.size} pending AI refine job(s)")
            runBlocking {
                refineJobs.forEach { withTimeoutOrNull(120_000) { it.join() } }
            }
        }

        writeResult(wav, t0, engineError, freshFlags())
    }

    /** Flags re-read per segment, like syncRecognizedText (AI can degrade mid-session). */
    private fun freshFlags() = VoiceTranscriptPipeline.StageFlags(
        smartPunctuation = prefs.getBoolean(VoiceConstants.PREF_VOICE_SMART_PUNCTUATION, true),
        removeFillers = prefs.getBoolean(VoiceConstants.PREF_VOICE_REMOVE_FILLERS, true),
        aiPostProcessing = prefs.getBoolean(VoiceConstants.PREF_VOICE_AI_POST_PROCESSING, true),
        aiUsable = VoiceAiPostProcessor.isProviderAvailable(this),
        customVocab = VoiceInputPrefs.customVocabulary(this)
    )

    private val prefs by lazy { applicationContext.prefs() }

    private val lock = Any()

    /** Mirror of VoiceInputManager.syncRecognizedText's isFinal branch. */
    private fun handleFinal(raw: String) = synchronized(lock) {
        val flags = freshFlags()
        val trimmed = VoiceTranscriptPipeline.normalize(
            VoiceTranscriptPipeline.preTransform(raw, true, flags)
        )
        val seg = JSONObject()
        seg.put("raw", raw)
        seg.put("trimmed", trimmed)

        // AI pass (async, replaces the segment afterwards — like the IME)
        if (flags.aiPostProcessing && flags.aiUsable && trimmed.isNotEmpty()) {
            val preAi = trimmed
            val idx = segmentsJson.length()
            val job = scope.launch {
                val refined = VoiceAiPostProcessor.process(applicationContext, preAi, flags.customVocab)
                VoiceAiPostProcessor.logOutcome(preAi, refined)
                seg.put("refined", refined ?: JSONObject.NULL)
                if (refined != null && refined != preAi) {
                    applyRefine(preAi, refined)
                }
            }
            refineJobs.add(job)
        }

        val echoCandidates = listOfNotNull(lastFinalText, lastFinalPreAi)
        if (SystemClock.elapsedRealtime() - lastFinalTimestamp < VoiceTranscriptPipeline.ECHO_GUARD_WINDOW_MS &&
            VoiceTranscriptPipeline.isTailEcho(trimmed, echoCandidates)
        ) {
            Log.i(TAG, "echo guard dropped final: '$trimmed'")
            echoesJson.put(trimmed)
            seg.put("echoDropped", true)
            segmentsJson.put(seg)
            lastPartialText = null
            return
        }

        val command = VoiceTranscriptPipeline.detectCommand(trimmed)
        if (command != null) {
            Log.i(TAG, "command: $command")
            commandsJson.put(command.name)
            seg.put("command", command.name)
            segmentsJson.put(seg)
            lastPartialText = null
            return
        }

        if (trimmed.isEmpty()) {
            seg.put("empty", true)
            segmentsJson.put(seg)
            lastPartialText = null
            return
        }

        val displayText = VoiceTranscriptPipeline.finalDisplayText(trimmed, needsCapitalStart, lastPartialText)
        seg.put("display", displayText)
        segmentsJson.put(seg)

        committed.append(displayText).append(' ')
        needsCapitalStart = VoiceTranscriptPipeline.needsCapitalAfter(displayText)
        lastFinalText = displayText
        lastFinalPreAi = trimmed
        lastFinalTimestamp = SystemClock.elapsedRealtime()
        lastPartialText = null
        }

    /** Mirror of replaceLastSegment: swap the just-committed segment's text. */
    private fun applyRefine(original: String, refined: String) = synchronized(lock) {
        val m = committed.toString()
        val idx = m.lastIndexOf(original)
        if (idx >= 0) {
            committed.setLength(0)
            committed.append(m, 0, idx).append(refined).append(m, idx + original.length)
        }
    }

    private fun writeResult(wav: File, t0: Long, error: String?, flags: VoiceTranscriptPipeline.StageFlags) {
        try {
            val out = JSONObject()
                .put("file", wav.absolutePath)
                .put("wallMs", SystemClock.elapsedRealtime() - t0)
                .put("enginePref", applicationContext.prefs().getString(VoiceConstants.PREF_OFFLINE_ENGINE, "auto"))
                .put("smartPunctuation", flags.smartPunctuation)
                .put("removeFillers", flags.removeFillers)
                .put("aiPostProcessing", flags.aiPostProcessing)
                .put("aiUsableAtStart", flags.aiUsable)
                .put("customVocab", JSONArray(flags.customVocab))
                .put("finalText", committed.toString())
                .put("segments", segmentsJson)
                .put("commands", commandsJson)
                .put("echoDropped", echoesJson)
                .put("partials", partialsJson)
            if (error != null) out.put("error", error)
            val dir = File(applicationContext.filesDir, "voice_harness")
            dir.mkdirs()
            val f = File(dir, wav.nameWithoutExtension + ".json")
            f.writeText(out.toString(2))
            Log.i(TAG, "DONE ${f.absolutePath}")
        } catch (t: Throwable) {
            Log.e(TAG, "failed to write result", t)
        }
    }

    /** Minimal WAV parser: finds the data chunk, returns raw PCM bytes. */
    private fun readWavPcm(f: File): ByteArray? {
        return try {
            val all = f.readBytes()
            if (all.size < 44 || all[0] != 'R'.code.toByte() || all[1] != 'I'.code.toByte()) return null
            var pos = 12
            while (pos + 8 <= all.size) {
                val id = String(all, pos, 4, Charsets.US_ASCII)
                val size = le32(all, pos + 4)
                if (id == "data") {
                    return all.copyOfRange(pos + 8, minOf(pos + 8 + size, all.size))
                }
                pos += 8 + size + (size and 1)
            }
            null
        } catch (t: Throwable) {
            Log.e(TAG, "readWavPcm failed", t)
            null
        }
    }

    private fun le32(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or
            ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)
}
