#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 <input.ipa> <output.ipa>" >&2
  exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
INPUT_IPA="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
OUTPUT_IPA="$2"
[[ "$OUTPUT_IPA" = /* ]] || OUTPUT_IPA="$ROOT/$OUTPUT_IPA"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT

unzip -q "$INPUT_IPA" -d "$TEMP_DIR"
APP_PATH="$(find "$TEMP_DIR/Payload" -maxdepth 1 -type d -name '*.app' -print -quit)"
if [[ -z "$APP_PATH" ]]; then
  echo "The IPA does not contain a Payload/*.app bundle." >&2
  exit 1
fi
bash "$ROOT/packaging/ios/install-python-runtime-into-app.sh" "$APP_PATH"

mkdir -p "$(dirname "$OUTPUT_IPA")"
rm -f "$OUTPUT_IPA"
(cd "$TEMP_DIR" && zip -qry -y "$OUTPUT_IPA" .)
echo "Packaged unsigned IPA with embedded Python runtime: $OUTPUT_IPA"
