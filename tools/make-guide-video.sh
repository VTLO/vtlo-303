#!/usr/bin/env bash
# Records the French video guide from the real desktop app and encodes the lightweight clips.
#   tools/make-guide-video.sh [work-dir]
# Needs a built desktop app (cd android && ./gradlew -PdesktopOnly :desktop:installDist), ffmpeg
# (libx264, libvpx-vp9, libopus, libwebp) and an X display (Xvfb works: DISPLAY=:99).
# The session is scripted in android/desktop/src/main/kotlin/com/vtlo/desktop/GuideVideo.kt.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
work="${1:-$(mktemp -d)}"
assets="$root/assets"
mkdir -p "$work" "$assets"

if [ ! -f "$work/audio.wav" ]; then
  "$root/android/desktop/build/install/vtlo-3o3/bin/vtlo-3o3" --video "$work"
fi

fps=15
# Web page: VP9/Opus WebM first (smallest), H.264/AAC MP4 as the fallback; 960x540, tuned for flat UI graphics.
ffmpeg -y -loglevel error -framerate $fps -i "$work/frame%05d.png" -i "$work/audio.wav" \
  -vf "scale=960:540:flags=lanczos" -c:v libvpx-vp9 -b:v 0 -crf 38 -deadline good -cpu-used 1 -row-mt 1 -pix_fmt yuv420p \
  -c:a libopus -b:a 40k -shortest "$assets/guide.webm"
ffmpeg -y -loglevel error -framerate $fps -i "$work/frame%05d.png" -i "$work/audio.wav" \
  -vf "scale=960:540:flags=lanczos" -c:v libx264 -preset veryslow -crf 31 -profile:v main -pix_fmt yuv420p -movflags +faststart \
  -c:a aac -b:a 56k -shortest "$assets/guide.mp4"
# Poster shown until the video plays (and for visitors who prefer reduced motion).
ffmpeg -y -loglevel error -i "$work/frame00188.png" -vf "scale=960:540:flags=lanczos" -q:v 6 "$assets/guide-poster.jpg"
# README: a silent animated WebP, which GitHub plays automatically.
ffmpeg -y -loglevel error -framerate $fps -i "$work/frame%05d.png" \
  -vf "fps=8,scale=640:360:flags=lanczos" -c:v libwebp_anim -lossless 0 -q:v 42 -compression_level 6 -loop 0 "$assets/guide.webp"
ls -la "$assets"/guide*
