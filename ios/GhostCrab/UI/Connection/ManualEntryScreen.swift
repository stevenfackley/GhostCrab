import SwiftUI

/// Manual gateway URL/token entry. Mirrors `ManualEntryScreen.kt`.
///
/// Uses SwiftUI `Form` with sections: Pairing link (paste a `ghostcrab://pair`
/// link or gateway URL, the Mac Catalyst stand-in for the QR scanner),
/// Connection (HTTPS toggle + host + port), Authentication (bearer token with
/// visibility toggle). Shows
/// `HttpSecurityBanner` above the form when HTTPS is off; renders the assembled
/// URL preview in JetBrains Mono. Connect button is disabled while connecting
/// or any field has a validation error.
public struct ManualEntryScreen: View {

    @Environment(\.navigate) private var navigate
    @Environment(\.dismiss) private var dismiss

    @State public var vm: ManualEntryViewModel

    /// Optional URL passed in by the navigation Route; applied to the form on
    /// first appearance (``ManualEntryViewModel/applyRoutePrefill(_:)``).
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

                Section {
                    HStack {
                        TextField("Gateway URL or ghostcrab://pair link", text: $vm.pastedLink)
                            #if os(iOS)
                            .keyboardType(.URL)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled(true)
                            #endif
                            .font(AppFont.mono(13))
                            .foregroundStyle(DesignTokens.Color.textPrimary)
                            .submitLabel(.done)
                            .onSubmit { vm.applyPastedLink() }
                            .disabled(vm.isConnecting)

                        Button("Fill") {
                            vm.applyPastedLink()
                        }
                        .font(AppFont.bodyBold(14))
                        .foregroundStyle(DesignTokens.Color.cyanPrimary)
                        .buttonStyle(.plain)
                        .disabled(vm.isConnecting)
                    }
                    if let err = vm.pastedLinkError {
                        Text(err)
                            .font(AppFont.body(12))
                            .foregroundStyle(DesignTokens.Color.crimsonError)
                    }
                } header: {
                    Text("Pairing link")
                } footer: {
                    Text("Paste a link to fill the form below. Nothing connects until you tap Connect.")
                }

                Section("Connection") {
                    Toggle(isOn: Binding(get: { vm.useHttps }, set: { vm.setUseHttps($0) })) {
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
                        if vm.cleartextBlockedByATS {
                            Text("iOS App Transport Security blocks plain HTTP to any hostname: only .local names and IP addresses are allowed over HTTP. Turn on HTTPS, or use the gateway's IP address or .local name.")
                                .font(AppFont.body(12))
                                .foregroundStyle(DesignTokens.Color.amberWarn)
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
                                TextField("Bearer Token (optional)", text: tokenBinding)
                            } else {
                                SecureField("Bearer Token (optional)", text: tokenBinding)
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
            // Once per screen: a later appear, or one after a re-delivery
            // already filled the form, changes nothing.
            vm.applyRoutePrefill(prefillURL)
        }
        .onChange(of: navigate.pairingRedelivery) { _, redelivery in
            // A pairing link for this gateway arrived again while this screen
            // was on top, perhaps re-issued with a new token: the newer link
            // wins and its stash fills the form.
            guard let redelivery else { return }
            vm.pairingRedelivered(redelivery.url, routeURL: prefillURL)
        }
        .onDisappear {
            vm.cancelConnect()
        }
        .onChange(of: vm.navigateToDashboard) { _, ready in
            if ready {
                navigate.toDashboard()
                vm.consumeNavigation()
            }
        }
    }

    private var connectDisabled: Bool {
        vm.isConnecting || vm.hostError != nil || vm.portError != nil
    }

    /// Typing marks the token as the user's own (``ManualEntryViewModel/setToken(_:)``).
    private var tokenBinding: Binding<String> {
        Binding(get: { vm.token }, set: { vm.setToken($0) })
    }
}
