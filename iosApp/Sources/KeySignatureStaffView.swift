import SwiftUI
import Shared

/// Small staff-notation picture of a key's signature: 5 lines, treble clef, and the correctly
/// placed sharps/flats. iOS counterpart of Android's `KeySignatureView`
/// (app/src/main/java/.../ui/KeySignatureView.kt) — that logic lives only in the Android app
/// module (not shared Kotlin), so the signature table below is a deliberate duplicate.
struct KeySignatureStaffView: View {
    let key: Key
    var height: CGFloat = 28

    /// Vertical positions (in half-steps from the top line, downward positive) in standard
    /// accidental order. Sharps: F# C# G# D# A# E# B#. Flats: Bb Eb Ab Db Gb Cb Fb.
    private static let sharpSteps: [CGFloat] = [0, 3, -1, 2, 5, 1, 4]
    private static let flatSteps: [CGFloat] = [4, 1, 5, 2, 6, 3, 7]
    private static let accidentalWidthFactor: CGFloat = 0.95
    private static let gapAfterClef: CGFloat = 0.5
    private static let clefBoxWidthFactor: CGFloat = 2.3

    /// Number of accidentals and whether they're sharps (true) or flats (false), for the major key.
    private var signature: (count: Int, sharps: Bool) {
        if key == Key.c { return (0, true) }
        if key == Key.g { return (1, true) }
        if key == Key.d { return (2, true) }
        if key == Key.a { return (3, true) }
        if key == Key.e { return (4, true) }
        if key == Key.b { return (5, true) }
        if key == Key.fSharp { return (6, true) }
        if key == Key.cSharp { return (7, true) }
        if key == Key.f { return (1, false) }
        if key == Key.bFlat { return (2, false) }
        if key == Key.eFlat { return (3, false) }
        if key == Key.aFlat { return (4, false) }
        if key == Key.dFlat { return (5, false) }
        if key == Key.gFlat { return (6, false) }
        return (0, true)
    }

    private var lineGap: CGFloat { height / 7 }
    private var clefBoxWidth: CGFloat { Self.clefBoxWidthFactor * lineGap }

    private var width: CGFloat {
        let count = signature.count
        return 0.3 * lineGap + clefBoxWidth + Self.gapAfterClef * lineGap
            + CGFloat(count) * (Self.accidentalWidthFactor * lineGap) + 0.4 * lineGap
    }

    var body: some View {
        let (count, sharps) = signature
        Canvas { context, size in
            let lineGap = size.height / 7
            let halfStep = lineGap / 2
            let topLineY = (size.height - 4 * lineGap) / 2
            let color = GraphicsContext.Shading.color(.primary)

            var lines = Path()
            for i in 0...4 {
                let y = topLineY + CGFloat(i) * lineGap
                lines.move(to: CGPoint(x: 0, y: y))
                lines.addLine(to: CGPoint(x: size.width, y: y))
            }
            context.stroke(lines, with: color, lineWidth: max(1, lineGap * 0.09))

            let clefBoxWidth = Self.clefBoxWidthFactor * lineGap
            let clefCenter = CGPoint(
                x: 0.3 * lineGap + clefBoxWidth / 2,
                y: topLineY + 2 * lineGap - lineGap * 0.15
            )
            context.draw(
                Text(verbatim: "𝄞").font(.system(size: lineGap * 6.6)).foregroundColor(.primary),
                at: clefCenter,
                anchor: .center
            )

            let steps = sharps ? Self.sharpSteps : Self.flatSteps
            let symbol = sharps ? "♯" : "♭"
            var x = 0.3 * lineGap + clefBoxWidth + Self.gapAfterClef * lineGap
            for i in 0..<count {
                let cy = topLineY + steps[i] * halfStep
                context.draw(
                    Text(verbatim: symbol).font(.system(size: lineGap * 2.3)).foregroundColor(.primary),
                    at: CGPoint(x: x + Self.accidentalWidthFactor * lineGap / 2, y: cy),
                    anchor: .center
                )
                x += Self.accidentalWidthFactor * lineGap
            }
        }
        .frame(width: width, height: height)
        .accessibilityHidden(true)
    }
}

/// Button showing the currently selected key (name + staff picture) that presents a sheet to
/// pick a different one. Shared by the control bars / settings row that used to be a plain
/// `Menu` of text-only key names.
struct KeyPickerButton: View {
    let keys: [Key]
    let selectedKey: Key
    var showsIcon: Bool = true
    let onSelect: (Key) -> Void

    @State private var isPresented = false

    var body: some View {
        Button {
            isPresented = true
        } label: {
            HStack(spacing: 6) {
                if showsIcon {
                    Image(systemName: "key")
                }
                Text(verbatim: selectedKey.displayName)
                KeySignatureStaffView(key: selectedKey)
            }
        }
        .sheet(isPresented: $isPresented) {
            KeyPickerSheet(keys: keys, selectedKey: selectedKey) { key in
                isPresented = false
                onSelect(key)
            }
        }
    }
}

private struct KeyPickerSheet: View {
    let keys: [Key]
    let selectedKey: Key
    let onSelect: (Key) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationView {
            List(keys, id: \.ordinal) { key in
                Button {
                    onSelect(key)
                } label: {
                    HStack {
                        Text(verbatim: key.displayName)
                            .foregroundStyle(Color(.label))
                        Spacer()
                        KeySignatureStaffView(key: key)
                        if key == selectedKey {
                            Image(systemName: "checkmark")
                                .foregroundStyle(.tint)
                        }
                    }
                }
            }
            .listStyle(.plain)
            .navigationTitle("Key")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }
}
