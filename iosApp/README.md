# iOS host

The iOS application uses the shared Compose UI, hosted by a minimal SwiftUI app
entry point. It requires the Compose XCFramework and deliberately has no
separate SwiftUI fallback UI. Kotlin/Native can compile iOS source sets to
klibs on Linux, but the final Apple framework and IPA must be built on macOS.

On macOS with Xcode installed, prepare the embedded Python runtime, then build
and host the shared UI with:

```sh
bash packaging/ios/prepare-python-runtime.sh
./gradlew :composeApp:assembleComposeAppDebugXCFramework
xtool dev build --triple arm64-apple-ios-simulator
app_path="$(find .build -type d -name Starling.app -print -quit)"
bash packaging/ios/install-python-runtime-into-app.sh "$app_path"
```

The Swift package links the shared `ComposeApp` framework and embeds CPython 3.13
with the same RNS, RRC, and LXMF bridge used by the other clients. The iOS runtime
uses PyCA `cryptography`, built as device and simulator iOS wheels and packaged as
frameworks. RNS identities and config live in app-private Application Support;
chat preferences and history use the shared persisted-state codec. iOS does not
provide an Android-style background service, so live networking is foreground-only.

The unsigned release IPA workflow prepares the pinned Python support framework,
builds the release Compose framework, asks xtool to generate an Xcode project,
then uses Xcode to build against the installed iOS SDK before embedding Python's
standard library and extension frameworks. The Xcode build is required for
App Store Connect's SDK and platform metadata. The workflow also cross-builds
PyCA cryptography wheels for iOS device and simulator using cibuildwheel and
Rust. To run it manually on a Mac with Xcode 26 or later and rustup installed:

```sh
bash packaging/ios/prepare-python-runtime.sh
./gradlew :composeApp:assembleComposeAppReleaseXCFramework
STARLING_COMPOSE_CONFIGURATION=release xtool dev generate-xcode-project
STARLING_COMPOSE_CONFIGURATION=release xcodebuild \
  -project xtool/.xtool-tmp/Starling.xcodeproj \
  -scheme Starling-App \
  -configuration Release \
  -sdk iphoneos \
  -destination 'generic/platform=iOS' \
  -derivedDataPath .build/StarlingDerivedData \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO build
app_path="$(find .build/StarlingDerivedData/Build/Products/Release-iphoneos -maxdepth 1 -type d -name '*.app' -print -quit)"
bash packaging/ios/package-python-app.sh "$app_path" artifacts/Starling-ios-unsigned.ipa
```

GitHub Actions can also sign this IPA and upload it to App Store Connect. The
signed IPA is sent directly to Apple and is not published as a GitHub artifact
or release asset. See [the App Store Connect setup guide](../docs/IOS_APP_STORE_CONNECT.md).

The app starts in local preview. Turn preview off in Settings to configure TCP
peers and optional per-peer IFAC credentials, or attach to an existing local RNS
shared instance. The iOS local-network permission prompt appears when connecting
to a peer on the local network. RNode hardware is not supported on iOS.
