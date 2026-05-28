import Testing
import Foundation
@testable import GhostCrab

/// Pins the public/private IP classification used by ``CleartextPublicIPGuard``.
///
/// If this drifts, the cleartext-HTTP-to-public-IPs interceptor will either
/// over-block legitimate LAN traffic or under-block real public IPs.
@Suite("Cleartext public IP literal detection")
struct CleartextPublicIPLiteralTests {

    // MARK: - Public IP literals should be flagged

    @Test func detectsPublicIPv4() {
        #expect(CleartextPublicIPGuard.isPublicIPLiteral("8.8.8.8"))
        #expect(CleartextPublicIPGuard.isPublicIPLiteral("1.1.1.1"))
        #expect(CleartextPublicIPGuard.isPublicIPLiteral("199.165.136.101"))
    }

    // MARK: - Private / loopback / link-local should pass

    @Test func skipsLoopback() {
        #expect(!CleartextPublicIPGuard.isPublicIPLiteral("127.0.0.1"))
        #expect(!CleartextPublicIPGuard.isPublicIPLiteral("127.0.0.99"))
    }

    @Test func skipsRFC1918() {
        #expect(!CleartextPublicIPGuard.isPublicIPLiteral("10.0.0.1"))
        #expect(!CleartextPublicIPGuard.isPublicIPLiteral("10.255.255.1"))
        #expect(!CleartextPublicIPGuard.isPublicIPLiteral("172.16.0.1"))
        #expect(!CleartextPublicIPGuard.isPublicIPLiteral("172.31.255.255"))
        #expect(!CleartextPublicIPGuard.isPublicIPLiteral("192.168.0.1"))
        #expect(!CleartextPublicIPGuard.isPublicIPLiteral("192.168.0.239"))
    }

    @Test func skipsLinkLocal() {
        #expect(!CleartextPublicIPGuard.isPublicIPLiteral("169.254.1.1"))
    }

    // MARK: - Non-literal hostnames always pass

    @Test func skipsHostnames() {
        #expect(!CleartextPublicIPGuard.isPublicIPLiteral("gateway.local"))
        #expect(!CleartextPublicIPGuard.isPublicIPLiteral("example.com"))
        #expect(!CleartextPublicIPGuard.isPublicIPLiteral("openclaw"))
    }

    // MARK: - Garbage inputs

    @Test func rejectsMalformed() {
        #expect(!CleartextPublicIPGuard.isPublicIPLiteral(""))
        #expect(!CleartextPublicIPGuard.isPublicIPLiteral("999.999.999.999"))
        #expect(!CleartextPublicIPGuard.isPublicIPLiteral("not-an-ip"))
    }
}
