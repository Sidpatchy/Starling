#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 || ! -d "$1" ]]; then
  echo "Usage: $0 <Starling.app> <output.ipa>" >&2
  exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
INPUT_APP="$(cd "$1" && pwd)"
OUTPUT_IPA="$2"
[[ "$OUTPUT_IPA" = /* ]] || OUTPUT_IPA="$ROOT/$OUTPUT_IPA"

TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/starling-app-ipa.XXXXXX")"
trap 'rm -rf "$TEMP_DIR"' EXIT
mkdir -p "$TEMP_DIR/Payload"
APP_PATH="$TEMP_DIR/Payload/$(basename "$INPUT_APP")"
ditto "$INPUT_APP" "$APP_PATH"
bash "$ROOT/packaging/ios/install-python-runtime-into-app.sh" "$APP_PATH"

mkdir -p "$(dirname "$OUTPUT_IPA")"
rm -f "$OUTPUT_IPA"
ditto -c -k --sequesterRsrc --keepParent "$TEMP_DIR/Payload" "$OUTPUT_IPA"
echo "Packaged unsigned iOS app with embedded Python runtime: $OUTPUT_IPA"
