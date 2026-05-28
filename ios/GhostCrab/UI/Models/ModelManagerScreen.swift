import SwiftUI

/// Model picker — list all models known to the connected gateway and let the
/// user activate one. Mirrors `ModelManagerScreen.kt`.
public struct ModelManagerScreen: View {

    @State private var vm: ModelManagerViewModel
    @Environment(\.navigate) private var navigate
    @Environment(\.dismiss) private var dismiss

    public init(vm: ModelManagerViewModel) {
        self._vm = State(initialValue: vm)
    }

    public var body: some View {
        ZStack {
            DesignTokens.Color.abyss.ignoresSafeArea()
            content
        }
        .navigationTitle("Models")
        #if os(iOS)
        .toolbarBackground(DesignTokens.Color.abyssRaised, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .toolbarColorScheme(.dark, for: .navigationBar)
        #endif
        .task { await vm.load() }
    }

    // MARK: - Content

    @ViewBuilder
    private var content: some View {
        if vm.isLoading && vm.models_.isEmpty {
            ProgressView()
                .tint(DesignTokens.Color.cyanPrimary)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if vm.notSupported {
            EmptyState(
                icon: "questionmark.app.dashed",
                title: "Not supported",
                message: "This gateway version doesn't expose a /models API. Models must be configured server-side."
            )
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if vm.models_.isEmpty {
            EmptyState(
                icon: "tray",
                title: "No models configured",
                message: "Use Config Editor to add a provider.",
                action: ("Open Config Editor", { navigate(.configEditor) })
            )
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            modelsList
        }
    }

    @ViewBuilder
    private var modelsList: some View {
        List {
            if let error = vm.lastError {
                Section {
                    errorBanner(error)
                        .listRowBackground(Color.clear)
                        .listRowSeparator(.hidden)
                        .listRowInsets(EdgeInsets(
                            top: DesignTokens.Spacing.sm,
                            leading: DesignTokens.Spacing.md,
                            bottom: DesignTokens.Spacing.sm,
                            trailing: DesignTokens.Spacing.md
                        ))
                }
            }

            Section {
                ForEach(vm.models_) { model in
                    row(for: model)
                        .listRowBackground(DesignTokens.Color.abyss)
                        .listRowSeparatorTint(DesignTokens.Color.outline)
                        .contentShape(Rectangle())
                        .onTapGesture {
                            Task { await vm.setActive(model.id) }
                        }
                }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(DesignTokens.Color.abyss)
        .refreshable { await vm.load() }
    }

    // MARK: - Row

    @ViewBuilder
    private func row(for model: ModelInfo) -> some View {
        HStack(spacing: DesignTokens.Spacing.md) {
            VStack(alignment: .leading, spacing: DesignTokens.Spacing.xxs) {
                Text(model.displayName)
                    .font(AppFont.body(16))
                    .foregroundStyle(DesignTokens.Color.textPrimary)
                    .lineLimit(1)

                HStack(spacing: DesignTokens.Spacing.sm) {
                    Text(model.provider)
                        .font(AppFont.mono(12))
                        .foregroundStyle(DesignTokens.Color.textSecondary)
                    Text(model.status)
                        .font(AppFont.mono(12))
                        .foregroundStyle(statusColor(for: model.status))
                }
            }

            Spacer(minLength: 0)

            if vm.activatingId == model.id {
                ProgressView()
                    .tint(DesignTokens.Color.cyanPrimary)
                    .controlSize(.small)
            } else if model.isActive {
                Image(systemName: "checkmark")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(DesignTokens.Color.cyanPrimary)
            }
        }
        .padding(.vertical, DesignTokens.Spacing.xs)
        .opacity(vm.activatingId == nil || vm.activatingId == model.id ? 1.0 : 0.6)
    }

    private func statusColor(for status: String) -> Color {
        switch status {
        case "auth-error":
            return DesignTokens.Color.crimsonError
        case "loading":
            return DesignTokens.Color.amberWarn
        default:
            return DesignTokens.Color.textSecondary
        }
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
}
