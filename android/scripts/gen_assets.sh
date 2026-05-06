#!/usr/bin/env bash
# Generates 6 distinct sine tones (one per StreamType) plus a silence pad,
# in WAV/MP3/M4A/MP4 — total 6 freq * 4 formats = 24 audio files.
#
# Stream type → frequency:
#   RINGTONE     440 Hz (A4)
#   DEEP_BUFFER  523 Hz (C5)
#   FAST_LL      659 Hz (E5)
#   ULL          784 Hz (G5)
#   OFFLOAD      880 Hz (A5)
#   FAST_OTHERS  988 Hz (B5)
#
# Output filename pattern: sine_<freq>_48k_5s.{wav,mp3,m4a,mp4}
# Output directory is the first argument.

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

SR=48000
DUR=5
AMP=0.5  # -6 dBFS
FREQS=(440 523 659 784 880 988)

# Idempotency — if every expected file is already non-empty, skip.
ALL_OK=1
for F in "${FREQS[@]}"; do
  for EXT in wav mp3 m4a mp4; do
    if [[ ! -s "$OUT/sine_${F}_48k_5s.${EXT}" ]]; then
      ALL_OK=0
    fi
  done
done
if [[ ! -s "$OUT/silence_500ms.wav" ]]; then ALL_OK=0; fi

if [[ "$ALL_OK" -eq 1 ]]; then
  echo "gen_assets.sh: all 25 assets present, skipping"
  exit 0
fi

for F in "${FREQS[@]}"; do
  WAV="$OUT/sine_${F}_48k_5s.wav"
  ffmpeg -y -hide_banner -loglevel error \
    -f lavfi -i "sine=frequency=${F}:sample_rate=${SR}:duration=${DUR}" \
    -af "volume=${AMP},pan=stereo|c0=c0|c1=c0" \
    -c:a pcm_s16le \
    "$WAV"

  ffmpeg -y -hide_banner -loglevel error \
    -i "$WAV" \
    -c:a libmp3lame -b:a 192k -ar ${SR} \
    "$OUT/sine_${F}_48k_5s.mp3"

  ffmpeg -y -hide_banner -loglevel error \
    -i "$WAV" \
    -c:a aac -b:a 128k -ar ${SR} \
    "$OUT/sine_${F}_48k_5s.m4a"

  ffmpeg -y -hide_banner -loglevel error \
    -i "$WAV" \
    -c:a aac -b:a 128k -ar ${SR} -movflags +faststart \
    "$OUT/sine_${F}_48k_5s.mp4"
done

ffmpeg -y -hide_banner -loglevel error \
  -f lavfi -i "anullsrc=r=${SR}:cl=stereo" -t 0.5 \
  -c:a pcm_s16le \
  "$OUT/silence_500ms.wav"

echo "gen_assets.sh: wrote $(ls -1 "$OUT" | wc -l) files to $OUT"
