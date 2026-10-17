// swift-tools-version: 6.0

import Foundation
import PackageDescription

let composeConfiguration = ProcessInfo.processInfo.environment["STARLING_COMPOSE_CONFIGURATION"] ?? "debug"
guard ["debug", "release"].contains(composeConfiguration) else {
    fatalError("STARLING_COMPOSE_CONFIGURATION must be either 'debug' or 'release'.")
}
let composeFrameworkPath = "composeApp/build/XCFrameworks/\(composeConfiguration)/ComposeApp.xcframework"
let pythonFrameworkPath = "iosApp/Vendor/Python.xcframework"
let packageTargets: [Target] = {
    guard FileManager.default.fileExists(atPath: composeFrameworkPath) else {
        fatalError("Build :composeApp:assembleComposeAppDebugXCFramework before building the iOS app.")
    }
    guard FileManager.default.fileExists(atPath: pythonFrameworkPath) else {
        fatalError("Run packaging/ios/prepare-python-runtime.sh before building the iOS app.")
    }
    return [
        .binaryTarget(name: "ComposeApp", path: composeFrameworkPath),
        .binaryTarget(name: "Python", path: pythonFrameworkPath),
        .target(
            name: "PythonRuntimeShim",
            dependencies: [.target(name: "Python")],
            path: "iosApp/Sources/PythonRuntimeShim",
            publicHeadersPath: "include"
        ),
        .target(
            name: "Starling",
            dependencies: [.target(name: "ComposeApp"), .target(name: "PythonRuntimeShim")],
            path: "iosApp/Sources/Starling",
            resources: [.copy("PythonModules")]
        ),
    ]
}()

let package = Package(
    name: "Starling",
    platforms: [.iOS(.v17)],
    products: [.library(name: "Starling", targets: ["Starling"])],
    targets: packageTargets
)
