#!/usr/bin/env bash
# Generates 1 kHz sine and silence test clips in WAV/MP3/AAC/MP4 for the audio
# verification matrix. Output directory is the first argument.
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "usage: gen_assets.sh <output-dir>" >&2
  exit 2
fi

OUT="$1"
mkdir -p "$OUT"

if ! command -v ffmpeg >/dev/null 2>&1; then
  echo "gen_assets.sh: ffmpeg not found on PATH" >&2
  exit 3
fi

# Idempotency: skip if all targets already exist and are non-empty.
TARGETS=(
  "$OUT/sine_1k_48k_16b_5s.wav"
  "$OUT/sine_1k_48k_16b_5s.mp3"
  "$OUT/sine_1k_48k_16b_5s.m4a"
  "$OUT/sine_1k_48k_16b_5s.mp4"
  "$OUT/silence_500ms.wav"
)
ALL_OK=1
for t in "${TARGETS[@]}"; do
  if [[ ! -s "$t" ]]; then ALL_OK=0; break; fi
done
if [[ "$ALL_OK" -eq 1 ]]; then
  echo "gen_assets.sh: all assets present, skipping"
  exit 0
fi

SR=48000
DUR=5
AMP=0.5  # -6 dBFS

# 1 kHz sine, stereo, 16-bit PCM WAV, 48 kHz, 5 s, -6 dBFS
ffmpeg -y -hide_banner -loglevel error \
  -f lavfi -i "sine=frequency=1000:sample_rate=${SR}:duration=${DUR}" \
  -af "volume=${AMP},pan=stereo|c0=c0|c1=c0" \
  -c:a pcm_s16le \
  "$OUT/sine_1k_48k_16b_5s.wav"

# MP3 192 kbps CBR
ffmpeg -y -hide_banner -loglevel error \
  -i "$OUT/sine_1k_48k_16b_5s.wav" \
  -c:a libmp3lame -b:a 192k -ar ${SR} \
  "$OUT/sine_1k_48k_16b_5s.mp3"

# AAC-LC 128 kbps in M4A container
ffmpeg -y -hide_banner -loglevel error \
  -i "$OUT/sine_1k_48k_16b_5s.wav" \
  -c:a aac -b:a 128k -ar ${SR} \
  "$OUT/sine_1k_48k_16b_5s.m4a"

# AAC-LC 128 kbps in MP4 container
ffmpeg -y -hide_banner -loglevel error \
  -i "$OUT/sine_1k_48k_16b_5s.wav" \
  -c:a aac -b:a 128k -ar ${SR} -movflags +faststart \
  "$OUT/sine_1k_48k_16b_5s.mp4"

# 500 ms silence WAV (offload boundary pad)
ffmpeg -y -hide_banner -loglevel error \
  -f lavfi -i "anullsrc=r=${SR}:cl=stereo" -t 0.5 \
  -c:a pcm_s16le \
  "$OUT/silence_500ms.wav"

echo "gen_assets.sh: wrote $(ls -1 "$OUT" | wc -l) files to $OUT"
