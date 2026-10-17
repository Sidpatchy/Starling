#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 <unsigned.ipa> <signed.ipa>" >&2
  exit 2
fi

: "${IOS_DISTRIBUTION_CERTIFICATE_BASE64:?Set the Apple Distribution certificate secret.}"
: "${IOS_DISTRIBUTION_CERTIFICATE_PASSWORD:?Set the Apple Distribution certificate password secret.}"
: "${IOS_APP_STORE_PROVISIONING_PROFILE_BASE64:?Set the App Store provisioning profile secret.}"
: "${APPLE_TEAM_ID:?Set the Apple Developer team ID secret.}"
: "${ASC_KEY_ID:?Set the App Store Connect API key ID secret.}"
: "${ASC_ISSUER_ID:?Set the App Store Connect API issuer ID secret.}"
: "${ASC_PRIVATE_KEY:?Set the App Store Connect API private key secret.}"
: "${IOS_MARKETING_VERSION:?Set the iOS marketing version.}"
: "${IOS_BUILD_NUMBER:?Set the iOS build number.}"

INPUT_IPA="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
OUTPUT_IPA="$2"
[[ "$OUTPUT_IPA" = /* ]] || OUTPUT_IPA="$PWD/$OUTPUT_IPA"
[[ -f "$INPUT_IPA" ]] || { echo "Unsigned IPA not found: $INPUT_IPA" >&2; exit 1; }

WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/starling-sign.XXXXXX")"
KEYCHAIN_PATH="$WORK_DIR/signing.keychain-db"
KEYCHAIN_PASSWORD="$(openssl rand -hex 24)"
cleanup() {
  security delete-keychain "$KEYCHAIN_PATH" >/dev/null 2>&1 || true
  rm -rf "$WORK_DIR"
}
trap cleanup EXIT

python3 - "$WORK_DIR/distribution.p12" "$WORK_DIR/profile.mobileprovision" <<'PY'
import base64
import os
import sys

for secret, destination in zip(
    ("IOS_DISTRIBUTION_CERTIFICATE_BASE64", "IOS_APP_STORE_PROVISIONING_PROFILE_BASE64"),
    sys.argv[1:],
):
    with open(destination, "wb") as output:
        output.write(base64.b64decode(os.environ[secret], validate=False))
PY

if ! openssl pkcs12 \
  -in "$WORK_DIR/distribution.p12" \
  -noout \
  -passin env:IOS_DISTRIBUTION_CERTIFICATE_PASSWORD \
  >/dev/null 2>&1; then
  echo "The P12 certificate and password secrets do not match." >&2
  exit 1
fi

security create-keychain -p "$KEYCHAIN_PASSWORD" "$KEYCHAIN_PATH"
security set-keychain-settings -lut 21600 "$KEYCHAIN_PATH"
security unlock-keychain -p "$KEYCHAIN_PASSWORD" "$KEYCHAIN_PATH"
security import "$WORK_DIR/distribution.p12" \
  -k "$KEYCHAIN_PATH" \
  -P "$IOS_DISTRIBUTION_CERTIFICATE_PASSWORD" \
  -T /usr/bin/codesign \
  -T /usr/bin/security
security set-key-partition-list \
  -S apple-tool:,apple:,codesign: \
  -s \
  -k "$KEYCHAIN_PASSWORD" \
  "$KEYCHAIN_PATH"
security list-keychains -d user -s "$KEYCHAIN_PATH"

SIGNING_IDENTITY="$(security find-identity -v -p codesigning "$KEYCHAIN_PATH" |
  awk -F '"' '/Apple Distribution:/ { print $2; exit }')"
if [[ -z "$SIGNING_IDENTITY" ]]; then
  echo "The P12 does not contain a valid Apple Distribution signing identity." >&2
  exit 1
fi

security cms -D -i "$WORK_DIR/profile.mobileprovision" -o "$WORK_DIR/profile.plist"
ditto -x -k "$INPUT_IPA" "$WORK_DIR"
APP_PATH="$(python3 - "$INPUT_IPA" "$WORK_DIR" <<'PY'
import pathlib
import sys

_, destination = sys.argv[1:]
apps = list((pathlib.Path(destination) / "Payload").glob("*.app"))
if len(apps) != 1:
    raise SystemExit(f"Expected one Payload/*.app bundle, found {len(apps)}")
print(apps[0])
PY
)"
INFO_PLIST="$APP_PATH/Info.plist"
APP_BUNDLE_ID="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$INFO_PLIST")"
PROFILE_TEAM_ID="$(/usr/libexec/PlistBuddy -c 'Print :TeamIdentifier:0' "$WORK_DIR/profile.plist")"
PROFILE_APP_ID="$(/usr/libexec/PlistBuddy -c 'Print :Entitlements:application-identifier' "$WORK_DIR/profile.plist")"
PROFILE_GET_TASK_ALLOW="$(/usr/libexec/PlistBuddy -c 'Print :Entitlements:get-task-allow' "$WORK_DIR/profile.plist")"
if [[ "$PROFILE_TEAM_ID" != "$APPLE_TEAM_ID" ]]; then
  echo "Provisioning profile team '$PROFILE_TEAM_ID' does not match APPLE_TEAM_ID." >&2
  exit 1
fi
if [[ "$PROFILE_APP_ID" != "$APPLE_TEAM_ID.$APP_BUNDLE_ID" ]]; then
  echo "Provisioning profile is for '$PROFILE_APP_ID', not '$APPLE_TEAM_ID.$APP_BUNDLE_ID'." >&2
  exit 1
fi
if [[ "$PROFILE_GET_TASK_ALLOW" != "false" ]]; then
  echo "The provisioning profile is not an App Store distribution profile." >&2
  exit 1
fi

set_or_add_plist_value() {
  local key="$1" value="$2"
  if /usr/libexec/PlistBuddy -c "Print :$key" "$INFO_PLIST" >/dev/null 2>&1; then
    /usr/libexec/PlistBuddy -c "Set :$key $value" "$INFO_PLIST"
  else
    /usr/libexec/PlistBuddy -c "Add :$key string $value" "$INFO_PLIST"
  fi
}
set_or_add_plist_value CFBundleShortVersionString "$IOS_MARKETING_VERSION"
set_or_add_plist_value CFBundleVersion "$IOS_BUILD_NUMBER"
cp "$WORK_DIR/profile.mobileprovision" "$APP_PATH/embedded.mobileprovision"
/usr/libexec/PlistBuddy -x -c 'Print :Entitlements' "$WORK_DIR/profile.plist" > "$WORK_DIR/entitlements.plist"

# Re-sign nested code first, deepest paths first, then sign the containing app.
while IFS= read -r -d '' code_path; do
  codesign --force --sign "$SIGNING_IDENTITY" --timestamp=none "$code_path"
done < <(python3 - "$APP_PATH" <<'PY'
import pathlib
import os
import sys

app = pathlib.Path(sys.argv[1])
code = []
for path in app.rglob("*"):
    if path.is_symlink():
        continue
    if path.is_dir() and path.suffix in {".app", ".appex", ".framework", ".xpc"}:
        code.append(path)
    elif path.is_file() and path.suffix == ".dylib":
        code.append(path)
for path in sorted(code, key=lambda item: len(item.parts), reverse=True):
    sys.stdout.buffer.write(os.fsencode(path) + b"\0")
PY
)

codesign --force \
  --sign "$SIGNING_IDENTITY" \
  --entitlements "$WORK_DIR/entitlements.plist" \
  --timestamp=none \
  --generate-entitlement-der \
  "$APP_PATH"
codesign --verify --deep --strict --verbose=2 "$APP_PATH"

mkdir -p "$(dirname "$OUTPUT_IPA")"
rm -f "$OUTPUT_IPA"
ditto -c -k --sequesterRsrc --keepParent "$WORK_DIR/Payload" "$OUTPUT_IPA"

API_KEY_DIRECTORY="$HOME/.appstoreconnect/private_keys"
mkdir -p "$API_KEY_DIRECTORY"
printf '%s' "$ASC_PRIVATE_KEY" > "$API_KEY_DIRECTORY/AuthKey_${ASC_KEY_ID}.p8"
chmod 600 "$API_KEY_DIRECTORY/AuthKey_${ASC_KEY_ID}.p8"

xcrun altool --upload-app \
  --type ios \
  --file "$OUTPUT_IPA" \
  --apiKey "$ASC_KEY_ID" \
  --apiIssuer "$ASC_ISSUER_ID"
