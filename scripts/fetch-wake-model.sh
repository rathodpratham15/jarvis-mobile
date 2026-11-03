#!/usr/bin/env bash
# Downloads the offline Vosk model used for the "Hey Jarvis" wake word into the Android assets.
set -euo pipefail
DEST="$(cd "$(dirname "$0")/.." && pwd)/app/android/app/src/main/assets/model-en-us"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
curl -fL -o "$TMP/m.zip" https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip
unzip -q "$TMP/m.zip" -d "$TMP/x"
rm -rf "$DEST"; mkdir -p "$DEST"; cp -R "$TMP"/x/*/. "$DEST/"
echo "jarvis-vosk-small-en-us-0.15" > "$DEST/uuid"
echo "Model installed at $DEST"
