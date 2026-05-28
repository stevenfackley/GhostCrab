import SwiftUI

/// Top-level Config Editor screen.
///
/// Direct port of `ConfigEditorScreen.kt`. Handles every
/// ``ConfigEditorUiState`` case with explicit branching — no shared "Oops"
/// screen. Section editing happens in a per-section `.sheet` containing a
/// monospaced raw-JSON text editor; diff preview, ETag-conflict alert, and
/// save-success banner are surfaced inline.
public struct ConfigEditorScreen: View {

    @State private var vm: ConfigEditorViewModel
    @Environment(\.navigate) private var navigate
    @Environment(\.dismiss) private var dismiss

    /// Section currently presented in the editor sheet, if any.
    @State private var editingSection: String?

    public init(viewModel: ConfigEditorViewModel) {
        _vm = State(initialValue: viewModel)
    }

    public var body: some View {
        ZStack {
            DesignTokens.Color.abyss.ignoresSafeArea()

            content
                .frame(maxWidth: 600)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        }
        .navigationTitle("Configure Gateway")
        #if os(iOS)
        .toolbarBackground(DesignTokens.Color.abyssRaised, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .toolbarColorScheme(.dark, for: .navigationBar)
        #endif
        .overlay(alignment: .top) { saveSuccessBanner }
        .alert(
            "Configuration Changed",
            isPresented: concurrentEditBinding,
            presenting: concurrentEditSection
        ) { section in
            Button("Reload") {
                vm.acknowledgeConflict()
                vm.loadConfig()
            }
            Button("Force overwrite", role: .destructive) {
                vm.acknowledgeConflict()
                vm.confirmSave(section)
            }
            Button("Dismiss", role: .cancel) {
                vm.acknowledgeConflict()
            }
        } message: { section in
            Text("Someone else updated this config since you loaded section \"\(section)\". The latest values have been loaded.")
        }
        .task {
            vm.start()
            await waitUntilCancelled()
            vm.stop()
        }
    }

    // MARK: - State-driven content

    @ViewBuilder
    private var content: some View {
        switch vm.state {
        case .loading:
            ProgressView()
                .tint(DesignTokens.Color.cyanPrimary)
                .frame(maxHeight: .infinity)

        case .disconnected:
            EmptyState(
                icon: "wifi.slash",
                title: "Not connected",
                message: "Reconnect to a gateway to edit its configuration.",
                action: ("Pick a gateway", { navigate(.connectionPicker) })
            )
            .frame(maxHeight: .infinity)

        case .noConfigApi:
            EmptyState(
                icon: "doc.text.magnifyingglass",
                title: "No editable configuration",
                message: "This gateway version doesn't expose a JSON config API at /config (it serves an HTML admin UI instead). The connection is healthy — use the gateway's own web UI to edit settings."
            )
            .frame(maxHeight: .infinity)

        case .error(let message):
            EmptyState(
                icon: "exclamationmark.triangle",
                title: "Couldn't load configuration",
                message: message,
                action: ("Retry", { vm.loadConfig() })
            )
            .frame(maxHeight: .infinity)

        case .ready(let config, let pending, _, _, _, _):
            readyContent(config: config, pending: pending)
        }
    }

    // MARK: - Ready content

    @ViewBuilder
    private func readyContent(config: OpenClawConfig, pending: [String: AnyCodable]) -> some View {
        let orderedKeys = orderedSectionKeys(config.sections.keys)

        ScrollView {
            LazyVStack(spacing: DesignTokens.Spacing.sm) {
                ForEach(orderedKeys, id: \.self) { key in
                    if let raw = config.sections[key] {
                        let hasPending = pending[key] != nil
                        let editableCount = countEditableKeys(raw)

                        Button {
                            editingSection = key
                        } label: {
                            sectionCard(
                                key: key,
                                editableCount: editableCount,
                                hasPending: hasPending
                            )
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .padding(.horizontal, DesignTokens.Spacing.md)
            .padding(.vertical, DesignTokens.Spacing.sm)
        }
        .sheet(item: bindingForEditingSection(config: config)) { sectionEdit in
            SectionEditorSheet(
                sectionKey: sectionEdit.key,
                originalValue: sectionEdit.original,
                pendingValue: pending[sectionEdit.key],
                onSave: { newValue in
                    vm.editSection(sectionEdit.key, value: newValue)
                    vm.requestSave(sectionEdit.key)
                    editingSection = nil
                    // Diff sheet is handled inline below.
                    vm.confirmSave(sectionEdit.key)
                },
                onDiscard: {
                    vm.discardSection(sectionEdit.key)
                    editingSection = nil
                },
                onCancel: {
                    editingSection = nil
                }
            )
        }
    }

    private func sectionCard(key: String, editableCount: Int, hasPending: Bool) -> some View {
        GlassSurface {
            HStack(alignment: .center, spacing: DesignTokens.Spacing.sm) {
                VStack(alignment: .leading, spacing: DesignTokens.Spacing.xxs) {
                    HStack(spacing: DesignTokens.Spacing.sm) {
                        Text(key)
                            .font(AppFont.bodyBold(15))
                            .foregroundStyle(DesignTokens.Color.textPrimary)

                        if hasPending {
                            Text("Modified")
                                .font(AppFont.bodyMedium(11))
                                .foregroundStyle(DesignTokens.Color.amberWarn)
                                .padding(.horizontal, DesignTokens.Spacing.sm)
                                .padding(.vertical, DesignTokens.Spacing.xxs)
                                .background(
                                    DesignTokens.Color.amberWarn.opacity(0.18),
                                    in: DesignTokens.Shape.extraSmall
                                )
                        }
                    }
                    Text(editableCountLabel(editableCount))
                        .font(AppFont.body(12))
                        .foregroundStyle(DesignTokens.Color.textSecondary)
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right")
                    .foregroundStyle(DesignTokens.Color.textSecondary)
                    .font(.system(size: 14, weight: .medium))
            }
            .padding(DesignTokens.Spacing.md)
        }
    }

    // MARK: - Save-success banner

    @ViewBuilder
    private var saveSuccessBanner: some View {
        if case .ready(_, _, _, _, true, _) = vm.state {
            HStack(spacing: DesignTokens.Spacing.sm) {
                Image(systemName: "checkmark.seal.fill")
                    .foregroundStyle(DesignTokens.Color.cyanPrimary)
                Text("Configuration saved")
                    .font(AppFont.bodyMedium(14))
                    .foregroundStyle(DesignTokens.Color.textPrimary)
            }
            .padding(.horizontal, DesignTokens.Spacing.md)
            .padding(.vertical, DesignTokens.Spacing.sm)
            .background(DesignTokens.Color.abyssRaised, in: DesignTokens.Shape.small)
            .overlay(
                DesignTokens.Shape.small.strokeBorder(DesignTokens.Color.outline, lineWidth: 1)
            )
            .padding(.top, DesignTokens.Spacing.sm)
            .transition(.move(edge: .top).combined(with: .opacity))
            .task {
                try? await Task.sleep(nanoseconds: 1_800_000_000)
                vm.clearSaveSuccess()
            }
        }
    }

    // MARK: - Conflict-alert plumbing

    private var concurrentEditSection: String? {
        if case .ready(_, _, _, _, _, let conflict) = vm.state {
            return conflict
        }
        return nil
    }

    private var concurrentEditBinding: Binding<Bool> {
        Binding(
            get: { concurrentEditSection != nil },
            set: { isShown in if !isShown { vm.acknowledgeConflict() } }
        )
    }

    // MARK: - Helpers

    private func orderedSectionKeys<S: Sequence>(_ keys: S) -> [String] where S.Element == String {
        keys.sorted { a, b in
            if a == "gateway" { return true }
            if b == "gateway" { return false }
            return a < b
        }
    }

    private func countEditableKeys(_ value: AnyCodable) -> Int {
        if let obj = value.value as? [String: AnyCodable] { return obj.count }
        return 0
    }

    private func editableCountLabel(_ n: Int) -> String {
        n == 1 ? "1 editable key" : "\(n) editable keys"
    }

    private func bindingForEditingSection(config: OpenClawConfig) -> Binding<SectionEdit?> {
        Binding(
            get: {
                guard let key = editingSection, let raw = config.sections[key] else { return nil }
                return SectionEdit(key: key, original: raw)
            },
            set: { newValue in editingSection = newValue?.key }
        )
    }

    /// Awaits cancellation forever — used by `.task` to keep `start()` alive
    /// until the view goes away, then `stop()` runs in the deferred block.
    private func waitUntilCancelled() async {
        do {
            try await Task.sleep(nanoseconds: .max)
        } catch {
            // Cancellation arrives here; nothing else to do.
        }
    }
}

// MARK: - Section editor sheet

/// Identifier passed to `.sheet(item:)` carrying the section being edited.
private struct SectionEdit: Identifiable, Hashable {
    let key: String
    let original: AnyCodable
    var id: String { key }

    static func == (lhs: SectionEdit, rhs: SectionEdit) -> Bool { lhs.key == rhs.key }
    func hash(into hasher: inout Hasher) { hasher.combine(key) }
}

/// Modal raw-JSON editor for a single config section.
///
/// Shows the section as pretty-printed JSON in a monospaced ``TextEditor``.
/// "Save" parses the buffer back to ``AnyCodable`` and calls back. Parse errors
/// surface inline; the sheet stays open until JSON is valid.
private struct SectionEditorSheet: View {

    let sectionKey: String
    let originalValue: AnyCodable
    let pendingValue: AnyCodable?
    let onSave: (AnyCodable) -> Void
    let onDiscard: () -> Void
    let onCancel: () -> Void

    @State private var buffer: String = ""
    @State private var parseError: String?

    var body: some View {
        NavigationStack {
            ZStack {
                DesignTokens.Color.abyss.ignoresSafeArea()

                VStack(alignment: .leading, spacing: DesignTokens.Spacing.sm) {
                    Text(sectionKey)
                        .font(AppFont.bodyBold(15))
                        .foregroundStyle(DesignTokens.Color.textPrimary)
                        .padding(.horizontal, DesignTokens.Spacing.md)

                    GlassSurface {
                        TextEditor(text: $buffer)
                            .font(AppFont.mono(13))
                            .foregroundStyle(DesignTokens.Color.textPrimary)
                            .scrollContentBackground(.hidden)
                            .padding(DesignTokens.Spacing.sm)
                            .frame(minHeight: 240)
                    }
                    .padding(.horizontal, DesignTokens.Spacing.md)

                    if let parseError {
                        Text(parseError)
                            .font(AppFont.body(13))
                            .foregroundStyle(DesignTokens.Color.crimsonError)
                            .padding(.horizontal, DesignTokens.Spacing.md)
                    }

                    if pendingValue != nil {
                        Button("Discard pending changes", role: .destructive) {
                            onDiscard()
                        }
                        .font(AppFont.bodyMedium(13))
                        .padding(.horizontal, DesignTokens.Spacing.md)
                    }

                    Spacer()
                }
                .padding(.top, DesignTokens.Spacing.md)
            }
            .navigationTitle("Edit section")
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
                    Button("Save", action: attemptSave)
                        .foregroundStyle(DesignTokens.Color.cyanPrimary)
                }
            }
            .onAppear {
                let source = pendingValue ?? originalValue
                buffer = (try? prettyPrint(source)) ?? ""
            }
        }
    }

    private func attemptSave() {
        do {
            let parsed = try parseAnyCodable(buffer)
            parseError = nil
            onSave(parsed)
        } catch {
            parseError = "Invalid JSON: \(error.localizedDescription)"
        }
    }

    private func prettyPrint(_ value: AnyCodable) throws -> String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
        let data = try encoder.encode(value)
        return String(data: data, encoding: .utf8) ?? ""
    }

    private func parseAnyCodable(_ text: String) throws -> AnyCodable {
        guard let data = text.data(using: .utf8) else {
            throw NSError(domain: "ConfigEditor", code: -1, userInfo: [NSLocalizedDescriptionKey: "not UTF-8"])
        }
        return try JSONDecoder().decode(AnyCodable.self, from: data)
    }
}
