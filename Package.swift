// swift-tools-version: 6.0

import Foundation
import PackageDescription

let composeFrameworkPath = "composeApp/build/XCFrameworks/debug/ComposeApp.xcframework"
let packageTargets: [Target] = {
    guard FileManager.default.fileExists(atPath: composeFrameworkPath) else {
        fatalError("Build :composeApp:assembleComposeAppDebugXCFramework before building the iOS app.")
    }
    return [
        .binaryTarget(name: "ComposeApp", path: composeFrameworkPath),
        .target(
            name: "Starling",
            dependencies: [.target(name: "ComposeApp")],
            path: "iosApp/Sources/Starling"
        ),
    ]
}()

let package = Package(
    name: "Starling",
    platforms: [.iOS(.v17)],
    products: [.library(name: "Starling", targets: ["Starling"])],
    targets: packageTargets
)
