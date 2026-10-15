import CryptoKit
import Foundation
import Testing
@testable import GhostCrab

@Suite("Device identity and v3 payload")
struct DeviceIdentityTests {

    @Test func rfc8032Test1SeedYieldsTheRFCPublicKeyAndDeviceId() throws {
        let identity = try DeviceIdentity(seed: TestKeys.rfcSeed)
        #expect(identity.publicKeyRaw == TestKeys.rfcPublicKey)
        #expect(identity.publicKeyBase64URL == "11qYAYKxCrfVS_7TyWQHOg7hcvPapiMlrwIaaPcHURo")
        #expect(identity.deviceId == TestKeys.rfcDeviceId)
    }

    @Test func rfc8032Test1SignatureVerifiesWithCryptoKit() throws {
        let key = try Curve25519.Signing.PublicKey(rawRepresentation: TestKeys.rfcPublicKey)
        let signature = Data(hex: "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b")
        #expect(key.isValidSignature(signature, for: Data()))
    }

    @Test func ourSignaturesVerify() throws {
        let identity = DeviceIdentity.generate()
        let signature = try #require(Base64URL.decode(try identity.sign("payload")))
        let key = try Curve25519.Signing.PublicKey(rawRepresentation: identity.publicKeyRaw)
        #expect(key.isValidSignature(signature, for: Data("payload".utf8)))
        #expect(DeviceIdentity.generate().deviceId != identity.deviceId)
    }

    @Test func theStoredSeedRebuildsTheSameIdentity() throws {
        let identity = DeviceIdentity.generate()
        let seed = try #require(Data(base64Encoded: identity.seedBase64ForStorage()))
        #expect(seed.count == DeviceIdentity.seedBytes)
        #expect(try DeviceIdentity(seed: seed).deviceId == identity.deviceId)
    }

    @Test func deviceIdMatchesTheFingerprintTheRealGatewayAccepted() throws {
        let raw = try #require(Base64URL.decode("e9iojoSOwE3ajNKyeYkso69DO4ByRKR_kJv2IrzMS3Q"))
        #expect(DeviceIdentity.deviceId(forRawPublicKey: raw) == "f044a1f50388e949137206dbf72f4c79c9b499af0e2a4048b005bbf217a9f2e2")
    }

    @Test func descriptionShowsOnlyTheDeviceId() throws {
        let identity = try DeviceIdentity(seed: TestKeys.rfcSeed)
        #expect(String(describing: identity) == "DeviceIdentity(deviceId: \(TestKeys.rfcDeviceId))")
        var dumped = ""
        dump(identity, to: &dumped)
        for rendered in [String(reflecting: identity), dumped] {
            #expect(!rendered.contains("9d61"))
            #expect(!rendered.contains(TestKeys.rfcSeed.base64EncodedString()))
        }
    }

    @Test func aSeedThatIsNot32BytesIsRejected() {
        #expect(throws: DeviceIdentityError.self) { try DeviceIdentity(seed: Data(count: 31)) }
    }

    @Test func v3LayoutMatchesTheFramesTheRealGatewayAccepted() {
        let payload = DeviceAuthPayload(
            deviceId: "f044a1f50388e949137206dbf72f4c79c9b499af0e2a4048b005bbf217a9f2e2",
            clientId: "openclaw-android", clientMode: "ui", role: "operator", scopes: TestKeys.operatorScopes,
            signedAtMs: 1_792_027_489_701, token: "shared-token", nonce: "80f21d17-5553-4b86-b6bf-5bbddbc4f807",
            platform: "android", deviceFamily: nil
        )
        #expect(payload.v3 == "v3|f044a1f50388e949137206dbf72f4c79c9b499af0e2a4048b005bbf217a9f2e2|openclaw-android|ui|operator|operator.read,operator.write,operator.admin|1792027489701|shared-token|80f21d17-5553-4b86-b6bf-5bbddbc4f807|android|")
        var dumped = ""
        dump(payload, to: &dumped)
        for rendered in [String(describing: payload), String(reflecting: payload), dumped] {
            #expect(!rendered.contains("shared-token"))
        }
    }

    @Test func metadataIsTrimmedAndASCIILowercased() {
        let payload = DeviceAuthPayload(deviceId: "d", clientId: "c", clientMode: "ui", role: "operator", scopes: [],
                                        signedAtMs: 1, token: nil, nonce: "n", platform: " iOS ", deviceFamily: "iPhone")
        #expect(payload.v3 == "v3|d|c|ui|operator||1||n|ios|iphone")
        // Only A–Z are lowered (upstream rule): non-ASCII letters keep their case.
        #expect(DeviceAuthPayload.normalize("\nÄBC\t") == "Äbc")
    }

    @Test func nodesSignatureOverTheSameFieldsVerifiesAgainstOurPayload() throws {
        // Node 26: crypto.sign(null, Buffer.from(payload), rfc8032Test1Key).toString("base64url").
        let identity = try DeviceIdentity(seed: TestKeys.rfcSeed)
        let payload = DeviceAuthPayload(
            deviceId: identity.deviceId, clientId: "openclaw-ios", clientMode: "ui", role: "operator",
            scopes: TestKeys.operatorScopes, signedAtMs: 1_792_027_507_354, token: "shared-token",
            nonce: "71d63897-7ebd-44e8-a27a-20ac1598b846", platform: "iOS", deviceFamily: nil
        ).v3
        let nodeSignature = try #require(Base64URL.decode("a1H_WM8G73gnMrA-H2vH1q5eMLmxlXw4L_6iIfBM73InJ6BBBq29VsX3t8EM-f9BQGGM-aPWS1KY6DQ6JlJQCA"))
        let key = try Curve25519.Signing.PublicKey(rawRepresentation: identity.publicKeyRaw)
        #expect(key.isValidSignature(nodeSignature, for: Data(payload.utf8)))
    }
}
