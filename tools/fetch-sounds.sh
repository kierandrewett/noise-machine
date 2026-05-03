#!/usr/bin/env bash
# Download the bundled noise-machine loops into app/src/main/assets/sounds/
# as OGG Vorbis files.
#
# This script runs on YOUR machine (it can't run inside the agent sandbox -
# egress is restricted there). Requires yt-dlp + ffmpeg in PATH.
#
# Usage:
#   tools/fetch-sounds.sh                           # use built-in defaults
#   tools/fetch-sounds.sh --ambient <youtube-url>   # override ambient track
#   tools/fetch-sounds.sh --rain    <pixabay-url>   # override rain track
#   tools/fetch-sounds.sh --skip-rain --ambient ... # skip rain, only ambient
#
# License notes:
#   * Pixabay sounds: free for personal & commercial use, no attribution.
#   * YouTube tracks: ONLY download tracks whose creator explicitly allows
#     downloading. Default is Scott Buckley's "The Long Dark" (CC-BY 4.0,
#     attribution required). Substitute any other CC-BY/CC0/public-domain
#     URL you prefer.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ASSETS_DIR="$SCRIPT_DIR/../app/src/main/assets/sounds"
mkdir -p "$ASSETS_DIR"

# Defaults
RAIN_URL="https://pixabay.com/sound-effects/nature-calming-rain-loop-398653/"
AMBIENT_URL="https://www.youtube.com/watch?v=M6JuO6QrUYE"  # Scott Buckley - The Long Dark (CC-BY 4.0)
SKIP_RAIN=0
SKIP_AMBIENT=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --rain)        RAIN_URL="$2";    shift 2 ;;
    --ambient)     AMBIENT_URL="$2"; shift 2 ;;
    --skip-rain)   SKIP_RAIN=1;      shift ;;
    --skip-ambient) SKIP_AMBIENT=1;  shift ;;
    -h|--help)
      grep -E '^#' "$0" | sed 's/^# \{0,1\}//'
      exit 0 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

require() {
  command -v "$1" >/dev/null 2>&1 || { echo "need '$1' in PATH" >&2; exit 1; }
}
require yt-dlp
require ffmpeg

fetch() {
  local url="$1" out="$2"
  echo "==> $out"
  echo "    from $url"
  # -x = extract audio; --audio-format vorbis = OGG/Vorbis; quality 5 ~= 160 kbps
  # We download into a temp file then rename, so we always end with the right name.
  yt-dlp \
    -x --audio-format vorbis --audio-quality 5 \
    --no-playlist \
    --restrict-filenames \
    -o "$ASSETS_DIR/__tmp_$out.%(ext)s" \
    "$url"
  # yt-dlp produces $ASSETS_DIR/__tmp_$out.ogg
  if [[ -f "$ASSETS_DIR/__tmp_$out.ogg" ]]; then
    mv -f "$ASSETS_DIR/__tmp_$out.ogg" "$ASSETS_DIR/$out.ogg"
  else
    # Fallback: pick whatever yt-dlp produced
    local produced
    produced=$(ls "$ASSETS_DIR"/__tmp_$out.* 2>/dev/null | head -1)
    if [[ -n "$produced" ]]; then
      ffmpeg -y -i "$produced" -c:a libvorbis -q:a 5 "$ASSETS_DIR/$out.ogg"
      rm -f "$produced"
    else
      echo "yt-dlp did not produce output for $out" >&2
      exit 1
    fi
  fi
  ls -lh "$ASSETS_DIR/$out.ogg"
}

if [[ $SKIP_RAIN -eq 0 ]]; then
  fetch "$RAIN_URL" rain
fi
if [[ $SKIP_AMBIENT -eq 0 ]]; then
  fetch "$AMBIENT_URL" ambient
fi

echo
echo "Done. Files in: $ASSETS_DIR"
ls -lh "$ASSETS_DIR" | grep -E '\.ogg$' || true

cat <<'NOTE'

Attribution reminder:
  If you used the default ambient track, credit Scott Buckley
  (https://www.scottbuckley.com.au) somewhere user-visible (e.g. the
  Settings tab text or your README), as required by the CC-BY 4.0 license.
NOTE
