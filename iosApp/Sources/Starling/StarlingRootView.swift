import SwiftUI

#if STARLING_COMPOSE_AVAILABLE
import ComposeApp
import UIKit

private struct SharedComposeViewController: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
#endif

struct StarlingRootView: View {
    var body: some View {
        #if STARLING_COMPOSE_AVAILABLE
        SharedComposeViewController()
            .ignoresSafeArea()
        #else
        ComposeFrameworkMissingView()
        #endif
    }
}

private struct ComposeFrameworkMissingView: View {
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        VStack(spacing: 14) {
            Image(
                colorScheme == .dark ? "starling-dark-preview" : "starling-light-preview",
                bundle: .module
            )
            .resizable()
            .scaledToFit()
            .frame(width: 112, height: 112)
            Text("Starling iOS shim")
                .font(.title2.weight(.semibold))
            Text("The iOS host is ready. Build the ComposeApp XCFramework on macOS to load the shared chat interface here.")
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
        }
        .padding(32)
    }
}
