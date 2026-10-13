import SwiftUI
import UIKit
import ComposeApp

private struct SharedComposeViewController: UIViewControllerRepresentable {
    let runtime: StarlingEmbeddedPythonRuntime
    let documents: StarlingIdentityDocuments

    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController(runtime: runtime, documents: documents)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct StarlingRootView: View {
    @State private var pythonRuntime = StarlingEmbeddedPythonRuntime()
    @State private var identityDocuments = StarlingIdentityDocuments()

    var body: some View {
        SharedComposeViewController(runtime: pythonRuntime, documents: identityDocuments)
            .ignoresSafeArea()
    }
}
