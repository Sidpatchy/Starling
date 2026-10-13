import SwiftUI
import UIKit
import ComposeApp

private struct SharedComposeViewController: UIViewControllerRepresentable {
    let runtime: StarlingEmbeddedPythonRuntime

    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController(runtime: runtime)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct StarlingRootView: View {
    @State private var pythonRuntime = StarlingEmbeddedPythonRuntime()

    var body: some View {
        SharedComposeViewController(runtime: pythonRuntime)
            .ignoresSafeArea()
    }
}
