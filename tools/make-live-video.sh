#!/usr/bin/env bash
# Records the live-set video from the real desktop app and encodes the clips:
# two distinct parts, UK drum and bass (170 BPM) and hardtekno tribe (155 BPM), played with the built-in
# live sets and their scenes (android/engine/.../live/LiveSets.kt, scripted in GuideVideo.kt, scenario "live").
#   tools/make-live-video.sh [work-dir]
# Needs a built desktop app (cd android && ./gradlew -PdesktopOnly :desktop:installDist), ffmpeg
# (libx264, libvpx-vp9, libopus, libwebp) and an X display (Xvfb works: DISPLAY=:99).
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
work="${1:-$(mktemp -d)}"
assets="$root/assets"
mkdir -p "$work" "$assets"

if [ ! -f "$work/audio.wav" ]; then
  "$root/android/desktop/build/install/vtlo-3o3/bin/vtlo-3o3" --video "$work" --scenario live
fi

fps=15
dur=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$work/audio.wav")
fade=$(python3 -c "print(max(0, $dur - 2.6))")
# Loudness for the web, a limiter so lossy encoding cannot clip, a fade under the closing card.
af="loudnorm=I=-16:TP=-3:LRA=11,alimiter=limit=0.6:level=disabled,afade=t=out:st=$fade:d=2.6"
ffmpeg -y -loglevel error -framerate $fps -i "$work/frame%05d.png" -i "$work/audio.wav" \
  -vf "scale=960:540:flags=lanczos" -c:v libvpx-vp9 -b:v 0 -crf 38 -deadline good -cpu-used 1 -row-mt 1 -pix_fmt yuv420p \
  -af "$af" -c:a libopus -b:a 56k -shortest "$assets/live-set.webm"
ffmpeg -y -loglevel error -framerate $fps -i "$work/frame%05d.png" -i "$work/audio.wav" \
  -vf "scale=960:540:flags=lanczos" -c:v libx264 -preset veryslow -crf 31 -profile:v main -pix_fmt yuv420p -movflags +faststart \
  -af "$af" -c:a aac -b:a 96k -shortest "$assets/live-set.mp4"
ffmpeg -y -loglevel error -i "$work/audio.wav" -af "$af" -c:a libmp3lame -b:a 192k "$work/track.mp3"
# Poster: the first scene of part 1.
ffmpeg -y -loglevel error -i "$work/frame00180.png" -vf "scale=960:540:flags=lanczos" -q:v 6 "$assets/live-set-poster.jpg"
# README: a silent animated WebP (GitHub plays it automatically), both parts in a few seconds each.
ffmpeg -y -loglevel error -framerate $fps -i "$work/frame%05d.png" \
  -vf "fps=6,scale=640:360:flags=lanczos" -c:v libwebp_anim -lossless 0 -q:v 40 -compression_level 6 -loop 0 "$assets/live-set.webp"
ls -la "$assets"/live-set*
