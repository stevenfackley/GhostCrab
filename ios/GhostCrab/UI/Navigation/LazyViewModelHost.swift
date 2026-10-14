import SwiftUI

/// Builds a screen's ViewModel exactly once per destination identity.
///
/// `navigationDestination(for:)` re-runs its closure on every parent body
/// pass. Calling `container.makeXVM()` inline therefore built a throwaway VM
/// on every re-render and ran its init side effects (stream subscriptions,
/// hydration tasks), even though the screen's `@State(initialValue:)` kept
/// only the first instance. This host keeps the VM in `@State` and creates it
/// lazily in `.onAppear`, the same pattern `QrScanScreen`,
/// `AIRecommendationScreen` and `SettingsScreen` use inline.
struct LazyViewModelHost<ViewModel: AnyObject, Content: View>: View {

    private let make: @MainActor () -> ViewModel
    private let content: (ViewModel) -> Content

    @State private var vm: ViewModel?

    /// - Parameters:
    ///   - make: Factory called once, on first appearance. Usually a `container.makeXVM()` call.
    ///   - content: Builds the screen from the VM.
    init(
        make: @escaping @MainActor () -> ViewModel,
        @ViewBuilder content: @escaping (ViewModel) -> Content
    ) {
        self.make = make
        self.content = content
    }

    var body: some View {
        ZStack {
            if let vm {
                content(vm)
            } else {
                DesignTokens.Color.abyss.ignoresSafeArea()
            }
        }
        .onAppear {
            if vm == nil { vm = make() }
        }
    }
}
