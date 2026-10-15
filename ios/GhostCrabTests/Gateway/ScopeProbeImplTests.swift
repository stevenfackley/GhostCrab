import Testing
@testable import GhostCrab

@Suite("ScopeProbeImpl")
struct ScopeProbeImplTests {

    @Test func grantedScopesAreKnown() async {
        let result = await ScopeProbeImpl(scopes: { Set(TestKeys.operatorScopes) }).probe()
        #expect(result.has("operator.admin"))
    }

    @Test func aTokenOnlySocketHasNoScopes() async {
        guard case .known(let scopes) = await ScopeProbeImpl(scopes: { [] }).probe() else {
            Issue.record("expected known")
            return
        }
        #expect(scopes.isEmpty)
    }

    @Test func disconnectedIsAFailedProbe() async {
        guard case .failed = await ScopeProbeImpl(scopes: { nil }).probe() else {
            Issue.record("expected failed")
            return
        }
    }
}
