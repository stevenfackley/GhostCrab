import SwiftUI

/// Persistent header strip showing the current `GatewayConnection` state.
///
/// Mirrors Android's `ConnectionStatusBar`. Color and icon vary by state to make
/// connection health glanceable. Tap target on the right surfaces a Disconnect
/// action when connected.
public struct ConnectionStatusBar: View {

    private let connection: GatewayConnection
    private let onDisconnect: (() -> Void)?

    public init(
        connection: GatewayConnection,
        onDisconnect: (() -> Void)? = nil
    ) {
        self.connection = connection
        self.onDisconnect = onDisconnect
    }

    public var body: some View {
        HStack(spacing: DesignTokens.Spacing.sm) {
            statusDot
            Text(statusLabel)
                .font(AppFont.body(13))
                .foregroundStyle(DesignTokens.Color.textPrimary)
                .lineLimit(1)

            if case .connecting = connection {
                ProgressView()
                    .progressViewStyle(.circular)
                    .tint(DesignTokens.Color.cyanPulse)
                    .controlSize(.small)
            }

            Spacer(minLength: DesignTokens.Spacing.sm)

            if case .connected = connection, let onDisconnect {
                Button("Disconnect", action: onDisconnect)
                    .font(AppFont.body(13))
                    .foregroundStyle(DesignTokens.Color.cyanPrimary)
            }
        }
        .padding(.horizontal, DesignTokens.Spacing.md)
        .padding(.vertical, DesignTokens.Spacing.sm)
        .background(DesignTokens.Color.abyssRaised)
        .overlay(alignment: .bottom) {
            Rectangle()
                .fill(DesignTokens.Color.outline)
                .frame(height: 0.5)
        }
    }

    // MARK: - State styling

    @ViewBuilder
    private var statusDot: some View {
        Circle()
            .fill(statusColor)
            .frame(width: 8, height: 8)
            .overlay(
                Circle()
                    .stroke(statusColor.opacity(0.4), lineWidth: 4)
                    .scaleEffect(connection.isActive ? 1.0 : 1.0)
            )
    }

    private var statusColor: Color {
        switch connection {
        case .disconnected: return DesignTokens.Color.textDisabled
        case .connecting:   return DesignTokens.Color.cyanPulse
        case .connected:    return DesignTokens.Color.cyanPrimary
        case .error:        return DesignTokens.Color.crimsonError
        }
    }

    private var statusLabel: String {
        switch connection {
        case .disconnected:               return "Disconnected"
        case .connecting(let url):        return "Connecting to \(url)…"
        case .connected(let url, _, _, _, _, _, _, _):
            return url
        case .error(let url, _):          return "Error connecting to \(url)"
        }
    }
}

private extension GatewayConnection {
    var isActive: Bool {
        if case .connected = self { return true } else { return false }
    }
}
