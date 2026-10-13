import Foundation
import UIKit
import UniformTypeIdentifiers
import ComposeApp

@MainActor
final class StarlingIdentityDocuments: NSObject, @preconcurrency IosIdentityDocuments, UIDocumentPickerDelegate {
    private var importCompletion: ((KotlinByteArray?, String?) -> KotlinUnit)?
    private var exportCompletion: ((String?, String?) -> KotlinUnit)?
    private var exportDirectory: URL?
    private weak var picker: UIDocumentPickerViewController?

    func importIdentity(completion: @escaping (KotlinByteArray?, String?) -> KotlinUnit) {
        importCompletion = completion
        present(UIDocumentPickerViewController(forOpeningContentTypes: [.item], asCopy: false))
    }

    func exportIdentity(bytes: KotlinByteArray, suggestedName: String, completion: @escaping (String?, String?) -> KotlinUnit) {
        exportCompletion = completion
        do {
            let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
            exportDirectory = directory
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let file = directory.appendingPathComponent(suggestedName)
            var data = Data(count: Int(bytes.size))
            defer { data.resetBytes(in: 0..<data.count) }
            for index in 0..<Int(bytes.size) { data[index] = UInt8(bitPattern: bytes.get(index: Int32(index))) }
            try data.write(to: file, options: [.atomic, .completeFileProtection])
            present(UIDocumentPickerViewController(forExporting: [file], asCopy: true))
        } catch {
            finish(error: "Could not prepare the identity backup.")
        }
    }

    nonisolated func cancel() {
        DispatchQueue.main.async {
            self.picker?.dismiss(animated: true)
            self.finish()
        }
    }

    private func present(_ picker: UIDocumentPickerViewController) {
        guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first(where: { $0.activationState == .foregroundActive }),
              var presenter = scene.windows.first(where: { $0.isKeyWindow })?.rootViewController else {
            finish(error: "Could not open the identity file dialog.")
            return
        }
        while let presented = presenter.presentedViewController { presenter = presented }
        picker.delegate = self
        picker.allowsMultipleSelection = false
        self.picker = picker
        presenter.present(picker, animated: true)
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let url = urls.first else { finish(); return }
        if exportCompletion != nil { finish(exported: true); return }
        let access = url.startAccessingSecurityScopedResource()
        defer { if access { url.stopAccessingSecurityScopedResource() } }
        do {
            let input = try FileHandle(forReadingFrom: url)
            defer { try? input.close() }
            var data = Data()
            while data.count < 65 {
                guard let chunk = try input.read(upToCount: 65 - data.count), !chunk.isEmpty else { break }
                data.append(chunk)
            }
            let bytes = KotlinByteArray(size: Int32(data.count))
            for index in 0..<data.count { bytes.set(index: Int32(index), value: Int8(bitPattern: data[index])) }
            data.resetBytes(in: 0..<data.count)
            finish(imported: bytes)
        } catch {
            finish(error: "Could not read the identity file.")
        }
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) { finish() }

    private func finish(imported: KotlinByteArray? = nil, exported: Bool = false, error: String? = nil) {
        if let directory = exportDirectory { try? FileManager.default.removeItem(at: directory) }
        exportDirectory = nil
        let importCallback = importCompletion
        let exportCallback = exportCompletion
        importCompletion = nil
        exportCompletion = nil
        picker = nil
        _ = importCallback?(imported, error)
        _ = exportCallback?(exported ? "saved" : nil, error)
    }
}
