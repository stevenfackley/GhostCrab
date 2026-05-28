import SwiftUI

/// Installed Skills screen — list/install/uninstall gateway-installed skills.
///
/// Direct port of `InstalledSkillsScreen.kt`. Each row carries a trailing
/// `Menu` with an Uninstall action (gated by confirmation). In-flight installs
/// render as inline progress rows; failed installs render as inline error rows
/// with a Retry button when the underlying error is retryable.
public struct InstalledSkillsScreen: View {

    @State private var vm: InstalledSkillsViewModel
    @State private var showInstallSheet = false
    @Environment(\.navigate) private var navigate
    @Environment(\.dismiss) private var dismiss

    public init(viewModel: InstalledSkillsViewModel) {
        _vm = State(initialValue: viewModel)
    }

    public var body: some View {
        ZStack {
            DesignTokens.Color.abyss.ignoresSafeArea()
            content
        }
        .navigationTitle("Installed Skills")
        #if os(iOS)
        .toolbarBackground(DesignTokens.Color.abyssRaised, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .toolbarColorScheme(.dark, for: .navigationBar)
        #endif
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    showInstallSheet = true
                } label: {
                    Label("Install skill", systemImage: "plus")
                }
                .tint(DesignTokens.Color.cyanPrimary)
            }
        }
        .alert(
            "Uninstall \(vm.pendingUninstallSlug ?? "")?",
            isPresented: uninstallAlertBinding,
            presenting: vm.pendingUninstallSlug
        ) { _ in
            Button("Uninstall", role: .destructive) { vm.confirmUninstall() }
            Button("Cancel", role: .cancel) { vm.cancelUninstall() }
        } message: { _ in
            Text("The skill will be removed from the gateway. You can reinstall it later.")
        }
        .alert(
            "Couldn't complete operation",
            isPresented: errorAlertBinding,
            presenting: vm.lastError
        ) { _ in
            Button("OK", role: .cancel) { vm.lastError = nil }
        } message: { message in
            Text(message)
        }
        .sheet(isPresented: $showInstallSheet) {
            InstallSkillSheet { slug, version in
                vm.install(slug: slug, version: version)
                showInstallSheet = false
            } onCancel: {
                showInstallSheet = false
            }
        }
        .task {
            vm.start()
            await waitUntilCancelled()
            vm.stop()
        }
        .refreshable {
            await vm.refresh()
        }
    }

    // MARK: - Body content

    @ViewBuilder
    private var content: some View {
        if vm.isFirstLoad && vm.skills.isEmpty && vm.installProgress.isEmpty && vm.installErrors.isEmpty {
            ProgressView()
                .tint(DesignTokens.Color.cyanPrimary)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if vm.skills.isEmpty && vm.installProgress.isEmpty && vm.installErrors.isEmpty {
            EmptyState(
                icon: "puzzlepiece.extension",
                title: "No skills installed",
                message: "Skills extend the gateway with new features. Install the AI Recommendation skill to enable suggested config patches.",
                action: ("Install AI Recommendation skill", {
                    vm.install(slug: aiRecommendationSkillSlug, version: nil)
                })
            )
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            skillsList
        }
    }

    private var skillsList: some View {
        List {
            // Live skills.
            ForEach(vm.skills) { skill in
                skillRow(skill)
                    .listRowBackground(DesignTokens.Color.glass)
                    .listRowSeparatorTint(DesignTokens.Color.outline)
            }

            // In-flight installs (slugs not yet present in `skills`).
            ForEach(installingSlugs, id: \.self) { slug in
                if let progress = vm.installProgress[slug] {
                    installProgressRow(slug: slug, progress: progress)
                        .listRowBackground(DesignTokens.Color.glass)
                        .listRowSeparatorTint(DesignTokens.Color.outline)
                }
            }

            // Terminal install errors.
            ForEach(errorSlugs, id: \.self) { slug in
                if let error = vm.installErrors[slug] {
                    installErrorRow(slug: slug, error: error)
                        .listRowBackground(DesignTokens.Color.glass)
                        .listRowSeparatorTint(DesignTokens.Color.outline)
                }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
    }

    // MARK: - Rows

    private func skillRow(_ skill: InstalledSkill) -> some View {
        HStack(alignment: .center, spacing: DesignTokens.Spacing.sm) {
            VStack(alignment: .leading, spacing: DesignTokens.Spacing.xxs) {
                Text(skill.slug)
                    .font(AppFont.mono(13))
                    .foregroundStyle(DesignTokens.Color.textPrimary)

                HStack(spacing: DesignTokens.Spacing.xs) {
                    Text("v\(skill.installedVersion)")
                        .font(AppFont.mono(11))
                        .foregroundStyle(DesignTokens.Color.cyanPulse)
                    Text("·")
                        .font(AppFont.body(11))
                        .foregroundStyle(DesignTokens.Color.textSecondary)
                    Text(sourceLabel(skill.source))
                        .font(AppFont.body(11))
                        .foregroundStyle(DesignTokens.Color.textSecondary)
                }
            }

            Spacer(minLength: 0)

            if vm.uninstallingSlug == skill.slug {
                ProgressView()
                    .tint(DesignTokens.Color.crimsonError)
                    .controlSize(.small)
            } else {
                Menu {
                    Button(role: .destructive) {
                        vm.uninstall(slug: skill.slug)
                    } label: {
                        Label("Uninstall", systemImage: "trash")
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                        .foregroundStyle(DesignTokens.Color.textSecondary)
                        .font(.system(size: 18, weight: .regular))
                }
                .menuStyle(.borderlessButton)
            }
        }
        .padding(.vertical, DesignTokens.Spacing.xs)
    }

    private func installProgressRow(slug: String, progress: SkillInstallProgress) -> some View {
        HStack(alignment: .center, spacing: DesignTokens.Spacing.sm) {
            ProgressView(value: progressFraction(progress))
                .tint(DesignTokens.Color.cyanPrimary)
                .frame(width: 60)

            VStack(alignment: .leading, spacing: DesignTokens.Spacing.xxs) {
                Text(slug)
                    .font(AppFont.mono(13))
                    .foregroundStyle(DesignTokens.Color.textPrimary)
                Text(progressLabel(progress))
                    .font(AppFont.body(11))
                    .foregroundStyle(DesignTokens.Color.cyanPulse)
            }

            Spacer(minLength: 0)
        }
        .padding(.vertical, DesignTokens.Spacing.xs)
    }

    private func installErrorRow(slug: String, error: SkillInstallError) -> some View {
        HStack(alignment: .top, spacing: DesignTokens.Spacing.sm) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(DesignTokens.Color.crimsonError)

            VStack(alignment: .leading, spacing: DesignTokens.Spacing.xxs) {
                Text(slug)
                    .font(AppFont.mono(13))
                    .foregroundStyle(DesignTokens.Color.textPrimary)
                Text(installErrorLabel(error))
                    .font(AppFont.body(11))
                    .foregroundStyle(DesignTokens.Color.crimsonError)
            }

            Spacer(minLength: 0)

            if error.isRetryable {
                Button("Retry") { vm.retryInstall(slug: slug) }
                    .font(AppFont.bodyMedium(13))
                    .foregroundStyle(DesignTokens.Color.cyanPrimary)
                    .buttonStyle(.plain)
            }
            Button {
                vm.dismissInstallError(slug: slug)
            } label: {
                Image(systemName: "xmark")
                    .foregroundStyle(DesignTokens.Color.textSecondary)
                    .font(.system(size: 12, weight: .medium))
            }
            .buttonStyle(.plain)
        }
        .padding(.vertical, DesignTokens.Spacing.xs)
    }

    // MARK: - Derived collections

    private var installingSlugs: [String] {
        vm.installProgress.keys
            .filter { slug in !vm.skills.contains(where: { $0.slug == slug }) }
            .sorted()
    }

    private var errorSlugs: [String] {
        vm.installErrors.keys.sorted()
    }

    // MARK: - Alert bindings

    private var uninstallAlertBinding: Binding<Bool> {
        Binding(
            get: { vm.pendingUninstallSlug != nil },
            set: { isShown in if !isShown { vm.cancelUninstall() } }
        )
    }

    private var errorAlertBinding: Binding<Bool> {
        Binding(
            get: { vm.lastError != nil },
            set: { isShown in if !isShown { vm.lastError = nil } }
        )
    }

    // MARK: - Labels

    private func sourceLabel(_ source: SkillSource) -> String {
        switch source {
        case .clawHub: return "ClawHub"
        case .local: return "Local"
        case .unknown: return "Unknown"
        }
    }

    private func progressLabel(_ progress: SkillInstallProgress) -> String {
        switch progress {
        case .idle: return "Idle"
        case .connecting(let target): return "Connecting to \(target)…"
        case .downloading(let pct): return pct.map { "Downloading \($0)%" } ?? "Downloading…"
        case .verifying(let prefix): return "Verifying \(prefix)…"
        case .applying(let step): return "Applying: \(step)"
        case .succeeded: return "Installed"
        case .failed: return "Failed"
        }
    }

    private func progressFraction(_ progress: SkillInstallProgress) -> Double {
        switch progress {
        case .idle: return 0
        case .connecting: return 0.1
        case .downloading(let pct): return Double(pct ?? 35) / 100.0
        case .verifying: return 0.7
        case .applying: return 0.85
        case .succeeded: return 1.0
        case .failed: return 1.0
        }
    }

    private func installErrorLabel(_ error: SkillInstallError) -> String {
        switch error {
        case .unauthorized(let scope): return "Unauthorized (missing scope: \(scope))"
        case .notFound(let slug): return "Skill not found: \(slug)"
        case .dependencyConflict(let conflicts): return "Dependency conflict: \(conflicts.joined(separator: ", "))"
        case .network(let cause): return "Network error: \(cause)"
        case .protocol(let code, let message): return "Protocol error \(code): \(message)"
        case .verificationFailed(let expected, let actual): return "Verification failed: expected \(expected), got \(actual)"
        case .unknown(let cause): return "Unknown error: \(cause)"
        }
    }

    // MARK: - Lifecycle helper

    private func waitUntilCancelled() async {
        do { try await Task.sleep(nanoseconds: .max) } catch {}
    }
}

// MARK: - Install skill sheet

/// Modal form for entering a slug + optional version to install.
private struct InstallSkillSheet: View {

    let onInstall: (String, String?) -> Void
    let onCancel: () -> Void

    @State private var slug: String = ""
    @State private var version: String = ""

    var body: some View {
        NavigationStack {
            ZStack {
                DesignTokens.Color.abyss.ignoresSafeArea()

                Form {
                    Section {
                        TextField("slug (e.g. openclaw/ai-recommendation)", text: $slug)
                            .font(AppFont.mono(13))
                            .foregroundStyle(DesignTokens.Color.textPrimary)
                            #if os(iOS)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled(true)
                            #endif
                            .listRowBackground(DesignTokens.Color.glass)

                        TextField("version (optional — latest if blank)", text: $version)
                            .font(AppFont.mono(13))
                            .foregroundStyle(DesignTokens.Color.textPrimary)
                            #if os(iOS)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled(true)
                            #endif
                            .listRowBackground(DesignTokens.Color.glass)
                    } header: {
                        Text("Skill to install")
                            .foregroundStyle(DesignTokens.Color.textSecondary)
                    } footer: {
                        Text("The slug identifies the skill on ClawHub. Versions follow semver — leave blank to track latest.")
                            .foregroundStyle(DesignTokens.Color.textSecondary)
                    }
                }
                .scrollContentBackground(.hidden)
            }
            .navigationTitle("Install skill")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(DesignTokens.Color.abyssRaised, for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
            .toolbarColorScheme(.dark, for: .navigationBar)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", action: onCancel)
                        .foregroundStyle(DesignTokens.Color.textSecondary)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Install") {
                        let trimmed = slug.trimmingCharacters(in: .whitespacesAndNewlines)
                        guard !trimmed.isEmpty else { return }
                        let ver = version.trimmingCharacters(in: .whitespacesAndNewlines)
                        onInstall(trimmed, ver.isEmpty ? nil : ver)
                    }
                    .foregroundStyle(DesignTokens.Color.cyanPrimary)
                    .disabled(slug.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }
}
