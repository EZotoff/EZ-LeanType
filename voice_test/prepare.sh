#!/bin/sh
# Golden voice corpus prep: AAC takes -> 16 kHz mono s16 WAV, mean-volume
# normalized to -26 dBFS (peak ceiling -3 dB), leading/trailing silence trimmed.
# Deterministic: same input -> same output bytes (no loudnorm dynamic mode).
set -e
cd "$(dirname "$0")"
mkdir -p wav
TARGET_MEAN=-26
PEAK_CEIL=-3

for f in recordings/B*.aac; do
  name=$(basename "$f" .aac)
  # measure
  stats=$(ffmpeg -hide_banner -i "$f" -af volumedetect -f null - 2>&1)
  mean=$(printf '%s\n' "$stats" | sed -n 's/.*mean_volume: \(-\?[0-9.]*\) dB.*/\1/p' | head -1)
  max=$(printf '%s\n' "$stats" | sed -n 's/.*max_volume: \(-\?[0-9.]*\) dB.*/\1/p' | head -1)
  # gain to reach target mean, clamped by peak ceiling
  g_mean=$(awk -v t=$TARGET_MEAN -v m=$mean 'BEGIN{printf "%.2f", t - m}')
  g_peak=$(awk -v c=$PEAK_CEIL -v p=$max 'BEGIN{printf "%.2f", c - p}')
  gain=$(awk -v a=$g_mean -v b=$g_peak 'BEGIN{print (a < b) ? a : b}')
  echo "$name: mean=$mean max=$max gain=${gain}dB"
  ffmpeg -hide_banner -loglevel error -y -i "$f" -af \
    "silenceremove=start_periods=1:start_threshold=-45dB:start_silence=0.25,areverse,silenceremove=start_periods=1:start_threshold=-45dB:start_silence=0.25,areverse,volume=${gain}dB,aresample=16000" \
    -ac 1 -c:a pcm_s16le "wav/${name}.wav"
done
echo "--- verify ---"
for f in wav/B*.wav; do
  d=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$f")
  v=$(ffmpeg -i "$f" -af volumedetect -f null - 2>&1 | grep -E 'mean_volume|max_volume' | sed 's/.*: //' | tr '\n' ' ')
  echo "$f | ${d}s | $v"
done
