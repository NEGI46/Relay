import SwiftUI
import ComposeApp

/// Relay iOS host — wraps the shared Kotlin Compose UI.
/// Domain logic lives in the Kotlin `shared` and `composeApp` modules.
/// This file provides only the @main lifecycle and UIViewController hosting.
@main
struct RelayIOSApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeView()
                .ignoresSafeArea(.all)
        }
    }
}
