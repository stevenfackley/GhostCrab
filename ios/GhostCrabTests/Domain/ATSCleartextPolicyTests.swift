import Testing
@testable import GhostCrab

@Suite("ATS cleartext prediction")
struct ATSCleartextPolicyTests {

    @Test(arguments: ["192.168.1.20", "8.8.8.8", "fe80::1", "[fe80::1]", "nas", "localhost", "gateway.local", "Gateway.LOCAL", ""])
    func exemptHostsAreNotBlocked(host: String) {
        #expect(!ATSCleartextPolicy.blocksCleartext(host: host))
    }

    @Test(arguments: ["gw.example.com", "box.tailnet-1234.ts.net", "gateway.home.arpa", "host.corp."])
    func qualifiedHostnamesAreBlocked(host: String) {
        #expect(ATSCleartextPolicy.blocksCleartext(host: host))
    }
}
