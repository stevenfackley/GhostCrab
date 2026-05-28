import SwiftUI

/// AI Recommendations screen — mirrors `AIRecommendationScreen.kt`.
///
/// Layout:
/// 1. Top — multiline prompt field + Ask button.
/// 2. Middle — scrollable response area (loading spinner / markdown response / empty state / error).
/// 3. Bottom — sticky "Apply suggested config" button when the response includes a patch.
///
/// Error UX:
/// - `skillUnavailable` shows a prominent install CTA → `navigate(.installedSkills)`.
/// - `quotaExceeded` and other errors render as a crimson banner inside a `GlassSurface`.
public struct AIRecommendationScreen: View {

    @Environment(\.appContainer) private var container
    @Environment(\.navigate) private var navigate

    @State private var vm: AIRecommendationViewModel?

    public init() {}

    public var body: some View {
        ZStack {
            DesignTokens.Color.abyss.ignoresSafeArea()

            if let vm {
                content(vm: vm)
            } else {
                ProgressView().tint(DesignTokens.Color.cyanPulse)
            }
        }
        .navigationTitle("AI Recommendations")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(DesignTokens.Color.abyssRaised, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .toolbarColorScheme(.dark, for: .navigationBar)
        #endif
        .onAppear {
            if vm == nil { vm = container.makeAIRecommendationVM() }
        }
    }

    // MARK: - Content

    @ViewBuilder
    private func content(vm: AIRecommendationViewModel) -> some View {
        VStack(spacing: DesignTokens.Spacing.md) {
            promptRow(vm: vm)
            responseArea(vm: vm)
            if vm.hasSuggestedPatch {
                applyButton(vm: vm)
            }
        }
        .padding(.horizontal, DesignTokens.Spacing.md)
        .padding(.vertical, DesignTokens.Spacing.sm)
        .sheet(isPresented: Binding(
            get: { vm.isPresentingPatch },
            set: { vm.isPresentingPatch = $0 }
        )) {
            patchSheet(vm: vm)
        }
    }

    // MARK: - Prompt

    @ViewBuilder
    private func promptRow(vm: AIRecommendationViewModel) -> some View {
        GlassSurface {
            VStack(alignment: .leading, spacing: DesignTokens.Spacing.sm) {
                HStack(alignment: .top, spacing: DesignTokens.Spacing.sm) {
                    TextField(
                        "Ask the gateway\u{2019}s AI\u{2026}",
                        text: Binding(
                            get: { vm.prompt },
                            set: { vm.prompt = $0 }
                        ),
                        axis: .vertical
                    )
                    .lineLimit(3...6)
                    .font(AppFont.body(15))
                    .foregroundStyle(DesignTokens.Color.textPrimary)
                    .disabled(vm.loading)
                    .textInputAutocapitalization(.sentences)

                    Button {
                        vm.submit()
                    } label: {
                        if vm.loading {
                            ProgressView()
                                .progressViewStyle(.circular)
                                .tint(DesignTokens.Color.abyss)
                        } else {
                            Image(systemName: "sparkles")
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(DesignTokens.Color.cyanPrimary)
                    .foregroundStyle(DesignTokens.Color.abyss)
                    .disabled(vm.loading || vm.prompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
            .padding(DesignTokens.Spacing.md)
        }
    }

    // MARK: - Response area

    @ViewBuilder
    private func responseArea(vm: AIRecommendationViewModel) -> some View {
        Group {
            if vm.loading {
                loadingView
            } else if let error = vm.lastError {
                errorView(error: error, vm: vm)
            } else if vm.response != nil {
                responseView(vm: vm)
            } else {
                EmptyState(
                    icon: "brain",
                    title: "Ask the gateway\u{2019}s AI",
                    message: "Get configuration suggestions from your gateway\u{2019}s installed AI skill."
                )
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    // MARK: - Loading

    @ViewBuilder
    private var loadingView: some View {
        VStack(spacing: DesignTokens.Spacing.md) {
            ProgressView()
                .controlSize(.large)
                .tint(DesignTokens.Color.cyanPulse)
            Text("Thinking\u{2026}")
                .font(AppFont.body(13))
                .foregroundStyle(DesignTokens.Color.textSecondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    // MARK: - Response

    @ViewBuilder
    private func responseView(vm: AIRecommendationViewModel) -> some View {
        ScrollView {
            GlassSurface {
                VStack(alignment: .leading, spacing: DesignTokens.Spacing.md) {
                    Text("Recommendation")
                        .font(AppFont.bodyBold(12))
                        .foregroundStyle(DesignTokens.Color.cyanPrimary)
                    Text(vm.attributedResponse())
                        .font(AppFont.body(15))
                        .foregroundStyle(DesignTokens.Color.textPrimary)
                        .textSelection(.enabled)

                    if let response = vm.response, !response.suggestedChanges.isEmpty {
                        Divider().background(DesignTokens.Color.outline)
                        Text("\(response.suggestedChanges.count) suggested change\(response.suggestedChanges.count == 1 ? "" : "s")")
                            .font(AppFont.bodyBold(12))
                            .foregroundStyle(DesignTokens.Color.textSecondary)
                        ForEach(response.suggestedChanges, id: \.self) { change in
                            changeSummary(change)
                        }
                    }
                }
                .padding(DesignTokens.Spacing.md)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }

    @ViewBuilder
    private func changeSummary(_ change: SuggestedChange) -> some View {
        VStack(alignment: .leading, spacing: DesignTokens.Spacing.xs) {
            Text("\(change.section).\(change.key)")
                .font(AppFont.monoMedium(13))
                .foregroundStyle(DesignTokens.Color.cyanPulse)
            if let current = change.currentValue {
                Text("was: \(current)")
                    .font(AppFont.mono(12))
                    .foregroundStyle(DesignTokens.Color.textSecondary)
                    .strikethrough()
            }
            Text("now: \(change.suggestedValue)")
                .font(AppFont.mono(12))
                .foregroundStyle(DesignTokens.Color.cyanPrimary)
            if !change.rationale.isEmpty {
                Text(change.rationale)
                    .font(AppFont.body(12))
                    .foregroundStyle(DesignTokens.Color.textSecondary)
            }
        }
        .padding(DesignTokens.Spacing.sm)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            DesignTokens.Shape.small.fill(DesignTokens.Color.abyssRaised)
        )
    }

    // MARK: - Error

    @ViewBuilder
    private func errorView(error: AIRecommendationError, vm: AIRecommendationViewModel) -> some View {
        GlassSurface {
            VStack(alignment: .leading, spacing: DesignTokens.Spacing.sm) {
                HStack(spacing: DesignTokens.Spacing.sm) {
                    Image(systemName: errorSystemImage(for: error))
                        .foregroundStyle(DesignTokens.Color.crimsonError)
                    Text(error.headline)
                        .font(AppFont.bodyBold(15))
                        .foregroundStyle(DesignTokens.Color.crimsonError)
                }
                Text(error.body)
                    .font(AppFont.body(13))
                    .foregroundStyle(DesignTokens.Color.textPrimary)

                HStack(spacing: DesignTokens.Spacing.sm) {
                    if case .skillUnavailable = error {
                        Button("Install ai.recommend skill") {
                            navigate(.installedSkills)
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(DesignTokens.Color.cyanPrimary)
                        .foregroundStyle(DesignTokens.Color.abyss)
                    }
                    Button("Dismiss") { vm.dismissError() }
                        .buttonStyle(.bordered)
                        .tint(DesignTokens.Color.textSecondary)
                }
            }
            .padding(DesignTokens.Spacing.md)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func errorSystemImage(for error: AIRecommendationError) -> String {
        switch error {
        case .skillUnavailable: return "puzzlepiece.extension"
        case .quotaExceeded:    return "hourglass"
        case .other:            return "exclamationmark.triangle.fill"
        }
    }

    // MARK: - Apply button

    @ViewBuilder
    private func applyButton(vm: AIRecommendationViewModel) -> some View {
        Button {
            vm.applySuggestedPatch()
        } label: {
            HStack {
                Image(systemName: "checkmark.seal.fill")
                Text("Apply suggested config")
                    .font(AppFont.bodyBold(15))
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, DesignTokens.Spacing.sm)
        }
        .buttonStyle(.borderedProminent)
        .tint(DesignTokens.Color.cyanPrimary)
        .foregroundStyle(DesignTokens.Color.abyss)
    }

    // MARK: - Patch sheet

    @ViewBuilder
    private func patchSheet(vm: AIRecommendationViewModel) -> some View {
        NavigationStack {
            ZStack {
                DesignTokens.Color.abyss.ignoresSafeArea()
                ScrollView {
                    VStack(alignment: .leading, spacing: DesignTokens.Spacing.md) {
                        Text("These changes will be applied to the gateway's openclaw.json. Review the diff before confirming.")
                            .font(AppFont.body(13))
                            .foregroundStyle(DesignTokens.Color.textSecondary)

                        if let response = vm.response {
                            ForEach(response.suggestedChanges, id: \.self) { change in
                                GlassSurface {
                                    VStack(alignment: .leading, spacing: DesignTokens.Spacing.xs) {
                                        Text("\(change.section).\(change.key)")
                                            .font(AppFont.monoMedium(13))
                                            .foregroundStyle(DesignTokens.Color.cyanPrimary)
                                        if let current = change.currentValue {
                                            Text("- \(current)")
                                                .font(AppFont.mono(12))
                                                .foregroundStyle(DesignTokens.Color.crimsonError)
                                        }
                                        Text("+ \(change.suggestedValue)")
                                            .font(AppFont.mono(12))
                                            .foregroundStyle(DesignTokens.Color.cyanPrimary)
                                        if !change.rationale.isEmpty {
                                            Text(change.rationale)
                                                .font(AppFont.body(12))
                                                .foregroundStyle(DesignTokens.Color.textSecondary)
                                                .padding(.top, DesignTokens.Spacing.xs)
                                        }
                                    }
                                    .padding(DesignTokens.Spacing.md)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                }
                            }
                        }

                        if let error = vm.lastError, case .other(let message) = error {
                            Text(message)
                                .font(AppFont.body(13))
                                .foregroundStyle(DesignTokens.Color.crimsonError)
                        }
                    }
                    .padding(DesignTokens.Spacing.md)
                }
            }
            .navigationTitle("Apply config patch")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { vm.isPresentingPatch = false }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Apply") { vm.confirmApplyPatch() }
                        .fontWeight(.bold)
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}
