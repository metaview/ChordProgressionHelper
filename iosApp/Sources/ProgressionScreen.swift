import SwiftUI
import Shared

/// Progression editor: pick a chord from the palette (it previews and becomes the "selected"
/// chord), tap a measure slot to place it, and play the whole progression back (looping).
/// iOS counterpart of Android's ProgressionActivity — first pass: chords, measures, key,
/// tempo, loop, playback. Drum/solo/strumming patterns and templates/save-load come later.
struct ProgressionScreen: View {
    @StateObject private var model = ProgressionModel(env: IosAppEnvironment.companion.shared)

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
        .onDisappear { model.stop() }
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
        .padding(8)
        .background(
            RoundedRectangle(cornerRadius: 8)
                .fill(model.currentMeasureIndex == index ? Color.accentColor.opacity(0.18) : Color(.secondarySystemBackground))
        )
    }

    private func chordSlot(measureIndex: Int, quarter: Int, measure: Measure) -> some View {
        let chord = model.chord(inMeasure: measure, quarterNote: quarter)
        return Button {
            if chord != nil {
                model.removeChord(measureIndex: measureIndex, quarterNote: quarter)
            } else {
                model.addSelectedChord(measureIndex: measureIndex, quarterNote: quarter)
            }
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
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                paletteRow("Tonleiter", chords: model.scaleDegreeChords)
                paletteRow("Verwandte (V7/…)", chords: model.relatedChords)
                paletteRow("Geliehen (Moll)", chords: model.borrowedMinorChords)
                paletteRow("Geliehen (Dur)", chords: model.borrowedMajorChords)
            }
            .padding(16)
        }
        .frame(maxHeight: 260)
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
                                chord: chord,
                                isSelected: model.isSelected(chord),
                                onPress: { model.pressChord(chord) },
                                onRelease: { model.releaseChord() }
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
}

/// A chord in the palette. Touch-down starts a sustained audio preview and selects the chord;
/// lift lets it ring out. Mirrors Android's key-down/key-up preview behavior.
private struct ChordButton: View {
    let chord: Chord
    let isSelected: Bool
    let onPress: () -> Void
    let onRelease: () -> Void

    @State private var isPressing = false

    var body: some View {
        VStack(spacing: 2) {
            Text(chord.getDisplayName())
                .font(.subheadline.weight(.medium))
            if let roman = chord.getRomanNumeral(), !roman.isEmpty {
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
        // giving us key-down/key-up semantics for the sustained preview.
        .gesture(
            DragGesture(minimumDistance: 0)
                .onChanged { _ in
                    if !isPressing {
                        isPressing = true
                        onPress()
                    }
                }
                .onEnded { _ in
                    isPressing = false
                    onRelease()
                }
        )
    }
}
