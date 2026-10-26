# LeanType voice — infrastructure optimizations & follow-up tasks (2026-10-26)

Two groups: (A) engine/runtime infrastructure — how the models RUN, not the
models themselves; (B) text-stage follow-ups from the corpus campaign.
Status column: verified on device / research-only / in progress.

## A. Infrastructure findings

### A1. Decoder-level hotwords (contextual biasing) — VERIFIED ON DEVICE
sherpa-onnx supports boosting a vocabulary list during transducer decoding
(Aho-Corasick over BPE tokens), `decoding_method=modified_beam_search` +
`hotwords_file` + `hotwords_score` (+ `bpe.vocab` derived from tokens.txt).
- Tested 2026-10-26 on the user's corpus WAVs with the user's 15-term vocab:
  ALL B1 names recovered (ELAN/OREN/MAURO/VERAN/NESTOR/AGENTIC) AND B2
  compounds the text-stage corrector cannot fix (EZ OMO BENCH, EZ OMO
  CONFIG, COMFY UI). RTF 0.06 -> 0.12 (still ~8x realtime on-device).
- Hotword casing passes through (uppercase in = uppercase out) — write the
  file in display case. Score 2.5 was too hot (OC BEACOSTO merge, one
  dropped word); tune 1.5-2.0 on the corpus.
- Caveat: NeMo TDT modified_beam_search has a known upstream bug (drops
  sentences on multi-pause audio, k2-fsa#3267; our VAD segment shape did not
  manifest it in testing, but B6 whole-file MBS lost punctuation/casing).
  Fix PR k2-fsa#4084 (+ length_reward option) is OPEN, not in v2.0.0.
- In-app work: TransducerEngine decodingMethod + hotwords wiring; bpe.vocab
  derivation at model install; hotwords.txt regenerated from the voice
  vocabulary pref; hotwords toggle in settings; corpus A/B greedy vs MBS.
- Same mechanism likely helps GigaAM (Russian names).

### A2. sherpa-onnx fork with TDT beam-search fix + length_reward (PR #4084)
Fixes #3267 (per-frame hypothesis buckets, NeMo-style blank expansion,
correct hotword score accounting, length_reward=0.5 recommended for
parakeet-tdt-0.6b-v3/v2). Their numbers: concat-clips WER 40.9% (master MBS)
-> 2.91% (fixed+reward); also recovers sentences greedy+int8 drops
(17.2% -> ~3%). Needs a custom sherpa-onnx AAR build (uni-pc has the NDK
toolchain) or waiting for upstream merge. Pairs with A1 (makes MBS safe
everywhere). Also relevant: their side-note that the int8 encoder drops
whole sentences on long paused audio — matches our occasional B1/B5
truncation flakiness.

### A3. Execution provider experiments (research-only)
- NNAPI (`provider="nnapi"`): deprecated since Android 15 but functional;
  can offload to the Snapdragon's GPU/DSP/NPU -> battery + RAM wins;
  nnapi-reference CPU fallback risk. One-line config change + corpus run to
  benchmark (works today, no fork needed).
- QNN (Qualcomm NPU): the proper NPU path; heavy integration (vendor libs,
  AAR rebuild). Big-later.
- XNNPACK: little benefit for dynamic-shape transformer encoders per
  community testing; low priority.

### A4. Thread-count benchmark (cheap)
We decode with 4 threads on an 8-core device. CLI harness makes an 8-thread
A/B trivial (~2 min). Decide by RTF + battery feel.

### A5. maxSpeechDuration 25s -> ~12s (RAM/latency)
Peak RAM (~1.4 GB) is dominated by activations for long offline decodes;
halving the max segment cuts peak activation memory and final latency, at
the cost of more cold-start segment boundaries. Corpus-measurable.

### A6. Idle engine unload (LMK safety, high value)
The engine (and its ~1.4 GB) stays resident in the keyboard process after
dictation ends. Add an idle-timeout releaseContext (2-3 min after last
session) so a sleeping keyboard doesn't hold 1.4 GB. Pure infra, big
low-memory-kill risk reduction.

## B. Text-stage follow-ups (from corpus campaign memory)

- B1. fix #2b — VocabularyCorrector compound terms: "Light LLM"->LiteLLM
  double-word, Easy Omo Bench->ez-omo-bench, Conf->Comfy UI. Partially
  superseded by A1 (hotwords fixes these at decode); still worth hardening
  as fallback for when hotwords are off/miss.
- B2. fix #1 v2 — redundant-symbol edges (user-visible): two-word
  "semi colon", ".!" dedup edge, Bark's spelled-acronym dots ("p. m.",
  "h. t. p. slash").
- B3. fix #3 — SpokenSymbolProcessor variants (B5 ~100% WER): plural
  "open/close quotes", "open parent/close parent".
- B4. fix #4 — trailing-command split ("... filler words new line.").
- B5. In-app peak RSS measurement for Bark (poller ready; corpus runs are
  now PiP-free). Feeds A5/A6 verification.

## Suggested order (impact x risk)
1. A1 hotwords (verified win on the user's exact pain: names/terms)
2. A6 idle unload + B5 RSS measure (stability)
3. B2 fix #1 v2 symbols (user's live complaint), then B3, B4
4. A3 NNAPI + A4 threads benchmarks (cheap experiments, ride-along)
5. A2 sherpa fork build (only when A1 shows MBS instability in-app, or
   upstream merges and we bump the dependency)
