import Testing
@testable import GhostCrab

@MainActor
@Suite("CommandMessageText")
struct CommandMessageTextTests {

    @Test func aMessageWithoutACommandIsOneProseSegment() {
        let message = "Open your gateway's connect page in a browser:\nhttp://<gateway-ip>:19999"
        #expect(CommandMessageText.segments(of: message) == [.init(text: message, isCommand: false)])
    }

    @Test func everyCommandLineStandsOnItsOwn() {
        #expect(CommandMessageText.segments(of: PairingApproval.timeoutMessage) == [
            .init(
                text: "Device approval not received within 5 minutes; the pending request expired. On the gateway host run:",
                isCommand: false
            ),
            .init(text: "openclaw devices list", isCommand: true),
            .init(text: "then approve this device and connect again (PAIRING_REQUIRED).", isCommand: false),
        ])
    }

    @Test func aTrailingCommandEndsTheMessage() {
        let segments = CommandMessageText.segments(of: "Run this:\nopenclaw plugins enable bonjour")
        #expect(segments.map(\.isCommand) == [false, true])
        #expect(segments.last?.text == "openclaw plugins enable bonjour")
    }
}
