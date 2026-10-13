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

The unsigned IPA workflow prepares the pinned Python support framework, builds
the Compose framework, lets xtool create the IPA, then installs Python's standard
library and extension frameworks into the app bundle. It also cross-builds PyCA
cryptography wheels for iOS device and simulator using cibuildwheel and Rust. To
run it manually on a Mac with full Xcode and rustup installed:

```sh
bash packaging/ios/prepare-python-runtime.sh
./gradlew :composeApp:assembleComposeAppDebugXCFramework
xtool dev build --configuration debug --ipa --triple arm64-apple-ios
# Then run packaging/ios/package-python-ipa.sh on the generated IPA.
```

The app starts in local preview. Turn preview off in Settings to configure TCP
peers and optional per-peer IFAC credentials, or attach to an existing local RNS
shared instance. The iOS local-network permission prompt appears when connecting
to a peer on the local network. RNode hardware is not supported on iOS.
