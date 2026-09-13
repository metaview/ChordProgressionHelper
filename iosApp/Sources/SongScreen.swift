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
    @State private var showRenameSection = false
    @State private var renameIndex = 0
    @State private var renameSuggestedName = ""
    @State private var showNewSongConfirmation = false
    @State private var showLoadSong = false
    @State private var showSaveSong = false
    @State private var showSettings = false
    // Debug shortcut: `CPH_OPEN_EDITOR=1` jumps straight into the progression editor on launch
    // so the per-measure editors can be inspected without UI automation.
    @State private var showEditor = false

    var body: some View {
        AppNavigationContainer {
            List {
                ForEach(Array(model.sectionNames.enumerated()), id: \.offset) { index, name in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Button {
                                // Make this section the current one in the shared song, then open the
                                // editor (ProgressionScreen edits session.currentProgression).
                                model.selectSection(index)
                                showEditor = true
                            } label: {
                                HStack {
                                    Text(name)
                                    Spacer()
                                    Image(systemName: "chevron.right")
                                        .font(.caption)
                                        .foregroundStyle(.tertiary)
                                }
                            }
                            .foregroundStyle(.primary)

                            Menu {
                                Button {
                                    renameIndex = index
                                    renameSuggestedName = name
                                    showRenameSection = true
                                } label: {
                                    Label("Rename", systemImage: "pencil")
                                }
                                Button {
                                    model.duplicateSection(index)
                                } label: {
                                    Label("Duplicate", systemImage: "plus.square.on.square")
                                }
                                Button(role: .destructive) {
                                    model.deleteSection(index)
                                } label: {
                                    Label("Delete", systemImage: "trash")
                                }
                                Divider()
                                Button {
                                    model.moveSection(index, to: index - 1)
                                } label: {
                                    Label("Move Up", systemImage: "arrow.up")
                                }
                                .disabled(index == 0)
                                Button {
                                    model.moveSection(index, to: index + 1)
                                } label: {
                                    Label("Move Down", systemImage: "arrow.down")
                                }
                                .disabled(index == model.sectionNames.count - 1)
                            } label: {
                                Image(systemName: "ellipsis.circle")
                                    .foregroundStyle(.secondary)
                            }
                            .buttonStyle(.borderless)
                        }
                        chordTrack(sectionIndex: index)
                    }
                    .listRowBackground(
                        index == model.playingSectionIndex ? Color.accentColor.opacity(0.12) : nil
                    )
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
                // Debug: `CPH_OPEN_EDITOR=1|settings|loadsong|savesong` jumps straight to that
                // screen on launch so it can be inspected without UI automation.
                switch ProcessInfo.processInfo.environment["CPH_OPEN_EDITOR"] {
                case "1":
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
                        model.selectSection(0)
                        showEditor = true
                    }
                case "settings":
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { showSettings = true }
                case "loadsong":
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { showLoadSong = true }
                case "savesong":
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { showSaveSong = true }
                default: break
                }
            }
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        Button {
                            showNewSongConfirmation = true
                        } label: {
                            Label("New Song…", systemImage: "doc.badge.plus")
                        }
                        Button {
                            showLoadSong = true
                        } label: {
                            Label("Load Song…", systemImage: "folder")
                        }
                        Button {
                            showSaveSong = true
                        } label: {
                            Label("Save Song…", systemImage: "square.and.arrow.down")
                        }
                        Button {
                            showSettings = true
                        } label: {
                            Label("Settings", systemImage: "gearshape")
                        }
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
                SectionNameSheet(
                    title: "New Section",
                    confirmLabel: "Add",
                    suggestedName: "Section \(model.sectionNames.count + 1)"
                ) { name in
                    model.addSection(name: name)
                }
            }
            .sheet(isPresented: $showRenameSection) {
                SectionNameSheet(
                    title: "Rename",
                    confirmLabel: "Save",
                    suggestedName: renameSuggestedName
                ) { name in
                    model.renameSection(renameIndex, to: name)
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
            .sheet(isPresented: $showLoadSong) {
                LoadSongSheet(model: model)
            }
            .sheet(isPresented: $showSaveSong) {
                SaveSongSheet(model: model)
            }
            .sheet(isPresented: $showSettings) {
                SettingsScreen()
            }
            .alert("New Song?", isPresented: $showNewSongConfirmation) {
                Button("Continue") { model.newSong() }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("The current song will be replaced.")
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

    /// Prompts for a section name, prefilled with `suggestedName` — used both for adding a new
    /// section (suggested: the next default "Section N") and renaming one (suggested: its
    /// current name).
    private struct SectionNameSheet: View {
        @Environment(\.dismiss) private var dismiss
        @State private var name: String
        let title: String
        let confirmLabel: String
        let onConfirm: (String) -> Void

        init(title: String, confirmLabel: String, suggestedName: String, onConfirm: @escaping (String) -> Void) {
            self.title = title
            self.confirmLabel = confirmLabel
            _name = State(initialValue: suggestedName)
            self.onConfirm = onConfirm
        }

        var body: some View {
            NavigationView {
                Form {
                    TextField("Name", text: $name)
                        .autocorrectionDisabled()
                }
                .navigationTitle(title)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") { dismiss() }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button(confirmLabel) {
                            onConfirm(name)
                            dismiss()
                        }
                    }
                }
            }
        }
    }

    /// Saved songs list, tap to load — Android's SongActivity.showLoadSongDialog (no delete
    /// there either, unlike the progression Load sheet).
    private struct LoadSongSheet: View {
        @ObservedObject var model: SongModel
        @Environment(\.dismiss) private var dismiss
        @State private var names: [String] = []

        var body: some View {
            NavigationView {
                Group {
                    if names.isEmpty {
                        VStack(spacing: 8) {
                            Image(systemName: "tray").font(.largeTitle).foregroundStyle(.secondary)
                            Text("No Saved Songs").foregroundStyle(.secondary)
                        }
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                    } else {
                        List(names, id: \.self) { name in
                            Button {
                                model.loadSong(name)
                                dismiss()
                            } label: {
                                Text(name).foregroundStyle(.primary)
                            }
                        }
                    }
                }
                .navigationTitle("Load Song")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") { dismiss() }
                    }
                }
                .onAppear { names = model.getSavedSongNames() }
            }
        }
    }

    /// Named song save with an overwrite confirmation — mirrors ProgressionLibrarySheets'
    /// SaveProgressionSheet, bound to the whole song instead of a single progression.
    private struct SaveSongSheet: View {
        @ObservedObject var model: SongModel
        @Environment(\.dismiss) private var dismiss
        @State private var name: String = ""
        @State private var names: [String] = []
        @State private var showOverwriteConfirm = false

        var body: some View {
            NavigationView {
                VStack(spacing: 0) {
                    TextField("Name", text: $name)
                        .textFieldStyle(.roundedBorder)
                        .padding(16)
                    Spacer()
                }
                .navigationTitle("Save Song")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") { dismiss() }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Save") { attemptSave() }
                            .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                }
                .onAppear {
                    name = model.songName
                    names = model.getSavedSongNames()
                }
                .alert("Overwrite?", isPresented: $showOverwriteConfirm) {
                    Button("Overwrite", role: .destructive) {
                        model.saveNamedSong(name)
                        dismiss()
                    }
                    Button("Cancel", role: .cancel) {}
                } message: {
                    Text("“") + Text(verbatim: name) + Text("” already exists and will be overwritten.")
                }
            }
        }

        private func attemptSave() {
            let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty else { return }
            name = trimmed
            if names.contains(trimmed) {
                showOverwriteConfirm = true
            } else {
                model.saveNamedSong(trimmed)
                dismiss()
            }
        }
    }

    /// Mini progress track showing this section's chords on the same 0..1 timeline as playback
    /// progress, so a label sits exactly where the fill edge reaches it while playing — mirrors
    /// Android's ChordTrackView.
    private func chordTrack(sectionIndex: Int) -> some View {
        let marks = model.chordMarks(forSection: sectionIndex)
        let isPlaying = sectionIndex == model.playingSectionIndex
        let progress = CGFloat(isPlaying ? model.playingSectionProgress : 0)

        return Canvas { context, size in
            let trackPath = Path(roundedRect: CGRect(origin: .zero, size: size), cornerRadius: 6)
            context.fill(trackPath, with: .color(Color(.tertiarySystemFill)))

            if isPlaying && progress > 0 {
                context.drawLayer { layer in
                    layer.clip(to: Path(CGRect(x: 0, y: 0, width: size.width * progress, height: size.height)))
                    layer.fill(trackPath, with: .color(Color.accentColor.opacity(0.35)))
                }
            }

            for (i, mark) in marks.enumerated() {
                let x = CGFloat(mark.fraction) * size.width
                let slotEnd = i + 1 < marks.count ? CGFloat(marks[i + 1].fraction) * size.width : size.width
                context.drawLayer { layer in
                    layer.clip(to: Path(CGRect(x: x, y: 0, width: max(slotEnd - x, 0), height: size.height)))
                    layer.draw(
                        Text(mark.label).font(.system(size: 10)).foregroundColor(.secondary),
                        at: CGPoint(x: x + 3, y: size.height / 2),
                        anchor: .leading
                    )
                }
            }
        }
        .frame(height: 18)
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
                Text(verbatim: "\(model.tempoPercent)%")
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
