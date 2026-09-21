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
                    presetSection("Used", model.usedPatterns)
                    presetSection("More", model.defaultPatterns)
                }
                .padding(16)
            }
            .navigationTitle("Drums")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { model.stopPreview(); dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { model.save(); model.stopPreview(); dismiss() }
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

    private func laneRow(name: LocalizedStringKey, isOn: @escaping (DrumStep) -> Bool, toggle: @escaping (Int) -> Void) -> some View {
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
    private func presetSection(_ title: LocalizedStringKey, _ patterns: [DrumPattern]) -> some View {
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
                    presetSection("Used", model.usedPatterns)
                    presetSection("More", model.defaultPatterns)
                }
                .padding(16)
            }
            .navigationTitle("Strum")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { model.stopPreview(); dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { model.save(); model.stopPreview(); dismiss() }
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
            legendRow("→", "Let Ring")
            legendRow("·", "Pause")
        }
        .font(.caption)
        .foregroundStyle(.secondary)
    }

    private func legendRow(_ symbol: String, _ text: LocalizedStringKey) -> some View {
        HStack(spacing: 8) {
            Text(symbol).frame(width: 16)
            Text(text)
        }
    }

    @ViewBuilder
    private func presetSection(_ title: LocalizedStringKey, _ patterns: [StrummingPattern]) -> some View {
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

    // Mirrors Android's keyboard span: B (below the octave) up to D (an octave + a third above).
    private static let keys: [PianoKeySpec] = [
        PianoKeySpec(label: "B", pitchClass: 11, octaveOffset: -1, isBlack: false),
        PianoKeySpec(label: "C", pitchClass: 0, octaveOffset: 0, isBlack: false),
        PianoKeySpec(label: "C♯", pitchClass: 1, octaveOffset: 0, isBlack: true),
        PianoKeySpec(label: "D", pitchClass: 2, octaveOffset: 0, isBlack: false),
        PianoKeySpec(label: "D♯", pitchClass: 3, octaveOffset: 0, isBlack: true),
        PianoKeySpec(label: "E", pitchClass: 4, octaveOffset: 0, isBlack: false),
        PianoKeySpec(label: "F", pitchClass: 5, octaveOffset: 0, isBlack: false),
        PianoKeySpec(label: "F♯", pitchClass: 6, octaveOffset: 0, isBlack: true),
        PianoKeySpec(label: "G", pitchClass: 7, octaveOffset: 0, isBlack: false),
        PianoKeySpec(label: "G♯", pitchClass: 8, octaveOffset: 0, isBlack: true),
        PianoKeySpec(label: "A", pitchClass: 9, octaveOffset: 0, isBlack: false),
        PianoKeySpec(label: "A♯", pitchClass: 10, octaveOffset: 0, isBlack: true),
        PianoKeySpec(label: "B", pitchClass: 11, octaveOffset: 0, isBlack: false),
        PianoKeySpec(label: "C", pitchClass: 0, octaveOffset: 1, isBlack: false),
        PianoKeySpec(label: "C♯", pitchClass: 1, octaveOffset: 1, isBlack: true),
        PianoKeySpec(label: "D", pitchClass: 2, octaveOffset: 1, isBlack: false),
    ]

    var body: some View {
        NavigationView {
            VStack(spacing: 0) {
                modeBar
                Divider()
                ScrollViewReader { proxy in
                    ScrollView {
                        VStack(spacing: 8) {
                            ForEach(0..<model.measureCount, id: \.self) { measure in
                                measureCard(measure)
                                    .id(measure)
                            }
                        }
                        .padding(16)
                    }
                    // Test-playback loops through measures; keep the sounding slot on screen.
                    // Deferred to the next run loop turn (and unanimated) so this scroll never
                    // lands inside the same SwiftUI transaction as the FlowWatch-driven state
                    // update — doing it synchronously froze the whole sheet's hit-testing
                    // (Listen/Stop stopped responding) once measures started advancing.
                    .onChange(of: model.playingMeasure) { measure in
                        guard model.isPreviewing, measure >= 0 else { return }
                        DispatchQueue.main.async {
                            proxy.scrollTo(measure, anchor: .center)
                        }
                    }
                }
                Divider()
                controls
                keyboard
            }
            .navigationTitle("Solo")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { model.stopPreview(); dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { model.save(); model.stopPreview(); dismiss() }
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
                Label(model.isPreviewing ? "Stop" : "Listen", systemImage: model.isPreviewing ? "stop.fill" : "play.fill")
                    .font(.subheadline)
            }
            .buttonStyle(.borderedProminent)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
    }

    private var modeName: LocalizedStringKey {
        if model.editMode == SoloEditMode.edit { return "Edit" }
        if model.editMode == SoloEditMode.live { return "Live" }
        return "Preview"
    }

    private var modeIcon: String {
        if model.editMode == SoloEditMode.edit { return "pencil" }
        if model.editMode == SoloEditMode.live { return "record.circle" }
        return "eye"
    }

    private func measureCard(_ measure: Int) -> some View {
        let isActive = measure == model.activeMeasure
        return VStack(spacing: 2) {
            // Chord row: each chord shown above the slot where it starts, column-aligned with
            // the note slots below (mirrors Android's SoloPatternActivity chordRow).
            HStack(spacing: 4) {
                Color.clear.frame(width: 18)
                ForEach(0..<8, id: \.self) { slotIndex in
                    Text(model.chordLabel(measure: measure, slot: slotIndex) ?? "")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                        .frame(maxWidth: .infinity)
                }
            }
            HStack(spacing: 4) {
                Text(verbatim: "\(measure + 1)")
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
                    .frame(width: 18)
                ForEach(Array(model.slots(measure).enumerated()), id: \.offset) { slotIndex, slot in
                    let selected = isActive && slotIndex == model.cursor
                    // Preview loops the whole progression, so the sounding measure moves
                    // independently of isActive (the editing cursor's measure).
                    let playing = measure == model.playingMeasure && model.isPreviewing && slotIndex == model.playingSlot
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
                                    .fill(slotFill(slot: slot, selected: selected, playing: playing))
                            )
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .padding(6)
        .background(
            RoundedRectangle(cornerRadius: 8)
                .stroke(isActive ? Color.accentColor : Color.clear, lineWidth: 2)
                .background(RoundedRectangle(cornerRadius: 8).fill(Color(.secondarySystemBackground)))
        )
    }

    private func slotFill(slot: SoloSlot, selected: Bool, playing: Bool) -> Color {
        // Playing takes priority so the moving highlight is visible even on the selected slot.
        if playing { return Color.green.opacity(0.55) }
        if selected { return Color.accentColor.opacity(0.35) }
        if PatternDisplay.isNote(slot) { return Color.accentColor.opacity(0.18) }
        return Color(.tertiarySystemBackground)
    }

    private var controls: some View {
        VStack(spacing: 12) {
            HStack(spacing: 12) {
                HStack(spacing: 4) {
                    Button { model.octaveDown() } label: { Image(systemName: "minus.circle") }
                    (Text("Oct. ") + Text(verbatim: "\(model.octave)")).font(.subheadline.monospacedDigit())
                    Button { model.octaveUp() } label: { Image(systemName: "plus.circle") }
                }
                Spacer()
                Button { model.setRest() } label: { Text("Rest") }
                    .buttonStyle(.bordered)
                Button { model.setLetRing() } label: { Text("Hold") }
                    .buttonStyle(.bordered)
            }
            soundPresetPicker("Sound", selection: $model.soloPreset)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
    }

    private func soundPresetPicker(_ title: LocalizedStringKey, selection: Binding<SoundPreset>) -> some View {
        let ordinalBinding = Binding<Int32>(
            get: { selection.wrappedValue.ordinal },
            set: { newOrdinal in
                if let preset = SoundPreset.entries.first(where: { $0.ordinal == newOrdinal }) {
                    selection.wrappedValue = preset
                }
            }
        )
        return Picker(title, selection: ordinalBinding) {
            Text("Clean").tag(SoundPreset.clean.ordinal)
            Text("Overdrive").tag(SoundPreset.overdrive.ordinal)
            Text("Piano").tag(SoundPreset.piano.ordinal)
        }
        .pickerStyle(.segmented)
    }

    // All white keys sized to fill the available width so the whole B–D span (mirrors Android's
    // equal-weight white key row) is visible at once, with black keys overlaid at the boundaries
    // they sit on — no horizontal scrolling needed.
    private var keyboard: some View {
        let scale = model.scalePitchClasses()
        // While previewing, track the chord under the playhead (not the stationary edit cursor)
        // so the green root dot moves along with playback.
        let rootPc = model.isPreviewing && model.playingMeasure >= 0 && model.playingSlot >= 0
            ? model.rootPitchClass(measure: model.playingMeasure, slot: model.playingSlot)
            : model.rootPitchClass(measure: model.activeMeasure, slot: model.cursor)
        let whiteKeys = Self.keys.filter { !$0.isBlack }
        var whiteIndex = 0
        let blackKeys: [(key: PianoKeySpec, boundary: Int)] = Self.keys.compactMap { key in
            if key.isBlack { return (key, whiteIndex) }
            whiteIndex += 1
            return nil
        }

        return GeometryReader { geo in
            let whiteWidth = geo.size.width / CGFloat(whiteKeys.count)
            let blackWidth = whiteWidth * 0.62
            let blackHeight = geo.size.height * 0.6

            ZStack(alignment: .topLeading) {
                HStack(spacing: 0) {
                    ForEach(whiteKeys) { key in
                        PianoKey(
                            label: key.label,
                            isBlack: false,
                            dot: dotColor(pitchClass: key.pitchClass, rootPc: rootPc, scale: scale),
                            width: whiteWidth,
                            height: geo.size.height,
                            onPress: { model.pressKey(pitchClass: key.pitchClass, octaveOffset: key.octaveOffset) },
                            onRelease: { model.releaseKey() }
                        )
                    }
                }
                ForEach(blackKeys, id: \.key.id) { entry in
                    PianoKey(
                        label: entry.key.label,
                        isBlack: true,
                        dot: dotColor(pitchClass: entry.key.pitchClass, rootPc: rootPc, scale: scale),
                        width: blackWidth,
                        height: blackHeight,
                        onPress: { model.pressKey(pitchClass: entry.key.pitchClass, octaveOffset: entry.key.octaveOffset) },
                        onRelease: { model.releaseKey() }
                    )
                    .offset(x: whiteWidth * CGFloat(entry.boundary) - blackWidth / 2)
                }
            }
        }
        .frame(height: 120)
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(.thinMaterial)
    }

    private func dotColor(pitchClass: Int, rootPc: Int, scale: Set<Int>) -> Color? {
        if pitchClass == rootPc { return .green }
        if scale.contains(pitchClass) { return .orange }
        return nil
    }
}

private struct PianoKeySpec: Identifiable {
    let label: String
    let pitchClass: Int
    let octaveOffset: Int
    let isBlack: Bool

    /// Unique within a single keyboard span: only one key per (pitchClass, octaveOffset) pair.
    var id: String { "\(pitchClass)_\(octaveOffset)" }
}

private struct PianoKey: View {
    let label: String
    let isBlack: Bool
    let dot: Color?
    let width: CGFloat
    let height: CGFloat
    let onPress: () -> Void
    let onRelease: () -> Void

    @State private var pressing = false

    var body: some View {
        VStack(spacing: 4) {
            Spacer()
            if let dot { Circle().fill(dot).frame(width: 8, height: 8) }
            Text(label).font(.caption2)
        }
        .frame(width: width, height: height)
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
                    // Delay release so the tone can ring out instead of fading immediately
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
                        onRelease()
                    }
                }
        )
    }
}

// MARK: - Shared preview bar

func previewBar(isPlaying: Bool, action: @escaping () -> Void) -> some View {
    HStack {
        Button(action: action) {
            Label(isPlaying ? "Stop" : "Listen", systemImage: isPlaying ? "stop.fill" : "play.fill")
        }
        .buttonStyle(.borderedProminent)
        Spacer()
    }
    .padding(.horizontal, 16)
    .padding(.vertical, 10)
    .background(.thinMaterial)
}
