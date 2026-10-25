# Bark 3.0 spike — on-device evaluation (2026-10-25, decision pending)

Variants compared (all sha256-verified velddev sherpa-onnx int8 exports,
same harness): **base** 163 MB · **Plus** 180 MB · **Clean** 180 MB — Pro
skipped (RAM). WER vs spoken (raw, no text stage):

| block | base | plus | clean | Phonon+text vs expected |
|---|---|---|---|---|
| B0 | **15.0%** | 30.0% | 25.0% | 25.0% |
| B1 | **13.3%** | 15.6% | 17.8% | 20.4% |
| B2 | 41.7% | 41.7% | **38.9%** | 25.0% |
| B3 | **13.9%** | **13.9%** | 20.8% | 20.8% |
| B4 | 40.9% | **38.6%** | **38.6%** | 11.8% |
| B5 | 65.1% | 51.2% | **48.8%** | 100.0% |
| B6 | **35.9%** | 65.2% | 47.8% | 58.7% |

Leaderboard ordering does NOT transfer to this corpus: base is the best of
the three on the user's voice. Plus mishears more (Lint Eye Poise, Mauru);
Clean hallucinates ALL-CAPS ("LINTELS VALDATION") and worsens B3. Plus's
only win: hears "comma" where base writes "coma" (fix #1 covers both).
B2 tech-vocabulary weakness is family-wide.

## Combo: Bark base + LeanType text stage (fix #1 + fillers + vocab corrector)

BarkComboEvaluationTest (JVM, transcripts in test resources) — same flags as
the harness, corrector active, scored vs expected:

| block | Bark+pipeline | Phonon system | verdict |
|---|---|---|---|
| B0 | **15.0%** | 25.0% | bark |
| B1 | **11.4%** | 20.4% | bark — corrector recovered Mauro, OC Beacon; "Ilan"→Elan d1 MISS (gap) |
| B2 | 33.3% | **25.0%** | phonon — Qwen/Vercel recovered; ez-omo/Conf-UI compounds missed |
| B3 | **12.5%** | 20.8% | bark |
| B4 | 23.5% | **11.8%** | phonon — two-word "Semi colon." unmapped (fix #1 variant), ".!" edge |
| B5 | 111.5% | 100.0% | both broken — fix #3/#4 territory, engine-independent |
| B6 | **35.9%** | 58.7% | bark — no hallucinated repeats |

Average: Bark+pipeline ≈34.7% vs system ≈37.4% — Bark edges ahead with
known fixable handicaps (B4 semi-colon variant, B5 symbols apply to both).



Unofficial sherpa-onnx int8 export (velddev/Underdog-Bark-3.0-sherpa-onnx,
sha256-verified vs pinned LFS oids) run with sherpa-onnx v2.0.0 CLI
(linux-aarch64, glibc 2.41 loader from toolchain root, 4 threads) on THIS
phone's CPU, over voice_test WAVs + 0.25 s leading silence (their recipe).

| metric | value |
|---|---|
| RTF @ 4 threads | 0.090 (≈11× realtime) |
| Peak RSS (B6, VmHWM poll) | 1435 MB |
| Model on disk (int8, 4 files) | 684 MB |
| License | CC-BY 4.0, English-only |

WER, Bark RAW (no text stage) vs spoken — against the shipped system
(Phonon-2 + text stage) vs expected, from fix1-punct.md:

| block | Bark raw vs spoken | system (Phonon+text) vs expected |
|---|---|---|
| B0 calibration | 15.0% | 25.0% |
| B1 names | 13.3% | 20.4% |
| B2 tech/dict | **41.7%** | 25.0% |
| B3 homophones | 13.9% | 20.8% |
| B4 punct (raw) | 40.9% | 11.8% |
| B5 symbols (raw) | 65.1% | 100.0% |
| B6 long-form | **35.9%** | 58.7% |

Notes: raw-vs-system comparison — Bark had no text stage here. Bark hears
Oren/Veran/colon/twelve October correctly (Phonon: Oran/dropped colon/twelfth)
and produces no hallucinated repeats (B6). It is WORSE on rare tech terms
(Quen, light l. m., Versel, Conf UI — matches their own "hard names"
benchmark weakness) and still outputs spoken-punct words needing the text
stage ("coma", "Question mark.", "Semi colon." — note: TWO-word "semi colon"
needs a new pattern variant). Pseudo-streaming feasible at RTF 0.09 by
re-decoding the growing VAD segment.

Spike scripts: .harness/bark_{dl,prep,run,score,rss}.sh/.py; transcripts in
/data/local/tmp/androidharness-scratch/bark/transcripts (scratch, ephemeral).

## Fair-comparison addendum (post user review, 2026-10-25)

- The "Phonon system" column was always Phonon + FULL device pipeline
  (corpus run with fix #1). No raw-Phonon comparison was made.
- Phonon raw finals CANNOT be reconstructed from device JSONs: streaming
  partials rewrite (not only append) and lag committed finals (B2 last
  partial "Quen" vs final "Qwen"); segment-reset heuristics duplicate text.
  -> Harness change queued: also record each segment's PRE-preTransform
  engine final, so future engine comparisons get raw-vs-raw for free.
- Bark through the same path + sentence-casing approximation: base avg
  35.5% (preTransform-only: 34.7%) vs phonon+pipe 37.4% — the two Bark
  numbers bracket the truth; the casing approximation OVER-capitalizes
  after non-terminal periods ("LiteLLM. Routes") because the real chain
  capitalizes only at segment starts (streaming info Bark offline lacks).
- Net: Bark base + pipe ≈ 1.5–2.5 points better than phonon+pipe on the
  corpus average (B0/B1/B3/B6 wins; B2/B4 losses), within disclosed
  approximation error. Single take per block.

## Phonon-2 RAM (measured 2026-10-25, headless B0 + dumpsys meminfo polling)
- Keyboard process baseline RSS ~981 MB (PSS ~2.75 GB incl. ~1.88 GB swapped).
- Peak during Phonon voice session: RSS ~1030 MB -> engine incremental
  footprint ~+45-50 MB resident (164 MB int8 model on disk, evidently
  mmap'd/lazily resident; ~41 MB of cold pages swapped out to make room).
- Compare Bark int8 standalone CLI: 1435 MB peak (684 MB int8 files).
  Incremental-memory ratio ~30x. In-app Bark would land somewhere between
  the model file size and the CLI peak, but the order of magnitude stands.
