# iOS host

The iOS application uses the shared Compose UI, hosted by a minimal SwiftUI app
entry point. It requires the Compose XCFramework and deliberately has no
separate SwiftUI fallback UI. Kotlin/Native can compile iOS source sets to
klibs on Linux, but the final Apple framework and IPA must be built on macOS.

On macOS with Xcode installed, build and host the shared UI with:

```sh
./gradlew :composeApp:assembleComposeAppDebugXCFramework
xtool dev build --triple arm64-apple-ios-simulator
```

The Swift package links `composeApp/build/XCFrameworks/debug/ComposeApp.xcframework`
and hosts `MainViewController()` from `iosMain`. The same Compose UI is used on
Android, desktop, and iOS. iOS currently uses local preview data only; a native
Reticulum node and transport are not implemented on iOS yet.
