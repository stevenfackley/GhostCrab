import SwiftUI

/// Plain message text whose CLI lines (`openclaw …`) stand on their own line in JetBrains Mono and can be selected,
/// as the brand rule for CLI output asks; the rest renders in Inter. Never parses Markdown. A message without such a
/// line renders as a single `Text`, exactly like `Text(message)` would.
///
/// Callers set the color (`foregroundStyle`) and alignment (`multilineTextAlignment`) from outside.
struct CommandMessageText: View {

    /// A run of prose lines, or one command line.
    struct Segment: Equatable {
        let text: String
        let isCommand: Bool
    }

    private let segments: [Segment]
    private let size: CGFloat
    private let alignment: HorizontalAlignment

    /// - Parameters:
    ///   - message: Plain text; lines are separated by `\n`.
    ///   - size: Point size for both faces.
    ///   - alignment: How the segments line up.
    init(_ message: String, size: CGFloat, alignment: HorizontalAlignment = .leading) {
        self.segments = Self.segments(of: message)
        self.size = size
        self.alignment = alignment
    }

    var body: some View {
        VStack(alignment: alignment, spacing: DesignTokens.Spacing.xs) {
            ForEach(Array(segments.enumerated()), id: \.offset) { _, segment in
                if segment.isCommand {
                    Text(verbatim: segment.text)
                        .font(AppFont.mono(size))
                        .textSelection(.enabled)
                } else {
                    Text(verbatim: segment.text)
                        .font(AppFont.body(size))
                }
            }
        }
    }

    /// Splits `message` into segments: consecutive prose lines stay together, every line that starts with
    /// `openclaw ` is a segment of its own.
    ///
    /// - Parameter message: Plain text.
    /// - Returns: The segments in order; one prose segment when there is no command line.
    static func segments(of message: String) -> [Segment] {
        var result: [Segment] = []
        var prose: [String] = []
        for line in message.split(separator: "\n", omittingEmptySubsequences: false).map(String.init) {
            if line.hasPrefix("openclaw ") {
                if !prose.isEmpty {
                    result.append(Segment(text: prose.joined(separator: "\n"), isCommand: false))
                    prose = []
                }
                result.append(Segment(text: line, isCommand: true))
            } else {
                prose.append(line)
            }
        }
        if !prose.isEmpty {
            result.append(Segment(text: prose.joined(separator: "\n"), isCommand: false))
        }
        return result
    }
}

#Preview {
    ZStack {
        DesignTokens.Color.abyss.ignoresSafeArea()
        CommandMessageText(PairingApproval.timeoutMessage, size: 13)
            .foregroundStyle(DesignTokens.Color.crimsonError)
            .padding()
    }
}
