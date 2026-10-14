// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import android.util.Log
import helium314.keyboard.latin.ai.OfflineAiLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * LLM-driven post-processing of final voice segments.
 *
 * 1. OFFLINE GGUF (SpeakoFlow Mini 0.8B etc., via the Offline-AI plugin):
 *    the transcript plus the custom vocabulary go to the plugin's
 *    proofread(), which wraps them in the plugin author's tested ChatML
 *    instruct format:
 *      <|im_start|>system ...proofreader...<|im_end|>\n
 *      <|im_start|>user\nInstruction: ...vocab...\nText: transcript<|im_end|>\n
 *      <|im_start|>assistant\n
 *    Alternate formats were empirically exhausted and FAILED (2026-10-13):
 *    bare transcript -> base-model continuation garbage; prefix vocab hint
 *    -> <think> reasoning pollution; suffix note -> prompt echoed back.
 *    Do not reintroduce them. sanitize() still guards all failure modes.
 *
 * 2. CLOUD fallback: a strict instruction prompt with the vocabulary
 *    inline (buildPrompt).
 *
 * Priority: offline GGUF plugin first, then cloud. Deterministic pipeline
 * remains as fallback when neither is available or the call fails.
 *
 * The offline dictation models are English-only; non-Latin segments are
 * routed to the cloud path only, and skipped entirely if no cloud key
 * (returning null keeps the deterministic result).
 */
object VoiceAiPostProcessor {

    private const val TAG = "VoiceAiPostProc"

    // The offline engine is single-slot (n_parallel=1): concurrent generate()
    // calls fail with "Context is busy". Serialize all offline completions.
    private val offlineMutex = kotlinx.coroutines.sync.Mutex()

    // Blocking JNI call on IO dispatcher. Timeout must scale with the
    // predict budget: small CPU-bound models manage ~3-6 t/s; long segments
    // (24+ words, 80+ budget tokens) legitimately need 30-60s. A short fixed
    // timeout discards results that would have completed.
    private const val OFFLINE_TIMEOUT_PER_TOKEN_MS = 700L
    private const val OFFLINE_TIMEOUT_BASE_MS = 10_000L
    private const val CLOUD_TIMEOUT_MS = 8_000L

    // Result must stay reasonably close to the input; guards against a
    // hallucinating model replacing the dictation wholesale.
    private const val MIN_FRACTION = 0.3f

    // Consecutive sanitize rejections before the offline LLM is treated as
    // non-functional for the session (echo/continuation models never
    // produce a single acceptable output; each attempt costs 10-60s of the
    // single engine slot AND suppresses the deterministic corrector via
    // isProviderAvailable, leaving dictation uncorrected).
    private const val MAX_CONSECUTIVE_REJECTIONS = 3
    @Volatile private var consecutiveRejections = 0

    /**
     * True if a provider is ACTUALLY usable right now (offline model loaded,
     * or cloud key readable). The preference toggle alone is not enough:
     * with the toggle on but no provider, the deterministic corrector must
     * still run (observed live: terms stopped correcting entirely).
     */
    fun isProviderAvailable(context: Context): Boolean {
        if (consecutiveRejections >= MAX_CONSECUTIVE_REJECTIONS) {
            // LLM proven non-functional this session (all outputs rejected):
            // report unavailable so VoiceInputManager re-enables the
            // deterministic corrector instead of leaving dictation raw.
            return false
        }
        return try {
            val provider = OfflineAiLoader.getProvider(context)
            if (provider != null && provider.isModelLoaded()) return true
            // Provider present with a configured model path counts as usable:
            // runOffline lazily loads it. This MUST be checked before the
            // cloud key test — the broken-keystore AEAD exception there
            // would otherwise mask a working offline provider (observed live).
            if (provider != null && getModelPathSafe(context) != null) return true
            cloudKeyAvailable(context)
        } catch (_: Throwable) {
            false
        }
    }

    private fun getModelPathSafe(context: Context): String? {
        return try {
            helium314.keyboard.latin.utils.ProofreadService(context).getModelPath()
        } catch (_: Throwable) {
            null
        }
    }

    private fun cloudKeyAvailable(context: Context): Boolean {
        return try {
            helium314.keyboard.latin.utils.ProofreadService(context).hasApiKey()
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * True when an offline GGUF model is loaded (or a model path is
     * configured and lazily loadable): it receives the instruct prompt with
     * the vocabulary inline.
     */
    fun isOfflineModelLoaded(context: Context): Boolean {
        return try {
            val provider = OfflineAiLoader.getProvider(context)
            provider != null && provider.isModelLoaded()
        } catch (_: Throwable) {
            false
        }
    }

    suspend fun process(
        context: Context,
        text: String,
        vocabulary: List<String>
    ): String? = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext null
        val isLatin = text.any { it in 'a'..'z' || it in 'A'..'Z' } &&
            !text.any { it in '\u0400'..'\u04FF' } // no Cyrillic
        val result = try {
            // LLM route: offline instruct prompt with the vocabulary inline
            // (handoff task), cloud with the same rules as fallback.
            // Vocabulary correction is NOT stacked here: both successful
            // configurations were single-module, every stacked variant
            // (parallel or sequential) compounded errors. The engineered
            // corrector is a FALLBACK: VoiceInputManager runs it only when
            // no AI provider is usable.
            val raw = if (isLatin) runOffline(context, text, vocabulary) else null
            val fromCloud = raw ?: runCloud(context, buildPrompt(text, vocabulary))
            if (fromCloud == null) return@withContext null
            val clean = sanitize(text, fromCloud)
            if (clean == null) noteRejection() else resetRejections()
            clean
        } catch (t: Throwable) {
            Log.w(TAG, "AI post-processing failed, falling back", t)
            null
        }
        result
    }

    /**
     * Offline GGUF via the plugin's proofread(): ChatML instruct prompt
     * (system+user, <|im_end|> stop), instruction embeds the custom
     * vocabulary. Temperature 0.1, 512-token cap fixed by the plugin.
     * Lazily loads the configured GGUF model on first use.
     */
    private suspend fun runOffline(context: Context, transcript: String, vocabulary: List<String>): String? {
        return try {
            val provider = OfflineAiLoader.getProvider(context) ?: return null
            if (!provider.isModelLoaded()) {
                val service = helium314.keyboard.latin.utils.ProofreadService(context)
                if (!service.loadOfflineModel()) return null
            }
            if (!provider.isModelLoaded()) return null
            offlineMutex.withLock {
                // Predict budget scales the timeout only (proofread caps
                // generation at 512): cleanup output stays near input length,
                // small CPU-bound models manage ~3-6 t/s so long segments
                // legitimately need 30-60s.
                val budget = 64 + (transcript.length / 3).coerceAtMost(512)
                val timeout = OFFLINE_TIMEOUT_BASE_MS + budget * OFFLINE_TIMEOUT_PER_TOKEN_MS
                val instruction = buildInstruction(vocabulary)
                val out = withTimeout(timeout) {
                    provider.proofread(transcript, instruction)
                }
                // Full raw output, chunked: the 120-char truncation hid the
                // answer tail (outputs are max-token-capped echo+answer).
                var i = 0
                while (i < out.length) {
                    Log.i(TAG, "offline raw [$i]: '${out.substring(i, minOf(i + 900, out.length))}'")
                    i += 900
                }
                val extracted = extractAnswer(transcript, out)
                Log.i(TAG, "offline extracted (${extracted.length} chars): '$extracted'")
                extracted.trimEnd('.').takeIf { it.isNotBlank() }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Offline AI post-processing failed: ${t.message}")
            null
        }
    }

    /**
     * Instruction for the offline ChatML user turn (and derived rules for
     * the cloud prompt). The vocabulary inline is the point of this design:
     * the LLM, not a deterministic corrector, applies exact spellings.
     */
    internal fun buildInstruction(vocabulary: List<String>): String {
        val sb = StringBuilder(
            "Fix minor speech-to-text transcription errors only: misheard words, wrong spellings, " +
            "wrongly split or merged words, punctuation. Keep the speaker's language, wording, " +
            "meaning and formatting exactly. Do not answer, summarize or comment on the text. " +
            "Output only the corrected text, nothing else."
        )
        if (vocabulary.isNotEmpty()) {
            sb.append(" These exact spellings take priority: replace near-miss transcriptions with them: ")
            sb.append(vocabulary.joinToString(", "))
            sb.append('.')
        }
        return sb.toString()
    }

    /**
     * Llama-family chat models emit an empty or reasoning <think> block and
     * then often parrot the user turn ("Instruction: ... Text: ...") before
     * actually answering (observed on SpeakoFlow Mini AND BitVoice Llama3.2:
     * 549-712-char outputs whose first 120 chars are pure echo, some with a
     * plausible answer after it). Take the model's LAST line as the answer:
     * single-line outputs are pass-through, multi-line ones usually look
     * like [echo lines] + [real answer].
     */
    internal fun extractAnswer(transcript: String, raw: String): String {
        val body = sanitizeThink(raw)
        val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return ""
        if (lines.size == 1) return lines[0]
        val candidates = lines.filter { line ->
            !line.startsWith("Instruction:") && !line.startsWith("Text:")
        }
        val pool = candidates.ifEmpty { lines }
        return pool.last()
    }

    /** Strip leading <think>...</think> blocks; keep post-think content. */
    internal fun sanitizeThink(raw: String): String {
        var s = raw.trim()
        while (s.startsWith("<think>")) {
            val end = s.indexOf("</think>")
            if (end < 0) return ""
            s = s.substring(end + "</think>".length).trim()
        }
        return s
    }

    /** Visible diagnostics: log refine outcomes from the caller. */
    fun logOutcome(original: String, refined: String?) {
        if (refined == null) Log.i(TAG, "refine: kept original (no result): '$original'")
        else if (refined == original) Log.i(TAG, "refine: unchanged: '$original'")
        else Log.i(TAG, "refine: '$original' -> '$refined'")
    }

    private fun noteRejection() {
        consecutiveRejections++
        if (consecutiveRejections == MAX_CONSECUTIVE_REJECTIONS) {
            Log.w(TAG, "offline LLM rejected $MAX_CONSECUTIVE_REJECTIONS outputs in a row; " +
                "degrading to deterministic corrector for this session")
        }
    }

    private fun resetRejections() {
        consecutiveRejections = 0
    }

    private suspend fun runCloud(context: Context, prompt: String): String? {
        return try {
            val service = helium314.keyboard.latin.utils.ProofreadService(context)
            if (!service.hasApiKey()) return null
            val result = withTimeout(CLOUD_TIMEOUT_MS) {
                service.proofread("", overridePrompt = prompt)
            }
            result.getOrNull()?.takeIf { it.isNotBlank() }
        } catch (t: Throwable) {
            Log.w(TAG, "Cloud AI post-processing failed: ${t.message}")
            null
        }
    }

    /**
     * Instruction prompt for general-purpose (cloud) models. Dictation-tuned
     * local models must NOT receive this: they were fine-tuned on bare
     * transcripts and copy prompt rules into their output when instructed.
     */
    private fun buildPrompt(text: String, vocabulary: List<String>): String {
        val sb = StringBuilder()
        sb.append(
            "You are a dictation post-processor. Fix ONLY transcription artifacts in the text below. " +
            "Rules:\n" +
            "- Keep the speaker's language, wording and meaning EXACTLY. Never rewrite style, never add or remove information.\n" +
            "- PRESERVE the existing punctuation exactly as given (commas, periods, hyphens). Only fix punctuation the speaker explicitly dictated wrong.\n" +
            "- Fix misheard words using context (e.g. \"in you bug\" -> \"a new bug\").\n" +
            "- Join wrongly split or merged words (\"Light LLM\" -> \"LiteLLM\") and split wrongly joined ones.\n" +
            "- PRESERVE hyphenation and compound words exactly as transcribed (\"post-processing\" stays hyphenated).\n" +
            "- Replace near-misses of the priority vocabulary below with the exact vocabulary form.\n" +
            "- Spoken symbol names followed by content convert to symbols: \"open bracket ... close bracket\" -> \"(...)\"; " +
            "a standalone \"slash\" between two nouns means the / character, but \"slash\" as a verb (\"slash the costs\") stays a word.\n" +
            "- Output ONLY the corrected text, nothing else, no quotes, no commentary.\n"
        )
        if (vocabulary.isNotEmpty()) {
            sb.append("Priority vocabulary (prefer these exact forms): ")
            sb.append(vocabulary.joinToString(", "))
            sb.append('\n')
        }
        sb.append("\nText:\n").append(text)
        return sb.toString()
    }

    /** Strip model chatter, verify sanity, or return null to keep the fallback. */
    private fun sanitize(original: String, raw: String): String? {
        var out = raw.trim()
        // Strip <think>...</think> reasoning blocks (model contamination
        // observed 2026-10-13: 4 segments got reasoning text committed).
        if (out.startsWith("<think>")) {
            val end = out.indexOf("</think>")
            out = if (end >= 0) out.substring(end + "</think>".length).trim() else ""
        }
        // Any remaining think-tag anywhere = contamination, reject entirely.
        if (out.contains("<think>") || out.contains("</think>")) return null
        // Drop common wrappers.
        if (out.startsWith("```")) {
            out = out.trim('`').removePrefix("text").trim()
        }
        out = out.trim('"', '«', '»', ' ')
        if (out.isEmpty()) return null
        // Reject prompt echo / commentary.
        val lower = out.lowercase()
        if (lower.startsWith("text:") || lower.startsWith("rules:") || lower.startsWith("output:") ||
            lower.startsWith("instruction:")) {
            out = out.lines().drop(1).joinToString("\n").trim()
        }
        if (out.isEmpty()) return null
        if (lower.contains("corrected text") || lower.contains("priority vocabulary")) return null
        // Length sanity: replacement must not be a wholesale different text,
        // nor an echo-repetition (observed: model repeating the transcript
        // up to 3x on longer inputs — word overlap passes trivially).
        if (out.length < original.length * MIN_FRACTION) return null
        if (out.length > original.length * 1.5f) return null
        // Word-overlap sanity: a valid cleanup keeps most of the input words.
        // Guards against same-length fabrications (observed: model replaced
        // dictation with an unrelated repeated sentence of similar length).
        val inWords = original.lowercase().split(Regex("[^a-zа-яё0-9']+")).
            filter { it.length > 1 }.toSet()
        if (inWords.size >= 4) {
            val outWords = out.lowercase().split(Regex("[^a-zа-яё0-9']+")).
                filter { it.length > 1 }.toSet()
            val overlap = inWords.count { it in outWords }
            if (overlap < inWords.size * 0.5) {
                Log.w(TAG, "refine rejected: word overlap $overlap/${inWords.size}")
                return null
            }
        }
        // Punctuation sanity: the model must not strip existing commas.
        val commasIn = original.count { it == ',' }
        val commasOut = out.count { it == ',' }
        if (commasIn >= 1 && commasOut == 0) return null
        return out
    }
}
