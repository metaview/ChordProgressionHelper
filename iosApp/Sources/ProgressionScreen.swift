import SwiftUI
import Shared

/// Progression editor: pick a chord from the palette (it previews and becomes the "selected"
/// chord), tap a measure slot to place it, and play the whole progression back (looping).
/// iOS counterpart of Android's ProgressionActivity: chords, measures, key, tempo, loop,
/// playback, per-measure drum/strumming/solo editors, and the New/Load/Save menu.
struct ProgressionScreen: View {
    @StateObject private var model = ProgressionModel(env: IosAppEnvironment.companion.shared)
    @State private var patternSheet: PatternSheet?
    @State private var showTemplatePicker = false
    @State private var showLoadSheet = false
    @State private var showSaveSheet = false
    @State private var showSettingsSheet = false
    @State private var isChordPaletteExpanded = false

    /// Which per-measure pattern editor is open, if any.
    enum PatternSheet: Identifiable {
        case drums(Int), strum(Int), solo(Int)
        var id: String {
            switch self {
            case .drums(let i): return "drums-\(i)"
            case .strum(let i): return "strum-\(i)"
            case .solo(let i): return "solo-\(i)"
            }
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            controlBar
            Divider()
            measuresList
            Divider()
            chordPalette
        }
        .navigationTitle("Chords")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Menu {
                    Button { showSettingsSheet = true } label: {
                        Label("Settings…", systemImage: "gear")
                    }
                    Button { model.requestNewProgression() } label: {
                        Label("New Progression…", systemImage: "doc.badge.plus")
                    }
                    Button { showLoadSheet = true } label: {
                        Label("Load…", systemImage: "folder")
                    }
                    Button { showSaveSheet = true } label: {
                        Label("Save…", systemImage: "square.and.arrow.down")
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
            }
            ToolbarItem(placement: .navigationBarLeading) {
                EditButton().disabled(model.measures.count < 2)
            }
        }
        .onAppear {
            // Debug: `CPH_OPEN_EDITOR=drums|strum|solo|newprog|load|save` opens that sheet.
            switch ProcessInfo.processInfo.environment["CPH_OPEN_EDITOR"] {
            case "drums": patternSheet = .drums(0)
            case "strum": patternSheet = .strum(0)
            case "solo": patternSheet = .solo(0)
            case "newprog": showTemplatePicker = true
            case "load": showLoadSheet = true
            case "save": showSaveSheet = true
            default: break
            }
        }
        .onDisappear { model.stop() }
        .sheet(item: $patternSheet) { sheet in
            switch sheet {
            case .drums(let i): DrumPatternSheet(measureIndex: i)
            case .strum(let i): StrummingPatternSheet(measureIndex: i)
            case .solo(let i): SoloPatternSheet(measureIndex: i)
            }
        }
        .sheet(isPresented: $showTemplatePicker) {
            NewProgressionTemplateSheet(model: model)
        }
        .sheet(isPresented: $showLoadSheet) {
            LoadProgressionSheet(model: model)
        }
        .sheet(isPresented: $showSaveSheet) {
            SaveProgressionSheet(model: model)
        }
        .sheet(isPresented: $showSettingsSheet) {
            SettingsScreen()
        }
        .alert(
            "New Progression?",
            isPresented: newProgressionConfirmationBinding
        ) {
            Button("Continue") { showTemplatePicker = true }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("The current progression in this section will be replaced.")
        }
        .alert(
            "Delete Measure?",
            isPresented: deleteConfirmationBinding,
            presenting: model.deleteConfirmationMeasure
        ) { index in
            Button("Delete", role: .destructive) { model.confirmRemoveMeasure(index) }
            Button("Cancel", role: .cancel) { model.cancelRemoveMeasure() }
        } message: { _ in
            Text("This measure contains chords and will be permanently removed.")
        }
        .alert(
            "Change Key",
            isPresented: transposeConfirmationBinding,
            presenting: model.transposeConfirmationKey
        ) { newKey in
            Button("Transpose") { model.confirmTranspose(newKey, transpose: true) }
            Button("Set Key Only") { model.confirmTranspose(newKey, transpose: false) }
            Button("Cancel", role: .cancel) { model.cancelTranspose() }
        } message: { newKey in
            Text("Transpose existing chords to ") + Text(verbatim: newKey.displayName) + Text(" as well?")
        }
    }

    // MARK: - Control bar

    private var controlBar: some View {
        HStack(spacing: 16) {
            KeyPickerButton(keys: model.allKeys, selectedKey: model.key) { model.setKey($0) }
                .font(.subheadline)

            Spacer()

            HStack(spacing: 6) {
                Button { model.decrementTempo() } label: { Image(systemName: "minus.circle") }
                Text(verbatim: "\(model.tempo)")
                    .font(.subheadline.monospacedDigit())
                    .frame(minWidth: 36)
                Button { model.incrementTempo() } label: { Image(systemName: "plus.circle") }
            }

            Button { model.toggleLooping() } label: {
                Image(systemName: "repeat")
                    .foregroundStyle(model.isLooping ? Color.accentColor : Color.secondary)
            }

            Button { model.togglePlayback() } label: {
                Image(systemName: model.isPlaying ? "stop.fill" : "play.fill")
                    .font(.title2)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
    }

    // MARK: - Measures

    private var measuresList: some View {
        List {
            ForEach(Array(model.measures.enumerated()), id: \.element.id) { index, measure in
                measureRow(index: index, measure: measure)
                    .listRowInsets(EdgeInsets(top: 4, leading: 16, bottom: 4, trailing: 16))
                    .listRowSeparator(.hidden)
                    .listRowBackground(Color.clear)
            }
            .onMove { source, destination in
                model.moveMeasure(from: source, to: destination)
            }

            Button(action: model.addMeasure) {
                Label("Add Measure", systemImage: "plus")
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 10)
            }
            .buttonStyle(.bordered)
            .listRowInsets(EdgeInsets(top: 4, leading: 16, bottom: 16, trailing: 16))
            .listRowSeparator(.hidden)
            .listRowBackground(Color.clear)
        }
        .listStyle(.plain)
    }

    private func measureRow(index: Int, measure: Measure) -> some View {
        VStack(spacing: 6) {
            HStack(spacing: 6) {
                Text(verbatim: "\(index + 1)")
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
                    .frame(width: 20)

                ForEach(0..<4, id: \.self) { quarter in
                    chordSlot(measureIndex: index, quarter: quarter, measure: measure)
                }

                Menu {
                    Button { model.duplicateMeasure(index) } label: {
                        Label("Duplicate", systemImage: "plus.square.on.square")
                    }
                    Button { model.clearMeasureChords(index) } label: {
                        Label("Clear Chords", systemImage: "eraser")
                    }
                    Button(role: .destructive) { model.requestRemoveMeasure(index) } label: {
                        Label("Delete Measure", systemImage: "trash")
                    }
                } label: {
                    Image(systemName: "ellipsis.circle").foregroundStyle(.secondary)
                }
            }

            HStack(spacing: 6) {
                patternButton("Drums", systemImage: "circle.grid.3x3.fill", detail: measure.drumPattern.name, showTitle: false) {
                    patternSheet = .drums(index)
                }
                // Arrow signature (↓↑✕→·, one per strum) so the actual pattern for this measure
                // is visible at a glance, not just its (often generic) preset name.
                patternButton("Strum", systemImage: "guitars.fill", detail: PatternDisplay.strumSignature(measure.strummingPattern), showTitle: false) {
                    patternSheet = .strum(index)
                }
                patternButton("Solo", systemImage: "pianokeys", detail: nil) {
                    patternSheet = .solo(index)
                }
            }
        }
        .padding(8)
        .background(
            RoundedRectangle(cornerRadius: 8)
                .fill(model.currentMeasureIndex == index ? Color.accentColor.opacity(0.18) : Color(.secondarySystemBackground))
        )
    }

    private func patternButton(_ title: LocalizedStringKey, systemImage: String, detail: String?, showTitle: Bool = true, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 2) {
                Label(title, systemImage: systemImage)
                    .font(.caption2)
                    .labelStyle(.iconOnly)
                if showTitle {
                    Text(title).font(.caption2)
                }
                if let detail {
                    Text(detail)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 6)
            .background(RoundedRectangle(cornerRadius: 6).fill(Color(.tertiarySystemBackground)))
        }
        .buttonStyle(.plain)
    }

    private func chordSlot(measureIndex: Int, quarter: Int, measure: Measure) -> some View {
        let chord = model.chord(inMeasure: measure, quarterNote: quarter)
        return Button {
            model.addSelectedChord(measureIndex: measureIndex, quarterNote: quarter)
        } label: {
            Text(chord?.getDisplayName() ?? "·")
                .font(.subheadline)
                .frame(maxWidth: .infinity, minHeight: 40)
                .background(
                    RoundedRectangle(cornerRadius: 6)
                        .fill(chord != nil ? Color.accentColor.opacity(0.25) : Color(.tertiarySystemBackground))
                )
        }
        .buttonStyle(.plain)
    }

    // MARK: - Chord palette

    private var chordPalette: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button {
                withAnimation { isChordPaletteExpanded.toggle() }
            } label: {
                HStack {
                    Text("More Chords")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Image(systemName: isChordPaletteExpanded ? "chevron.up" : "chevron.down")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                .padding(.horizontal, 16)
                .padding(.top, 10)
            }
            .buttonStyle(.plain)

            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    paletteRow("Scale", chords: model.scaleDegreeChords)
                    if isChordPaletteExpanded {
                        paletteRow("Related (V7/…)", chords: model.relatedChords)
                        paletteRow("Borrowed (Minor)", chords: model.borrowedMinorChords)
                        paletteRow("Borrowed (Major)", chords: model.borrowedMajorChords)
                    }
                }
                .padding(16)
            }
            .frame(maxHeight: isChordPaletteExpanded ? 340 : 130)
        }
        .background(.thinMaterial)
    }

    @ViewBuilder
    private func paletteRow(_ title: LocalizedStringKey, chords: [Chord]) -> some View {
        if !chords.isEmpty {
            VStack(alignment: .leading, spacing: 6) {
                Text(title)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(Array(chords.enumerated()), id: \.offset) { _, chord in
                            ChordButton(
                                displayChord: model.isSelectedAsPower(chord) ? (model.selectedChord ?? chord) : chord,
                                isSelected: model.isSelected(chord) || model.isSelectedAsPower(chord),
                                onPress: { model.pressChord(chord) },
                                onRelease: { model.releaseChord() },
                                onMakePower: { model.makePowerChord(chord) }
                            )
                        }
                    }
                }
            }
        }
    }

    // MARK: - Alert bindings

    private var deleteConfirmationBinding: Binding<Bool> {
        Binding(
            get: { model.deleteConfirmationMeasure != nil },
            set: { if !$0 { model.cancelRemoveMeasure() } }
        )
    }

    private var transposeConfirmationBinding: Binding<Bool> {
        Binding(
            get: { model.transposeConfirmationKey != nil },
            set: { if !$0 { model.cancelTranspose() } }
        )
    }

    private var newProgressionConfirmationBinding: Binding<Bool> {
        Binding(
            get: { model.showNewProgressionConfirmation },
            set: { if !$0 { model.cancelNewProgression() } }
        )
    }
}

/// A chord in the palette. A tap (complete touch-down + release at the same spot) plays a
/// preview and selects the chord. Long-press (0.5s) stops and offers to turn it into a Power
/// chord (e.g. G -> G5), mirroring Android's long-press popup. Scroll gestures on the enclosing
/// horizontal ScrollView don't trigger any preview (the tap gesture auto-fails when the touch
/// moves too far).
private struct ChordButton: View {
    let displayChord: Chord
    let isSelected: Bool
    let onPress: () -> Void
    let onRelease: () -> Void
    let onMakePower: () -> Void

    @State private var longPressFired = false
    @State private var showPowerMenu = false

    var body: some View {
        VStack(spacing: 2) {
            Text(displayChord.getDisplayName())
                .font(.subheadline.weight(.medium))
            if let roman = displayChord.getRomanNumeral(), !roman.isEmpty {
                Text(roman)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .frame(minWidth: 56)
        .background(
            RoundedRectangle(cornerRadius: 8)
                .fill(isSelected ? Color.accentColor.opacity(0.3) : Color(.tertiarySystemBackground))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 8)
                .stroke(isSelected ? Color.accentColor : Color.clear, lineWidth: 2)
        )
        // onTapGesture fires only when the touch completes (release) at the same spot as
        // touch-down. If the touch moves > ~10pt during that time, the tap gesture fails and
        // never fires — that's the escape hatch that lets the ScrollView recognize its own pan
        // gesture. No preview blips on scroll, no need for debouncing or movement tracking.
        //
        // Start the preview on tap, but delay the release (~200ms) so the tone can ring out
        // instead of fading immediately. This mirrors a brief hold before releasing.
        .onTapGesture {
            onPress()
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
                onRelease()
            }
        }
        // Long-press for the Power-chord menu. Simultaneous with the tap gesture — both can
        // recognize, and they don't interfere since the tap only succeeds if long-press *didn't*
        // fire first (the 0.5s minimum).
        .onLongPressGesture(minimumDuration: 0.5, maximumDistance: 10) {
            longPressFired = true
            onRelease()  // stop the preview before opening the menu, like Android
            showPowerMenu = true
        }
        .confirmationDialog("", isPresented: $showPowerMenu, titleVisibility: .hidden) {
            Button {
                onMakePower()
            } label: {
                Text("Power Chord (") + Text(verbatim: "\(displayChord.root.displayName)5") + Text(")")
            }
        }
        .onChange(of: showPowerMenu) { isShowing in
            if !isShowing {
                longPressFired = false
            }
        }
    }
}
