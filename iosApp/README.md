# iOS shim

The SwiftUI host is packaged with `xtool` and embeds the shared Compose screen
whenever the Kotlin XCFramework is present. On Linux, SwiftPM can build the
standalone host shim, but Kotlin/Native cannot link final Apple binaries; it
only compiles iOS source sets to klibs there. The fallback screen says when the
shared framework has not been generated yet.

On macOS with Xcode installed, build and host the shared UI with:

```sh
./gradlew :composeApp:assembleComposeAppDebugXCFramework
xtool dev build --triple arm64-apple-ios-simulator
```

The package detects `composeApp/build/XCFrameworks/debug/ComposeApp.xcframework`
and switches from the fallback screen to `MainViewController()` in `iosMain`.
The current iOS shim uses local preview data only; a native Reticulum node and
transport are not implemented on iOS yet.
