import SwiftUI
import Shared

/// Per-measure pattern editors, presented as sheets from `ProgressionScreen`. iOS counterparts
/// of Android's `DrumPatternActivity` / `StrummingPatternActivity` / `SoloPatternActivity`.
/// Editing logic lives in the shared `*PatternEditor` cores; these views are presentation only.

// MARK: - Drum

struct DrumPatternSheet: View {
    @StateObject private var model: DrumEditorModel
    @Environment(\.dismiss) private var dismiss

    init(measureIndex: Int) {
        _model = StateObject(wrappedValue: DrumEditorModel(measureIndex: measureIndex))
    }

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    grid
                    presetSection("Verwendet", model.usedPatterns)
                    presetSection("Weitere", model.defaultPatterns)
                }
                .padding(16)
            }
            .navigationTitle("Schlagzeug · Takt \(model.measureIndex + 1)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Abbrechen") { model.stopPreview(); dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Fertig") { model.save(); model.stopPreview(); dismiss() }
                }
            }
            .safeAreaInset(edge: .bottom) { previewBar(isPlaying: model.isPreviewing, action: model.togglePreview) }
        }
    }

    private var grid: some View {
        VStack(spacing: 6) {
            laneRow(name: "Kick", isOn: { $0.kick }, toggle: model.toggleKick)
            laneRow(name: "Snare", isOn: { $0.snare }, toggle: model.toggleSnare)
            laneRow(name: "Hi-Hat", isOn: { $0.hiHat }, toggle: model.toggleHiHat)
        }
    }

    private func laneRow(name: String, isOn: @escaping (DrumStep) -> Bool, toggle: @escaping (Int) -> Void) -> some View {
        HStack(spacing: 4) {
            Text(name)
                .font(.caption)
                .frame(width: 52, alignment: .leading)
                .foregroundStyle(.secondary)
            ForEach(Array(model.steps.enumerated()), id: \.offset) { index, step in
                Button {
                    toggle(index)
                } label: {
                    RoundedRectangle(cornerRadius: 5)
                        .fill(isOn(step) ? Color.accentColor : Color(.tertiarySystemBackground))
                        .frame(height: 34)
                        .overlay(
                            RoundedRectangle(cornerRadius: 5)
                                .stroke(index % 2 == 0 ? Color.secondary.opacity(0.25) : Color.clear, lineWidth: 1)
                        )
                }
                .buttonStyle(.plain)
            }
        }
    }

    @ViewBuilder
    private func presetSection(_ title: String, _ patterns: [DrumPattern]) -> some View {
        if !patterns.isEmpty {
            VStack(alignment: .leading, spacing: 6) {
                Text(title).font(.caption).foregroundStyle(.secondary)
                ForEach(Array(patterns.enumerated()), id: \.offset) { _, pattern in
                    Button { model.selectPreset(pattern) } label: {
                        HStack(spacing: 3) {
                            ForEach(Array(pattern.steps.enumerated()), id: \.offset) { _, step in
                                VStack(spacing: 2) {
                                    dot(step.kick); dot(step.snare); dot(step.hiHat)
                                }
                            }
                            Spacer()
                            Text(pattern.name).font(.caption).foregroundStyle(.secondary)
                        }
                        .padding(8)
                        .background(RoundedRectangle(cornerRadius: 8).fill(Color(.secondarySystemBackground)))
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    private func dot(_ on: Bool) -> some View {
        Circle().fill(Color.primary.opacity(on ? 0.9 : 0.15)).frame(width: 6, height: 6)
    }
}

// MARK: - Strumming

struct StrummingPatternSheet: View {
    @StateObject private var model: StrummingEditorModel
    @Environment(\.dismiss) private var dismiss

    init(measureIndex: Int) {
        _model = StateObject(wrappedValue: StrummingEditorModel(measureIndex: measureIndex))
    }

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    editorRow
                    legend
                    presetSection("Verwendet", model.usedPatterns)
                    presetSection("Weitere", model.defaultPatterns)
                }
                .padding(16)
            }
            .navigationTitle("Anschlag · Takt \(model.measureIndex + 1)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Abbrechen") { model.stopPreview(); dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Fertig") { model.save(); model.stopPreview(); dismiss() }
                }
            }
            .safeAreaInset(edge: .bottom) { previewBar(isPlaying: model.isPreviewing, action: model.togglePreview) }
        }
    }

    private var editorRow: some View {
        HStack(spacing: 4) {
            ForEach(Array(model.strums.enumerated()), id: \.offset) { index, strum in
                Button { model.cycle(index) } label: {
                    Text(PatternDisplay.symbol(for: strum))
                        .font(.title3)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .background(RoundedRectangle(cornerRadius: 6).fill(Color(.tertiarySystemBackground)))
                }
                .buttonStyle(.plain)
            }
        }
    }

    private var legend: some View {
        VStack(alignment: .leading, spacing: 4) {
            legendRow("↓", "Downstroke")
            legendRow("↑", "Upstroke")
            legendRow("✕", "Palm Mute")
            legendRow("→", "Ausklingen lassen")
            legendRow("·", "Pause")
        }
        .font(.caption)
        .foregroundStyle(.secondary)
    }

    private func legendRow(_ symbol: String, _ text: String) -> some View {
        HStack(spacing: 8) {
            Text(symbol).frame(width: 16)
            Text(text)
        }
    }

    @ViewBuilder
    private func presetSection(_ title: String, _ patterns: [StrummingPattern]) -> some View {
        if !patterns.isEmpty {
            VStack(alignment: .leading, spacing: 6) {
                Text(title).font(.caption).foregroundStyle(.secondary)
                ForEach(Array(patterns.enumerated()), id: \.offset) { _, pattern in
                    Button { model.selectPreset(pattern) } label: {
                        HStack {
                            Text(pattern.strums.map { PatternDisplay.symbol(for: $0) }.joined(separator: " "))
                                .font(.subheadline.monospaced())
                            Spacer()
                            Text(pattern.name).font(.caption).foregroundStyle(.secondary)
                        }
                        .padding(8)
                        .background(RoundedRectangle(cornerRadius: 8).fill(Color(.secondarySystemBackground)))
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }
}

// MARK: - Solo

struct SoloPatternSheet: View {
    @StateObject private var model: SoloEditorModel
    @Environment(\.dismiss) private var dismiss

    init(measureIndex: Int) {
        _model = StateObject(wrappedValue: SoloEditorModel(measureIndex: measureIndex))
    }

    // (label, pitchClass, octaveOffset, isBlack) — mirrors Android's keyboard span.
    private static let keys: [(String, Int, Int, Bool)] = [
        ("B", 11, -1, false),
        ("C", 0, 0, false), ("C♯", 1, 0, true), ("D", 2, 0, false), ("D♯", 3, 0, true),
        ("E", 4, 0, false), ("F", 5, 0, false), ("F♯", 6, 0, true), ("G", 7, 0, false),
        ("G♯", 8, 0, true), ("A", 9, 0, false), ("A♯", 10, 0, true), ("B", 11, 0, false),
        ("C", 0, 1, false), ("C♯", 1, 1, true), ("D", 2, 1, false),
    ]

    var body: some View {
        NavigationView {
            VStack(spacing: 0) {
                modeBar
                Divider()
                ScrollView {
                    VStack(spacing: 8) {
                        ForEach(0..<model.measureCount, id: \.self) { measure in
                            measureCard(measure)
                        }
                    }
                    .padding(16)
                }
                Divider()
                controls
                keyboard
            }
            .navigationTitle("Solo · Takt \(model.measureIndex + 1)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Abbrechen") { model.stopPreview(); dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Fertig") { model.save(); model.stopPreview(); dismiss() }
                }
            }
        }
    }

    private var modeBar: some View {
        HStack {
            Button { model.cycleEditMode() } label: {
                Label(modeName, systemImage: modeIcon)
                    .font(.subheadline)
            }
            Spacer()
            Button { model.copyAll() } label: { Image(systemName: "doc.on.doc") }
            Button { model.pasteAll() } label: { Image(systemName: "doc.on.clipboard") }
                .disabled(!model.hasClipboard)
            Button { model.togglePreview() } label: {
                Image(systemName: model.isPreviewing ? "stop.fill" : "play.fill")
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
    }

    private var modeName: String {
        if model.editMode == SoloEditMode.edit { return "Bearbeiten" }
        if model.editMode == SoloEditMode.live { return "Live" }
        return "Vorschau"
    }

    private var modeIcon: String {
        if model.editMode == SoloEditMode.edit { return "pencil" }
        if model.editMode == SoloEditMode.live { return "record.circle" }
        return "eye"
    }

    private func measureCard(_ measure: Int) -> some View {
        let isActive = measure == model.activeMeasure
        return HStack(spacing: 4) {
            Text("\(measure + 1)")
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
                .frame(width: 18)
            ForEach(Array(model.slots(measure).enumerated()), id: \.offset) { slotIndex, slot in
                let selected = isActive && slotIndex == model.cursor
                Button {
                    model.selectSlot(measure: measure, slot: slotIndex)
                } label: {
                    Text(PatternDisplay.soloSlotLabel(slot))
                        .font(.caption2)
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                        .frame(maxWidth: .infinity, minHeight: 40)
                        .background(
                            RoundedRectangle(cornerRadius: 5)
                                .fill(slotFill(slot: slot, selected: selected))
                        )
                }
                .buttonStyle(.plain)
            }
        }
        .padding(6)
        .background(
            RoundedRectangle(cornerRadius: 8)
                .stroke(isActive ? Color.accentColor : Color.clear, lineWidth: 2)
                .background(RoundedRectangle(cornerRadius: 8).fill(Color(.secondarySystemBackground)))
        )
    }

    private func slotFill(slot: SoloSlot, selected: Bool) -> Color {
        if selected { return Color.accentColor.opacity(0.35) }
        if PatternDisplay.isNote(slot) { return Color.accentColor.opacity(0.18) }
        return Color(.tertiarySystemBackground)
    }

    private var controls: some View {
        HStack(spacing: 12) {
            HStack(spacing: 4) {
                Button { model.octaveDown() } label: { Image(systemName: "minus.circle") }
                Text("Okt. \(model.octave)").font(.subheadline.monospacedDigit())
                Button { model.octaveUp() } label: { Image(systemName: "plus.circle") }
            }
            Spacer()
            Button { model.setRest() } label: { Text("Pause") }
                .buttonStyle(.bordered)
            Button { model.setLetRing() } label: { Text("Halten") }
                .buttonStyle(.bordered)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
    }

    private var keyboard: some View {
        let scale = model.scalePitchClasses()
        let rootPc = model.rootPitchClass(measure: model.activeMeasure, slot: model.cursor)
        return ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 3) {
                ForEach(Array(Self.keys.enumerated()), id: \.offset) { _, key in
                    let (label, pitchClass, octaveOffset, isBlack) = key
                    PianoKey(
                        label: label,
                        isBlack: isBlack,
                        dot: dotColor(pitchClass: pitchClass, rootPc: rootPc, scale: scale),
                        onPress: { model.pressKey(pitchClass: pitchClass, octaveOffset: octaveOffset) },
                        onRelease: { model.releaseKey() }
                    )
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
        }
        .background(.thinMaterial)
    }

    private func dotColor(pitchClass: Int, rootPc: Int, scale: Set<Int>) -> Color? {
        if pitchClass == rootPc { return .green }
        if scale.contains(pitchClass) { return .orange }
        return nil
    }
}

private struct PianoKey: View {
    let label: String
    let isBlack: Bool
    let dot: Color?
    let onPress: () -> Void
    let onRelease: () -> Void

    @State private var pressing = false

    var body: some View {
        VStack(spacing: 4) {
            Spacer()
            if let dot { Circle().fill(dot).frame(width: 8, height: 8) }
            Text(label).font(.caption2)
        }
        .frame(width: isBlack ? 34 : 44, height: isBlack ? 96 : 128)
        .background(
            RoundedRectangle(cornerRadius: 5)
                .fill(isBlack ? Color(.label).opacity(pressing ? 0.55 : 0.8) : Color(.systemBackground))
        )
        .overlay(RoundedRectangle(cornerRadius: 5).stroke(Color.secondary.opacity(0.4), lineWidth: 1))
        .foregroundStyle(isBlack ? Color(.systemBackground) : Color(.label))
        .scaleEffect(pressing ? 0.97 : 1)
        .gesture(
            DragGesture(minimumDistance: 0)
                .onChanged { _ in
                    if !pressing { pressing = true; onPress() }
                }
                .onEnded { _ in
                    pressing = false
                    onRelease()
                }
        )
    }
}

// MARK: - Shared preview bar

func previewBar(isPlaying: Bool, action: @escaping () -> Void) -> some View {
    HStack {
        Button(action: action) {
            Label(isPlaying ? "Stopp" : "Anhören", systemImage: isPlaying ? "stop.fill" : "play.fill")
        }
        .buttonStyle(.borderedProminent)
        Spacer()
    }
    .padding(.horizontal, 16)
    .padding(.vertical, 10)
    .background(.thinMaterial)
}
