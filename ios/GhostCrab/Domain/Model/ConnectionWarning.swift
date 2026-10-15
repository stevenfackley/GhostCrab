import Foundation

/// A problem that did not fail the connection, or context for a failed one (ADR-0007). Mirrors Android's
/// `ConnectionWarning`.
///
/// Additive (v1.1) — see docs/adr/0005-additive-repository-methods.md
public enum ConnectionWarning: Sendable, Equatable {
    /// This device's stored identity was unreadable and replaced: every gateway it was paired with shows a new
    /// pairing request. Reported until the gateway accepts the new identity.
    case deviceIdentityRegenerated
    /// The device token `hello-ok` issued could not be stored in the Keychain. This session works; the next connect
    /// falls back to the shared token or asks for pairing again.
    case deviceTokenNotSaved
}
