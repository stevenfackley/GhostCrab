import SwiftUI

/// Connected-session dashboard.
///
/// Mirrors `DashboardScreen.kt`. Vertical card stack on iPhone; 2-column grid
/// on iPad / Mac Catalyst via `ViewThatFits` — the regular-width layout falls
/// back to the iPhone stack on narrow windows automatically, so we don't have
/// to read `horizontalSizeClass` ourselves.
public struct DashboardScreen: View {

    @State private var vm: DashboardViewModel
    @Environment(\.appContainer) private var container
    @Environment(\.navigate) private var navigate
    @Environment(\.dismiss) private var dismiss

    public init(vm: DashboardViewModel) {
        self._vm = State(initialValue: vm)
    }

    public var body: some View {
        ZStack {
            DesignTokens.Color.abyss.ignoresSafeArea()

            VStack(spacing: 0) {
                ConnectionStatusBar(
                    connection: vm.connection,
                    onDisconnect: { Task { await disconnectAndPop() } }
                )

                ScrollView {
                    content
                        .padding(.horizontal, DesignTokens.Spacing.md)
                        .padding(.vertical, DesignTokens.Spacing.md)
                        .frame(maxWidth: 720)
                        .frame(maxWidth: .infinity)
                }
                .refreshable { await vm.refresh() }
            }
        }
        .navigationTitle("Dashboard")
        #if os(iOS)
        .toolbarBackground(DesignTokens.Color.abyssRaised, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .toolbarColorScheme(.dark, for: .navigationBar)
        #endif
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    Task { await disconnectAndPop() }
                } label: {
                    Image(systemName: "power")
                        .foregroundStyle(DesignTokens.Color.crimsonError)
                }
                .accessibilityLabel("Disconnect")
            }
        }
        .task { vm.onAppear() }
    }

    // MARK: - Content

    @ViewBuilder
    private var content: some View {
        VStack(alignment: .leading, spacing: DesignTokens.Spacing.md) {

            // Security banners come before the body content — same order as Android.
            if case .connected(_, _, _, let authReq, let isHttps, _, _, _) = vm.connection {
                if !isHttps {
                    HttpSecurityBanner(httpWithoutAuth: authReq == .none)
                }
            }

            if let error = vm.lastError {
                errorBanner(error)
            }

            gatewayCard

            activeModelCard

            // Tiles section — iPad/Mac Catalyst gets a 2-column grid, iPhone gets
            // a vertical stack. `ViewThatFits` picks the widest layout that fits
            // the available width, which is the idiomatic SwiftUI way to handle
            // this without explicit size-class reads.
            ViewThatFits(in: .horizontal) {
                tileGrid
                tileStack
            }
        }
    }

    // MARK: - Cards

    @ViewBuilder
    private var gatewayCard: some View {
        GlassSurface {
            VStack(alignment: .leading, spacing: DesignTokens.Spacing.sm) {
                Text("Gateway")
                    .font(AppFont.bodyBold(15))
                    .foregroundStyle(DesignTokens.Color.textSecondary)

                if case .connected(
                    let url,
                    let displayName,
                    let version,
                    _,
                    let isHttps,
                    _,
                    _,
                    _
                ) = vm.connection {
                    Text(displayName)
                        .font(AppFont.bodyBold(20))
                        .foregroundStyle(DesignTokens.Color.textPrimary)

                    Text(url)
                        .font(AppFont.mono(13))
                        .foregroundStyle(DesignTokens.Color.textSecondary)
                        .lineLimit(1)
                        .truncationMode(.middle)

                    HStack(spacing: DesignTokens.Spacing.sm) {
                        Image(systemName: isHttps ? "lock.fill" : "lock.open.fill")
                            .foregroundStyle(
                                isHttps
                                    ? DesignTokens.Color.cyanPrimary
                                    : DesignTokens.Color.amberWarn
                            )
                            .font(.system(size: 12))
                        Text(isHttps ? "HTTPS" : "HTTP")
                            .font(AppFont.body(12))
                            .foregroundStyle(DesignTokens.Color.textSecondary)
                        Text("v\(version)")
                            .font(AppFont.mono(12))
                            .foregroundStyle(DesignTokens.Color.textSecondary)
                    }
                } else {
                    Text("Not connected")
                        .font(AppFont.body(14))
                        .foregroundStyle(DesignTokens.Color.textSecondary)
                }
            }
            .padding(DesignTokens.Spacing.md)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    @ViewBuilder
    private var activeModelCard: some View {
        Button {
            navigate(.modelManager)
        } label: {
            GlassSurface {
                VStack(alignment: .leading, spacing: DesignTokens.Spacing.sm) {
                    HStack {
                        Text("Active model")
                            .font(AppFont.bodyBold(15))
                            .foregroundStyle(DesignTokens.Color.textSecondary)
                        Spacer()
                        Image(systemName: "chevron.right")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(DesignTokens.Color.textSecondary)
                    }

                    if let model = vm.activeModel {
                        Text(model.displayName)
                            .font(AppFont.bodyBold(18))
                            .foregroundStyle(DesignTokens.Color.textPrimary)
                            .lineLimit(1)

                        HStack(spacing: DesignTokens.Spacing.sm) {
                            Text(model.provider)
                                .font(AppFont.mono(12))
                                .foregroundStyle(DesignTokens.Color.textSecondary)
                            stateBadge(model.status)
                        }
                    } else if vm.modelCount == 0 {
                        Text("No models configured")
                            .font(AppFont.body(14))
                            .foregroundStyle(DesignTokens.Color.textSecondary)
                    } else {
                        Text("No active model selected")
                            .font(AppFont.body(14))
                            .foregroundStyle(DesignTokens.Color.amberWarn)
                    }
                }
                .padding(DesignTokens.Spacing.md)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder
    private func stateBadge(_ status: String) -> some View {
        let (bg, fg): (Color, Color) = {
            switch status {
            case "auth-error":
                return (DesignTokens.Color.crimsonError, DesignTokens.Color.abyss)
            case "loading":
                return (DesignTokens.Color.amberWarn, DesignTokens.Color.abyss)
            default:
                return (DesignTokens.Color.glass, DesignTokens.Color.textSecondary)
            }
        }()
        Text(status)
            .font(AppFont.mono(11))
            .foregroundStyle(fg)
            .padding(.horizontal, DesignTokens.Spacing.sm)
            .padding(.vertical, DesignTokens.Spacing.xxs)
            .background(DesignTokens.Shape.extraSmall.fill(bg))
    }

    // MARK: - Tiles

    private var tiles: [DashboardTile] {
        var entries: [DashboardTile] = []
        if vm.aiAvailable {
            entries.append(
                .init(
                    title: "AI",
                    subtitle: "Ask for a recommendation",
                    icon: "sparkles",
                    route: .aiRecommendation
                )
            )
        }
        entries.append(
            .init(
                title: "Config",
                subtitle: "Edit gateway settings",
                icon: "doc.text",
                route: .configEditor
            )
        )
        entries.append(
            .init(
                title: "Skills",
                subtitle: "Installed skills",
                icon: "puzzlepiece.extension",
                route: .installedSkills
            )
        )
        entries.append(
            .init(
                title: "Settings",
                subtitle: "App preferences",
                icon: "gearshape",
                route: .settings
            )
        )
        return entries
    }

    @ViewBuilder
    private var tileGrid: some View {
        // Constrain min width so SwiftUI only picks this layout when there's
        // actually room for two columns — typical iPad portrait/landscape.
        Grid(
            horizontalSpacing: DesignTokens.Spacing.md,
            verticalSpacing: DesignTokens.Spacing.md
        ) {
            ForEach(tilePairs, id: \.0.id) { pair in
                GridRow {
                    tileView(pair.0)
                    if let right = pair.1 {
                        tileView(right)
                    } else {
                        Color.clear
                    }
                }
            }
        }
        .frame(minWidth: 560)
    }

    @ViewBuilder
    private var tileStack: some View {
        VStack(spacing: DesignTokens.Spacing.md) {
            ForEach(tiles) { tile in
                tileView(tile)
            }
        }
    }

    private var tilePairs: [(DashboardTile, DashboardTile?)] {
        let all = tiles
        var pairs: [(DashboardTile, DashboardTile?)] = []
        var index = 0
        while index < all.count {
            let left = all[index]
            let right = (index + 1 < all.count) ? all[index + 1] : nil
            pairs.append((left, right))
            index += 2
        }
        return pairs
    }

    @ViewBuilder
    private func tileView(_ tile: DashboardTile) -> some View {
        Button {
            navigate(tile.route)
        } label: {
            GlassSurface {
                HStack(spacing: DesignTokens.Spacing.md) {
                    Image(systemName: tile.icon)
                        .font(.system(size: 22, weight: .light))
                        .foregroundStyle(DesignTokens.Color.cyanPrimary)
                        .frame(width: 32)
                    VStack(alignment: .leading, spacing: DesignTokens.Spacing.xxs) {
                        Text(tile.title)
                            .font(AppFont.bodyBold(16))
                            .foregroundStyle(DesignTokens.Color.textPrimary)
                        Text(tile.subtitle)
                            .font(AppFont.body(13))
                            .foregroundStyle(DesignTokens.Color.textSecondary)
                            .lineLimit(1)
                    }
                    Spacer(minLength: 0)
                    Image(systemName: "chevron.right")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(DesignTokens.Color.textSecondary)
                }
                .padding(DesignTokens.Spacing.md)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .buttonStyle(.plain)
    }

    // MARK: - Error banner

    @ViewBuilder
    private func errorBanner(_ message: String) -> some View {
        HStack(alignment: .top, spacing: DesignTokens.Spacing.sm) {
            Image(systemName: "exclamationmark.octagon.fill")
                .foregroundStyle(DesignTokens.Color.crimsonError)
                .font(.system(size: 16))
            Text(message)
                .font(AppFont.mono(12))
                .foregroundStyle(DesignTokens.Color.textPrimary)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, DesignTokens.Spacing.md)
        .padding(.vertical, DesignTokens.Spacing.sm)
        .background(
            DesignTokens.Shape.small
                .fill(DesignTokens.Color.crimsonError.opacity(0.12))
        )
        .overlay(
            DesignTokens.Shape.small
                .strokeBorder(DesignTokens.Color.crimsonError, lineWidth: 1)
        )
    }

    // MARK: - Actions

    private func disconnectAndPop() async {
        await vm.disconnect()
        dismiss()
    }
}

// MARK: - Tile descriptor

private struct DashboardTile: Identifiable, Hashable {
    let id = UUID()
    let title: String
    let subtitle: String
    let icon: String
    let route: Route
}
