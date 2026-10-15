import SwiftUI

/// AI Recommendations screen — mirrors `AIRecommendationScreen.kt`.
///
/// Layout:
/// 1. Top — multiline prompt field + Ask button.
/// 2. Middle — scrollable response area (loading spinner / markdown response / empty state / error).
/// 3. Bottom — sticky "Apply suggested config" button when the response includes a patch.
///
/// Availability: the screen first asks the service. OpenClaw gateways have no
/// recommendation API (ADR-0007), so today it explains that and offers no
/// prompt; the Dashboard hides its tile for the same reason.
///
/// Error UX: every error, `skillUnavailable` included, renders as a crimson
/// banner inside a `GlassSurface`. Nothing offers to install a skill: none adds
/// recommendations.
public struct AIRecommendationScreen: View {

    @Environment(\.appContainer) private var container

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
            vm?.checkAvailability()
        }
        .onDisappear {
            vm?.cancelInFlight()
        }
    }

    // MARK: - Content

    @ViewBuilder
    private func content(vm: AIRecommendationViewModel) -> some View {
        switch vm.available {
        case nil:
            ProgressView().tint(DesignTokens.Color.cyanPulse)
        case false?:
            unavailableView
        case true?:
            availableContent(vm: vm)
        }
    }

    // MARK: - Unavailable

    @ViewBuilder
    private var unavailableView: some View {
        VStack {
            GlassSurface {
                VStack(alignment: .leading, spacing: DesignTokens.Spacing.sm) {
                    HStack(spacing: DesignTokens.Spacing.sm) {
                        Image(systemName: "sparkles")
                            .foregroundStyle(DesignTokens.Color.amberWarn)
                        Text(AIRecommendationError.unavailableTitle)
                            .font(AppFont.bodyBold(15))
                            .foregroundStyle(DesignTokens.Color.textPrimary)
                    }
                    Text(AIRecommendationError.unavailableBody)
                        .font(AppFont.body(13))
                        .foregroundStyle(DesignTokens.Color.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(DesignTokens.Spacing.md)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            Spacer()
        }
        .padding(.horizontal, DesignTokens.Spacing.md)
        .padding(.vertical, DesignTokens.Spacing.sm)
    }

    // MARK: - Available

    @ViewBuilder
    private func availableContent(vm: AIRecommendationViewModel) -> some View {
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
                        "Describe what to optimize\u{2026}",
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
                    title: "Recommendations",
                    message: "Describe a goal, e.g. \u{201C}best coding model for 16 GB RAM\u{201D}. Suggested config changes are applied only after you confirm them."
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
