import SwiftUI

/// `@main` entry point for the GhostCrab iOS / Mac Catalyst app.
///
/// Builds the single ``AppContainer`` and hands it to the SwiftUI hierarchy
/// via Environment. Applies the dark abyss-background theme as the default
/// surface for every scene.
@main
struct GhostCrabApp: App {

    @State private var container = AppContainer()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(\.appContainer, container)
                .preferredColorScheme(.dark)
                .ghostCrabTheme()
        }
        #if targetEnvironment(macCatalyst)
        // Mac Catalyst gets a minimum window size so the connect/scan UI
        // doesn't collapse below a usable width on the Mac mini.
        .defaultSize(width: 480, height: 720)
        #endif
    }
}
