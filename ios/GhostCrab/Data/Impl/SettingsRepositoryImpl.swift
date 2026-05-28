import Foundation

/// ``SettingsStore``-backed implementation of ``SettingsRepository``.
///
/// Direct port of `SettingsRepositoryImpl.kt`. The wrapped ``SettingsStore``
/// is itself `Sendable` and serializes through `UserDefaults`, so this type
/// is a stateless `final class` marked `Sendable`.
///
/// All preferences default to `false` unless explicitly set.
public final class SettingsRepositoryImpl: SettingsRepository, Sendable {

    private let store: SettingsStore

    /// - Parameter store: Backing `UserDefaults` wrapper.
    public init(store: SettingsStore) {
        self.store = store
    }

    /// Emits the current value of the "allow cleartext to public IPs"
    /// preference on subscription, then every change.
    public func allowCleartextPublicIPs() -> AsyncStream<Bool> {
        store.observeAllowCleartextPublicIPs()
    }

    /// Persists the "allow cleartext to public IPs" preference.
    ///
    /// - Parameter enabled: `true` to allow HTTP connections to public IPs.
    public func setAllowCleartextPublicIPs(_ enabled: Bool) async {
        store.setAllowCleartextPublicIPs(enabled)
    }
}
