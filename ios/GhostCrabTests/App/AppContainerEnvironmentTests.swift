import SwiftUI
import Testing
@testable import GhostCrab

/// Pins the `\.appContainer` environment contract.
///
/// The old `AppContainerKey.defaultValue` was `AppContainer()`, so a view read
/// without injection silently built a second, disconnected container. Now the
/// key defaults to `nil`, the read side preconditions, and injection goes
/// through the separate optional `injectedAppContainer`.
///
/// The trap itself is not exercised: Swift Testing exit tests are unavailable
/// on iOS, and a `preconditionFailure` would take down the whole test host.
/// These tests pin the conditions around it instead: a fresh environment
/// reaches the precondition rather than a fallback container, injection never
/// runs the trapping getter, and an injected container comes back unchanged.
@MainActor
@Suite("AppContainer environment injection")
struct AppContainerEnvironmentTests {

    @Test func freshEnvironmentHasNoContainerToFallBackOn() {
        let environment = EnvironmentValues()
        #expect(environment.injectedAppContainer == nil)
    }

    @Test func readSideIsNotWritable() {
        // `.environment(_:_:)` writes through a WritableKeyPath, and a key-path
        // write to a computed property runs its getter first. A settable
        // `appContainer` would therefore trap on the parent environment at
        // launch, which is the crash the separate injection key fixes.
        let readSide: AnyKeyPath = \EnvironmentValues.appContainer
        #expect(!(readSide is WritableKeyPath<EnvironmentValues, AppContainer>))
    }

    @Test func keyPathInjectionReturnsTheSameContainer() {
        let container = AppContainer()
        var environment = EnvironmentValues()
        // Same write path SwiftUI's `.environment(\.injectedAppContainer, container)` takes.
        let injection: WritableKeyPath<EnvironmentValues, AppContainer?> = \.injectedAppContainer
        environment[keyPath: injection] = container

        #expect(environment.injectedAppContainer === container)
        #expect(environment.appContainer === container)
    }
}
