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
        .navigationTitle("Akkorde")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Menu {
                    Button { model.requestNewProgression() } label: {
                        Label("Neue Progression…", systemImage: "doc.badge.plus")
                    }
                    Button { showLoadSheet = true } label: {
                        Label("Laden…", systemImage: "folder")
                    }
                    Button { showSaveSheet = true } label: {
                        Label("Speichern…", systemImage: "square.and.arrow.down")
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
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
        .alert(
            "Neue Progression?",
            isPresented: newProgressionConfirmationBinding
        ) {
            Button("Weiter") { showTemplatePicker = true }
            Button("Abbrechen", role: .cancel) {}
        } message: {
            Text("Die aktuelle Progression in diesem Abschnitt wird ersetzt.")
        }
        .alert(
            "Takt löschen?",
            isPresented: deleteConfirmationBinding,
            presenting: model.deleteConfirmationMeasure
        ) { index in
            Button("Löschen", role: .destructive) { model.confirmRemoveMeasure(index) }
            Button("Abbrechen", role: .cancel) { model.cancelRemoveMeasure() }
        } message: { _ in
            Text("Dieser Takt enthält Akkorde und wird endgültig entfernt.")
        }
        .alert(
            "Tonart ändern",
            isPresented: transposeConfirmationBinding,
            presenting: model.transposeConfirmationKey
        ) { newKey in
            Button("Transponieren") { model.confirmTranspose(newKey, transpose: true) }
            Button("Nur Tonart setzen") { model.confirmTranspose(newKey, transpose: false) }
            Button("Abbrechen", role: .cancel) { model.cancelTranspose() }
        } message: { newKey in
            Text("Bestehende Akkorde nach \(newKey.displayName) transponieren?")
        }
    }

    // MARK: - Control bar

    private var controlBar: some View {
        HStack(spacing: 16) {
            Menu {
                ForEach(model.allKeys, id: \.ordinal) { k in
                    Button(k.displayName) { model.setKey(k) }
                }
            } label: {
                Label(model.key.displayName, systemImage: "key")
                    .font(.subheadline)
            }

            Spacer()

            HStack(spacing: 6) {
                Button { model.decrementTempo() } label: { Image(systemName: "minus.circle") }
                Text("\(model.tempo)")
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
        ScrollView {
            LazyVStack(spacing: 8) {
                ForEach(Array(model.measures.enumerated()), id: \.element.id) { index, measure in
                    measureRow(index: index, measure: measure)
                }

                Button(action: model.addMeasure) {
                    Label("Takt hinzufügen", systemImage: "plus")
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                }
                .buttonStyle(.bordered)
                .padding(.top, 4)
            }
            .padding(16)
        }
    }

    private func measureRow(index: Int, measure: Measure) -> some View {
        VStack(spacing: 6) {
            HStack(spacing: 6) {
                Text("\(index + 1)")
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
                    .frame(width: 20)

                ForEach(0..<4, id: \.self) { quarter in
                    chordSlot(measureIndex: index, quarter: quarter, measure: measure)
                }

                Button { model.requestRemoveMeasure(index) } label: {
                    Image(systemName: "trash").foregroundStyle(.secondary)
                }
                .buttonStyle(.borderless)
            }

            HStack(spacing: 6) {
                patternButton("Drums", systemImage: "circle.grid.3x3.fill", detail: measure.drumPattern.name) {
                    patternSheet = .drums(index)
                }
                patternButton("Anschlag", systemImage: "guitars.fill", detail: measure.strummingPattern.name) {
                    patternSheet = .strum(index)
                }
                patternButton("Solo", systemImage: "pianokeys", detail: soloDetail(measure.soloPattern)) {
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

    private func soloDetail(_ pattern: SoloPattern) -> String {
        pattern.isEmpty() ? "–" : "\(pattern.elements.count)"
    }

    private func patternButton(_ title: String, systemImage: String, detail: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 2) {
                Label(title, systemImage: systemImage)
                    .font(.caption2)
                    .labelStyle(.iconOnly)
                Text(title).font(.caption2)
                Text(detail)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
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
                    Text("Weitere Akkorde")
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
                    paletteRow("Tonleiter", chords: model.scaleDegreeChords)
                    if isChordPaletteExpanded {
                        paletteRow("Verwandte (V7/…)", chords: model.relatedChords)
                        paletteRow("Geliehen (Moll)", chords: model.borrowedMinorChords)
                        paletteRow("Geliehen (Dur)", chords: model.borrowedMajorChords)
                    }
                }
                .padding(16)
            }
            .frame(maxHeight: isChordPaletteExpanded ? 340 : 130)
        }
        .background(.thinMaterial)
    }

    @ViewBuilder
    private func paletteRow(_ title: String, chords: [Chord]) -> some View {
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

/// A chord in the palette. Touch-down starts a sustained audio preview and selects the chord;
/// lift lets it ring out. Mirrors Android's key-down/key-up preview behavior. Long-press stops
/// the preview and offers to turn it into a Power chord (e.g. G -> G5), mirroring Android's
/// long-press popup.
private struct ChordButton: View {
    let displayChord: Chord
    let isSelected: Bool
    let onPress: () -> Void
    let onRelease: () -> Void
    let onMakePower: () -> Void

    @State private var isPressing = false
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
        // DragGesture(minimumDistance: 0) fires on touch-down (onChanged) and lift (onEnded),
        // giving us key-down/key-up semantics for the sustained preview. A simultaneous
        // LongPressGesture recognizes alongside it (SwiftUI doesn't cancel one for the other) to
        // add Android's long-press-for-Power-chord behavior without disturbing the preview.
        .gesture(
            DragGesture(minimumDistance: 0)
                .onChanged { _ in
                    if !isPressing {
                        isPressing = true
                        longPressFired = false
                        onPress()
                    }
                }
                .onEnded { _ in
                    isPressing = false
                    if !longPressFired {
                        onRelease()
                    }
                }
        )
        .simultaneousGesture(
            LongPressGesture(minimumDuration: 0.5)
                .onEnded { _ in
                    longPressFired = true
                    onRelease()  // stop the preview before opening the menu, like Android
                    showPowerMenu = true
                }
        )
        .confirmationDialog("", isPresented: $showPowerMenu, titleVisibility: .hidden) {
            Button("Power-Chord (\(displayChord.root.displayName)5)") { onMakePower() }
        }
        .onChange(of: showPowerMenu) { isShowing in
            if !isShowing {
                isPressing = false
                longPressFired = false
            }
        }
    }
}
