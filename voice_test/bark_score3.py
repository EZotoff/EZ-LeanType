#!/usr/bin/env python3
"""Compare base/Plus/Clean Bark transcripts on the corpus.

Usage: python3 bark_score3.py <base_dir> <plus_dir> <clean_dir>
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "voice_test"
corpus = json.loads((ROOT / "corpus.json").read_text())
BASELINE = {"B0": 25.0, "B1": 20.4, "B2": 25.0, "B3": 20.8,
            "B4": 11.8, "B5": 100.0, "B6": 58.7}


def wer(ref, hyp):
    r, h = ref.split(), hyp.split()
    if not r:
        return 0.0 if h else 1.0
    prev = list(range(len(h) + 1))
    for i, rt in enumerate(r, 1):
        cur = [i] + [0] * len(h)
        for j, ht in enumerate(h, 1):
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (rt != ht))
        prev = cur
    return prev[-1] / len(r)


def transcript(d, bid):
    tf = Path(d) / f"{bid}.log"
    if not tf.exists():
        return None
    for line in tf.read_text().splitlines():
        if line.startswith("{"):
            m = re.search(r'"text": "([^"]*)"', line)
            if m:
                return m.group(1).strip()
    return None


dirs = {"base": sys.argv[1], "plus": sys.argv[2], "clean": sys.argv[3]}
print("| block | base vs spoken | plus vs spoken | clean vs spoken | clean vs expected | phonon+text vs expected |")
print("|---|---|---|---|---|---|")
for b in corpus["blocks"]:
    bid = b["id"]
    cells, clean_exp = [], ""
    for name in ("base", "plus", "clean"):
        t = transcript(dirs[name], bid)
        cells.append(f"{wer(b['spoken'], t)*100:.1f}%" if t is not None else "MISSING")
        if name == "clean" and t is not None:
            clean_exp = f"{wer(b['expected'], t)*100:.1f}%"
    print(f"| {bid} | {cells[0]} | {cells[1]} | {cells[2]} | {clean_exp} | {BASELINE[bid]}% |")
print()
for name in ("plus", "clean"):
    print(f"## {name} transcripts")
    for b in corpus["blocks"]:
        t = transcript(dirs[name], b["id"])
        if t:
            print(f"{b['id']}: {t[:190]}")
    print()
