import SwiftUI
import Shared

/// iOS counterpart of Android's SongActivity: section list plus playback bar
/// (play/stop, practice-speed percent, repeat).
struct SongScreen: View {
    @ObservedObject var model: SongModel

    @State private var showTrackSelection = false
    @State private var showExporter = false
    @State private var exportDocument: MidiDocument?
    @State private var exportFilename = "song"
    @State private var showAddSection = false
    // Debug shortcut: `CPH_OPEN_EDITOR=1` jumps straight into the progression editor on launch
    // so the per-measure editors can be inspected without UI automation.
    @State private var showEditor = false

    var body: some View {
        AppNavigationContainer {
            List {
                ForEach(Array(model.sectionNames.enumerated()), id: \.offset) { index, name in
                    Button {
                        // Make this section the current one in the shared song, then open the
                        // editor (ProgressionScreen edits session.currentProgression).
                        model.selectSection(index)
                        showEditor = true
                    } label: {
                        HStack {
                            Text(name)
                            Spacer()
                            if index == model.selectedIndex {
                                Image(systemName: "checkmark")
                            }
                            Image(systemName: "chevron.right")
                                .font(.caption)
                                .foregroundStyle(.tertiary)
                        }
                    }
                    .foregroundStyle(.primary)
                }
                .onMove { source, destination in
                    model.moveSection(from: source, to: destination)
                }
            }
            .background(
                NavigationLink(destination: ProgressionScreen(), isActive: $showEditor) {
                    EmptyView()
                }
                .hidden()
            )
            .navigationTitle(model.songName)
            .onAppear {
                // Debug shortcut: `CPH_OPEN_EDITOR=1` jumps straight into the progression editor
                // so the per-measure pattern editors can be inspected without UI automation.
                if ProcessInfo.processInfo.environment["CPH_OPEN_EDITOR"] != nil && !showEditor {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
                        model.selectSection(0)
                        showEditor = true
                    }
                }
            }
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        Button {
                            showTrackSelection = true
                        } label: {
                            Label("Export as MIDI", systemImage: "square.and.arrow.up")
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                }
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        showAddSection = true
                    } label: {
                        Image(systemName: "plus")
                    }
                }
                ToolbarItem(placement: .navigationBarLeading) {
                    EditButton().disabled(model.sectionNames.count < 2)
                }
            }
            .safeAreaInset(edge: .bottom) {
                playbackBar
            }
            .sheet(isPresented: $showAddSection) {
                AddSectionSheet(suggestedName: "Section \(model.sectionNames.count + 1)") { name in
                    model.addSection(name: name)
                }
            }
            .sheet(isPresented: $showTrackSelection) {
                MidiTrackSelectionSheet { tracks in
                    exportFilename = model.songFilenameBase
                    exportDocument = MidiDocument(data: model.exportMidiData(tracks: tracks))
                    // Let the selection sheet finish dismissing before presenting the file
                    // exporter, otherwise SwiftUI can drop the second presentation.
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
                        showExporter = true
                    }
                }
            }
            .fileExporter(
                isPresented: $showExporter,
                document: exportDocument,
                contentType: .midi,
                defaultFilename: exportFilename
            ) { _ in
                exportDocument = nil
            }
        }
    }

    /// Prompts for a new section's name, prefilled with the next default ("Section N") so
    /// accepting as-is works like a plain "add" — matching Android's addSongSection default.
    private struct AddSectionSheet: View {
        @Environment(\.dismiss) private var dismiss
        @State private var name: String
        let onAdd: (String) -> Void

        init(suggestedName: String, onAdd: @escaping (String) -> Void) {
            _name = State(initialValue: suggestedName)
            self.onAdd = onAdd
        }

        var body: some View {
            NavigationView {
                Form {
                    TextField("Name", text: $name)
                        .autocorrectionDisabled()
                }
                .navigationTitle("Neue Section")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Abbrechen") { dismiss() }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Hinzufügen") {
                            onAdd(name)
                            dismiss()
                        }
                    }
                }
            }
        }
    }

    private var playbackBar: some View {
        HStack(spacing: 16) {
            Button {
                model.togglePlayback()
            } label: {
                Image(systemName: model.isPlaying ? "pause.fill" : "play.fill")
                    .font(.title)
            }

            Button {
                model.stop()
            } label: {
                Image(systemName: "stop.fill")
                    .font(.title)
            }
            .disabled(!model.isPlaying)

            Spacer()

            // Practice speed (percent of each section's own BPM)
            HStack(spacing: 4) {
                RepeatingButton(systemImage: "chevron.down") {
                    model.decrementTempoPercent()
                }
                Text("\(model.tempoPercent)%")
                    .font(.subheadline.monospacedDigit())
                    .frame(minWidth: 48)
                RepeatingButton(systemImage: "chevron.up") {
                    model.incrementTempoPercent()
                }
            }

            Spacer()

            Button {
                model.toggleLooping()
            } label: {
                Image(systemName: "repeat")
                    .font(.title2)
                    .foregroundStyle(model.isLooping ? Color.accentColor : Color.secondary)
            }
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 12)
        .background(.thinMaterial)
    }
}

/// Button matching the Android arrows' behavior: one step per tap, and after holding
/// for 1s the step repeats rapidly until release.
struct RepeatingButton: View {
    let systemImage: String
    let onStep: () -> Void

    @State private var repeatTimer: Timer?

    var body: some View {
        Image(systemName: systemImage)
            .font(.title3)
            .frame(width: 32, height: 32)
            .contentShape(Rectangle())
            .onTapGesture {
                onStep()
            }
            .onLongPressGesture(minimumDuration: 1.0, perform: {
                repeatTimer = Timer.scheduledTimer(withTimeInterval: 0.06, repeats: true) { _ in
                    onStep()
                }
            }, onPressingChanged: { pressing in
                if !pressing {
                    repeatTimer?.invalidate()
                    repeatTimer = nil
                }
            })
    }
}
