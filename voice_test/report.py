#!/usr/bin/env python3
"""Spoken vs transcribed report from corpus.json + device results."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
corpus = json.loads((ROOT / "corpus.json").read_text())
res_dir = ROOT / "results" / "device"

out = ["# Voice corpus: spoken vs transcribed", "",
       "Build: 66024bf + harness (production behavior unchanged this session —",
       "pipeline extraction only). Engine: Phonon-2, AI post-processing ON.", ""]

for b in corpus["blocks"]:
    rf = res_dir / f"{b['id']}.json"
    r = json.loads(rf.read_text()) if rf.exists() else {}
    hyp = r.get("finalText", "(missing)").strip()
    out += [f"## {b['id']} — {b.get('feature','')}", "",
            "**Spoken:**", "", b["spoken"], "",
            "**Transcribed:**", "", hyp, "",
            f"segments: {len(r.get('segments', []))} | refined-by-AI: "
            f"{sum(1 for s in r.get('segments', []) if s.get('refined'))} | "
            f"wall: {r.get('wallMs', 0)/1000:.0f}s", ""]

(ROOT / "results" / "report.md").write_text("\n".join(out))
print("\n".join(out))
