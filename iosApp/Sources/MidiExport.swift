import SwiftUI
import UniformTypeIdentifiers
import Shared

/// A plain MIDI file wrapper so SwiftUI's `.fileExporter` can write the bytes the shared
/// `MidiExporter` produced to a user-chosen location (Files app, iCloud, etc.).
struct MidiDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.midi] }
    static var writableContentTypes: [UTType] { [.midi] }

    var data: Data

    init(data: Data) {
        self.data = data
    }

    init(configuration: ReadConfiguration) throws {
        data = configuration.file.regularFileContents ?? Data()
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}

/// Checkbox sheet (all tracks pre-selected) matching Android's track-selection dialog.
/// Calls `onExport` with the chosen set; the parent then presents the file exporter.
struct MidiTrackSelectionSheet: View {
    @Environment(\.dismiss) private var dismiss

    @State private var includeChords = true
    @State private var includeDrums = true
    @State private var includeSolo = true

    let onExport: (Set<MidiTrackType>) -> Void

    private var selectedTracks: Set<MidiTrackType> {
        var tracks = Set<MidiTrackType>()
        if includeChords { tracks.insert(.chords) }
        if includeDrums { tracks.insert(.drums) }
        if includeSolo { tracks.insert(.solo) }
        return tracks
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Tracks") {
                    Toggle("Chords", isOn: $includeChords)
                    Toggle("Drums", isOn: $includeDrums)
                    Toggle("Solo", isOn: $includeSolo)
                }
            }
            .navigationTitle("Export as MIDI")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Export") {
                        let tracks = selectedTracks
                        dismiss()
                        onExport(tracks)
                    }
                    .disabled(selectedTracks.isEmpty)
                }
            }
        }
    }
}
