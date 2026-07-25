import SwiftUI
import ComposeApp

/// Bridges the Kotlin Compose UIViewController into SwiftUI.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
        // No dynamic updates needed; Compose manages its own state.
    }
}
