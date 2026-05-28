import SwiftUI

/// Manual gateway URL/token entry. Mirrors `ManualEntryScreen.kt`.
///
/// Uses SwiftUI `Form` with sections: Connection (HTTPS toggle + host + port),
/// Authentication (bearer token with visibility toggle). Shows
/// `HttpSecurityBanner` above the form when HTTPS is off; renders the assembled
/// URL preview in JetBrains Mono. Connect button is disabled while connecting
/// or any field has a validation error.
public struct ManualEntryScreen: View {

    @Environment(\.navigate) private var navigate
    @Environment(\.dismiss) private var dismiss

    @State public var vm: ManualEntryViewModel

    /// Optional URL passed in by the navigation Route; applied to the form on first appearance.
    public let prefillURL: URL?

    public init(vm: ManualEntryViewModel, prefillURL: URL? = nil) {
        self._vm = State(initialValue: vm)
        self.prefillURL = prefillURL
    }

    public var body: some View {
        ZStack {
            DesignTokens.Color.abyss.ignoresSafeArea()

            Form {
                if !vm.useHttps {
                    Section {
                        HttpSecurityBanner(httpWithoutAuth: vm.token.trimmingCharacters(in: .whitespaces).isEmpty)
                            .listRowBackground(Color.clear)
                            .listRowInsets(EdgeInsets())
                    }
                }

                Section("Connection") {
                    Toggle(isOn: $vm.useHttps) {
                        VStack(alignment: .leading, spacing: DesignTokens.Spacing.xxs) {
                            Text("Use HTTPS")
                                .font(AppFont.body(15))
                                .foregroundStyle(DesignTokens.Color.textPrimary)
                            Text(vm.useHttps ? "https://" : "http://")
                                .font(AppFont.mono(12))
                                .foregroundStyle(DesignTokens.Color.textSecondary)
                        }
                    }
                    .tint(DesignTokens.Color.cyanPrimary)
                    .disabled(vm.isConnecting)

                    VStack(alignment: .leading, spacing: DesignTokens.Spacing.xs) {
                        TextField(
                            "Host / IP",
                            text: Binding(get: { vm.host }, set: { vm.setHost($0) })
                        )
                        #if os(iOS)
                        .keyboardType(.URL)
                        .autocapitalization(.none)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled(true)
                        #endif
                        .font(AppFont.mono(15))
                        .foregroundStyle(DesignTokens.Color.textPrimary)
                        .disabled(vm.isConnecting)
                        if let err = vm.hostError {
                            Text(err)
                                .font(AppFont.body(12))
                                .foregroundStyle(DesignTokens.Color.crimsonError)
                        }
                    }

                    VStack(alignment: .leading, spacing: DesignTokens.Spacing.xs) {
                        TextField(
                            "Port",
                            text: Binding(get: { vm.port }, set: { vm.setPort($0) })
                        )
                        #if os(iOS)
                        .keyboardType(.numberPad)
                        #endif
                        .font(AppFont.mono(15))
                        .foregroundStyle(DesignTokens.Color.textPrimary)
                        .disabled(vm.isConnecting)
                        if let err = vm.portError {
                            Text(err)
                                .font(AppFont.body(12))
                                .foregroundStyle(DesignTokens.Color.crimsonError)
                        }
                    }
                }

                Section("Authentication") {
                    HStack {
                        Group {
                            if vm.tokenVisible {
                                TextField("Bearer Token (optional)", text: $vm.token)
                            } else {
                                SecureField("Bearer Token (optional)", text: $vm.token)
                            }
                        }
                        #if os(iOS)
                        .autocapitalization(.none)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled(true)
                        #endif
                        .font(AppFont.mono(14))
                        .foregroundStyle(DesignTokens.Color.textPrimary)
                        .disabled(vm.isConnecting)

                        Button {
                            vm.toggleTokenVisibility()
                        } label: {
                            Image(systemName: vm.tokenVisible ? "eye.slash" : "eye")
                                .foregroundStyle(DesignTokens.Color.textSecondary)
                        }
                        .buttonStyle(.plain)
                    }
                }

                Section {
                    Text(vm.assembledURL)
                        .font(AppFont.mono(13))
                        .foregroundStyle(DesignTokens.Color.textSecondary)
                        .frame(maxWidth: .infinity, alignment: .leading)

                    if let err = vm.lastError {
                        Text(err)
                            .font(AppFont.mono(12))
                            .foregroundStyle(DesignTokens.Color.crimsonError)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }

                    Button(action: { vm.connect() }) {
                        HStack {
                            Spacer()
                            if vm.isConnecting {
                                ProgressView()
                                    .progressViewStyle(.circular)
                                    .tint(DesignTokens.Color.abyss)
                            } else {
                                Text("Connect")
                                    .font(AppFont.bodyBold(15))
                                    .foregroundStyle(DesignTokens.Color.abyss)
                            }
                            Spacer()
                        }
                        .padding(.vertical, DesignTokens.Spacing.sm)
                        .background(
                            DesignTokens.Shape.small.fill(
                                connectDisabled
                                    ? DesignTokens.Color.cyanPrimary.opacity(0.4)
                                    : DesignTokens.Color.cyanPrimary
                            )
                        )
                    }
                    .buttonStyle(.plain)
                    .disabled(connectDisabled)
                }
            }
            .scrollContentBackground(.hidden)
            .background(DesignTokens.Color.abyss)
        }
        .navigationTitle("Connect to Gateway")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(DesignTokens.Color.abyssRaised, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .toolbarColorScheme(.dark, for: .navigationBar)
        #endif
        .onAppear {
            if let prefill = prefillURL { vm.setPrefillURL(prefill) }
        }
        .onChange(of: vm.navigateToDashboard) { _, ready in
            if ready {
                navigate(.dashboard)
                vm.consumeNavigation()
            }
        }
    }

    private var connectDisabled: Bool {
        vm.isConnecting || vm.hostError != nil || vm.portError != nil
    }
}
