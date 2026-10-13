import Foundation
import ComposeApp
import PythonRuntimeShim

final class StarlingEmbeddedPythonRuntime: NSObject, IosPythonRuntime {
    let dataDirectory: String
    private let pythonHome: String
    private let pythonModules: String
    private let executablePath: String
    private let runtimeLock = NSRecursiveLock()
    private var didInitialize = false
    private var startupError: String?

    init() {
        let supportDirectory = FileManager.default.urls(
            for: .applicationSupportDirectory,
            in: .userDomainMask
        )[0].appendingPathComponent("Starling", isDirectory: true)
        do {
            try FileManager.default.createDirectory(
                at: supportDirectory,
                withIntermediateDirectories: true
            )
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            var protectedDirectory = supportDirectory
            try protectedDirectory.setResourceValues(values)
        } catch {
            // RNS startup will surface any later file-access failure in Settings.
        }
        dataDirectory = supportDirectory.path

        pythonHome = Bundle.main.bundleURL.appendingPathComponent("python", isDirectory: true).path
        #if targetEnvironment(simulator)
        let pythonPlatform = "ios-simulator"
        #else
        let pythonPlatform = "ios-device"
        #endif
        pythonModules = Bundle.module.resourceURL!
            .appendingPathComponent("PythonModules", isDirectory: true)
            .appendingPathComponent(pythonPlatform, isDirectory: true)
            .path
        let executableName = Bundle.main.infoDictionary?["CFBundleExecutable"] as? String ?? "Starling"
        executablePath = Bundle.main.executableURL?.path ?? Bundle.main.bundleURL.appendingPathComponent(executableName).path
        super.init()
    }

    func invoke(command: String) -> String {
        runtimeLock.lock()
        defer { runtimeLock.unlock() }
        if !didInitialize {
            startupError = StarlingPythonInitialize(pythonHome, pythonModules, executablePath)
            didInitialize = true
        }
        if let startupError {
            let encoded = Data(startupError.utf8).base64EncodedString()
            return "ERROR\t\(encoded)"
        }
        return StarlingPythonInvoke(command)
    }
}
