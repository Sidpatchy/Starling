import SwiftUI
import UIKit
import ComposeApp

private struct SharedComposeViewController: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct StarlingRootView: View {
    var body: some View {
        SharedComposeViewController()
            .ignoresSafeArea()
    }
}
