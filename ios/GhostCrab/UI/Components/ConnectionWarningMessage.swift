import Foundation

extension ConnectionWarning {
    /// Plain-text explanation for the approval card and the dashboard (shown amber, as every warning). Names the
    /// case, never a secret.
    public var userMessage: String {
        switch self {
        case .deviceIdentityRegenerated:
            return "This device has a new identity; approve it again. Its previous key could not be read from this "
                + "device's Keychain, so every gateway sees a new device (deviceIdentityRegenerated)."
        case .deviceTokenNotSaved:
            return "The gateway's device token could not be saved in this device's Keychain. This session works; "
                + "later connects need the gateway token until a device token is saved (deviceTokenNotSaved)."
        }
    }
}
