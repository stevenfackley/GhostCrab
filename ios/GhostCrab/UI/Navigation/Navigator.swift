import Observation
import SwiftUI

/// The app's navigation state: the screen at the bottom of the stack and the
/// routes pushed above it. ``RootView`` binds its `NavigationStack` to
/// ``path`` and hands screens a ``NavigateAction`` built on this.
///
/// Plain values, so the stack rules are unit-testable without SwiftUI:
/// - ``push(_:)`` for ordinary drill-down.
/// - ``showPicker()`` and ``showDashboard()`` replace the stack. Pushing
///   there stacked a second picker or dashboard, each keeping its old screen's
///   live ViewModel alive underneath.
/// - ``showManualEntry(prefillURL:)`` for pairing deep links, which never
///   stacks two Manual Entry screens.
@MainActor
@Observable
final class Navigator {

    /// Bottom of the stack, or `nil` until ``resolveRoot(_:)`` (the splash shows meanwhile).
    private(set) var root: Route?

    /// Routes pushed above ``root``, bottom first. Bound to `NavigationStack(path:)`.
    var path: [Route] = []

    /// - Parameter root: Start screen, or `nil` to resolve it later.
    init(root: Route? = nil) {
        self.root = root
    }

    /// Sets the start screen once onboarding state is known. Ignored once a
    /// root is set, so a late call cannot undo navigation the user already did.
    ///
    /// - Parameter route: `.onboarding` on a first run, else `.connectionPicker`.
    func resolveRoot(_ route: Route) {
        guard root == nil else { return }
        root = route
    }

    /// Pushes `route` on top of the stack.
    func push(_ route: Route) {
        path.append(route)
    }

    /// "Go to the gateway picker": the picker becomes the only screen. Used when
    /// onboarding finishes (first run or replayed from Settings) and by Config
    /// Editor's "Pick a gateway".
    func showPicker() {
        root = .connectionPicker
        path = []
    }

    /// "Go to the dashboard" after a connect: the dashboard sits directly on
    /// the picker, so Back and Disconnect (which pops) land on the picker, and
    /// whatever led here (scan, manual entry, onboarding) is released.
    func showDashboard() {
        root = .connectionPicker
        path = [.dashboard]
    }

    /// Shows Manual Entry pre-filled with `url` for a pairing deep link.
    /// Replaces a Manual Entry already on top (a newer link wins over a
    /// half-typed form) instead of stacking a second one; otherwise pushes.
    ///
    /// - Parameter url: Gateway URL from the link. Never a token.
    func showManualEntry(prefillURL url: URL) {
        let route = Route.manualEntry(prefillURL: url)
        if case .manualEntry? = path.last {
            path[path.count - 1] = route
        } else {
            path.append(route)
        }
    }

    /// Whether Manual Entry pre-filled with `url` is already on top.
    ///
    /// - Parameter url: Gateway URL from a pairing link.
    /// - Returns: `true` when a repeated delivery of that link has nothing left to do.
    func isShowingManualEntry(prefillURL url: URL) -> Bool {
        path.last == Route.manualEntry(prefillURL: url)
    }
}

// MARK: - Navigate action (passed down the tree)

/// Typed navigation handed to screens through the environment. Screens call
/// `navigate(.modelManager)` to push, `navigate.toPicker()` or
/// `navigate.toDashboard()` to replace the stack, without touching the path.
public struct NavigateAction: Sendable {

    private let navigator: Navigator?

    /// - Parameter navigator: The stack to drive; `nil` makes every call a no-op
    ///   (the environment default, for screens shown outside ``RootView``).
    init(_ navigator: Navigator?) {
        self.navigator = navigator
    }

    /// Pushes `route`. See ``Navigator/push(_:)``.
    @MainActor public func callAsFunction(_ route: Route) {
        navigator?.push(route)
    }

    /// Makes the gateway picker the only screen. See ``Navigator/showPicker()``.
    @MainActor public func toPicker() {
        navigator?.showPicker()
    }

    /// Shows the dashboard directly on the picker. See ``Navigator/showDashboard()``.
    @MainActor public func toDashboard() {
        navigator?.showDashboard()
    }
}

private struct NavigateKey: EnvironmentKey {
    static let defaultValue = NavigateAction(nil)
}

extension EnvironmentValues {
    public var navigate: NavigateAction {
        get { self[NavigateKey.self] }
        set { self[NavigateKey.self] = newValue }
    }
}
