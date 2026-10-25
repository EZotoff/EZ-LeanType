#!/usr/bin/env python3
"""Score VoiceHarnessService results against voice_test/corpus.json.

Usage: python3 score.py [results_dir] [out_md]
Defaults: results_dir=voice_test/results/device, out_md=voice_test/results/baseline.md
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
CORPUS = ROOT / "corpus.json"


def wer(ref: str, hyp: str) -> float:
    """Word error rate via token-level Levenshtein."""
    r, h = ref.split(), hyp.split()
    if not r:
        return 0.0 if not h else 1.0
    prev = list(range(len(h) + 1))
    for i, rt in enumerate(r, 1):
        cur = [i] + [0] * len(h)
        for j, ht in enumerate(h, 1):
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (rt != ht))
        prev = cur
    return prev[-1] / len(r)


def score_block(block: dict, result: dict) -> dict:
    hyp = result.get("finalText", "").strip()
    expected = block["expected"].strip()
    checks = block.get("checks", {})
    failures, passes = [], []

    def contains(needle, ok, fail_msg=None):
        if needle in hyp:
            passes.append(ok)
        else:
            failures.append(fail_msg or f"missing: {needle!r}")

    for needle in checks.get("contains", []):
        contains(needle, f"contains {needle!r}")
    for needle in checks.get("absent", []):
        if needle.lower() in hyp.lower():
            failures.append(f"forbidden present: {needle!r}")
        else:
            passes.append(f"absent {needle!r}")
    for name, pattern in (checks.get("must_regex") or {}).items():
        if re.search(pattern, hyp):
            passes.append(f"regex {name}")
        else:
            failures.append(f"regex {name} ({pattern}) not matched")

    return {
        "id": block["id"],
        "feature": block.get("feature", ""),
        "wer": round(wer(expected, hyp), 4),
        "checks_passed": len(passes),
        "checks_failed": len(failures),
        "failures": failures,
        "segments": len(result.get("segments", [])),
        "commands": result.get("commands", []),
        "echoDropped": result.get("echoDropped", []),
        "aiUsableAtStart": result.get("aiUsableAtStart"),
        "refinedCount": sum(1 for s in result.get("segments", []) if s.get("refined") not in (None,)),
        "wallMs": result.get("wallMs"),
        "finalText": hyp,
        "known_limitations": checks.get("known_limitations", []),
    }


def main():
    results_dir = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "results" / "device"
    out_md = Path(sys.argv[2]) if len(sys.argv) > 2 else ROOT / "results" / "baseline.md"

    corpus = json.loads(CORPUS.read_text())
    rows, summary = [], []
    for block in corpus["blocks"]:
        rf = results_dir / f"{block['id']}.json"
        if not rf.exists():
            rows.append({"id": block["id"], "error": "missing result file"})
            continue
        result = json.loads(rf.read_text())
        if result.get("error"):
            rows.append({"id": block["id"], "error": result["error"]})
            continue
        s = score_block(block, result)
        rows.append(s)
        summary.append(s)

    lines = ["# Voice corpus results", ""]
    lines.append("| block | feature | WER | checks | segments | refined | wall s | verdict |")
    lines.append("|---|---|---|---|---|---|---|---|")
    for r in rows:
        if "error" in r:
            lines.append(f"| {r['id']} | - | - | - | - | - | - | ERROR: {r['error']} |")
            continue
        verdict = "PASS" if r["checks_failed"] == 0 and r["wer"] <= 0.15 else "FAIL"
        lines.append(
            f"| {r['id']} | {r['feature']} | {r['wer']*100:.1f}% | "
            f"{r['checks_passed']}/{r['checks_passed']+r['checks_failed']} | "
            f"{r['segments']} | {r['refinedCount']} | {r['wallMs']/1000:.0f} | {verdict} |"
        )
    lines.append("")
    for r in rows:
        if "error" in r:
            continue
        if r["failures"] or r["known_limitations"]:
            lines.append(f"## {r['id']} ({r['feature']})")
            for f in r["failures"]:
                lines.append(f"- FAIL {f}")
            for k in r["known_limitations"]:
                lines.append(f"- known-limitation: {k}")
            lines.append("")
    lines.append("## Transcripts")
    for r in rows:
        if "error" in r:
            continue
        lines.append(f"### {r['id']}")
        lines.append("```")
        lines.append(r["finalText"])
        lines.append("```")
        lines.append("")
    out_md.write_text("\n".join(lines))
    (out_md.parent / "baseline.json").write_text(json.dumps(rows, indent=2, ensure_ascii=False))
    print("\n".join(lines[:12]))


if __name__ == "__main__":
    main()
