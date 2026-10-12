// swift-tools-version: 6.0

import Foundation
import PackageDescription

let composeFrameworkPath = "composeApp/build/XCFrameworks/debug/ComposeApp.xcframework"
let hasComposeFramework = FileManager.default.fileExists(atPath: composeFrameworkPath)

var packageTargets: [Target] = []
var appDependencies: [Target.Dependency] = []
var appSwiftSettings: [SwiftSetting] = []

if hasComposeFramework {
    packageTargets.append(
        .binaryTarget(name: "ComposeApp", path: composeFrameworkPath)
    )
    appDependencies.append(.target(name: "ComposeApp"))
    appSwiftSettings.append(.define("STARLING_COMPOSE_AVAILABLE"))
}

packageTargets.append(
    .target(
        name: "Starling",
        dependencies: appDependencies,
        path: "iosApp/Sources/Starling",
        resources: [.copy("Resources/starling-light-preview.png"), .copy("Resources/starling-dark-preview.png")],
        swiftSettings: appSwiftSettings
    )
)

let package = Package(
    name: "Starling",
    platforms: [.iOS(.v17)],
    products: [.library(name: "Starling", targets: ["Starling"])],
    targets: packageTargets
)
