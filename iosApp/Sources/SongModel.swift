import Foundation
import Shared

/// ObservableObject bridge over the shared SongViewModelCore + IosPlaybackController.
/// Collects the core's StateFlows via the FlowWatch helper and republishes them for SwiftUI.
final class SongModel: ObservableObject {
    private let core: SongViewModelCore
    private let playback: IosPlaybackController
    private var handles: [WatchHandle] = []

    @Published var songName: String = ""
    @Published var sectionNames: [String] = []
    @Published var selectedIndex: Int = 0
    @Published var isLooping: Bool = false
    @Published var tempoPercent: Int = 100
    @Published var isPlaying: Bool = false
    /// Section currently sounding during playback (-1 when stopped), and 0..1 progress through
    /// it — mirrors Android's SectionAdapter.setPlayingIndex/setProgress.
    @Published var playingSectionIndex: Int = -1
    @Published var playingSectionProgress: Float = 0

    private var lastMeasureIndex = -1
    private var lastStrumIndex = -1

    init(env: IosAppEnvironment) {
        core = env.songViewModel
        playback = env.playback

        handles.append(FlowWatchKt.watch(flow: core.songName) { [weak self] value in
            self?.songName = value as? String ?? ""
        })
        handles.append(FlowWatchKt.watch(flow: core.songSectionNames) { [weak self] value in
            self?.sectionNames = value as? [String] ?? []
        })
        handles.append(FlowWatchKt.watch(flow: core.selectedSongSectionIndex) { [weak self] value in
            self?.selectedIndex = (value as? KotlinInt)?.intValue ?? 0
        })
        handles.append(FlowWatchKt.watch(flow: core.isSongLooping) { [weak self] value in
            self?.isLooping = (value as? KotlinBoolean)?.boolValue ?? false
        })
        handles.append(FlowWatchKt.watch(flow: core.tempoPercent) { [weak self] value in
            self?.tempoPercent = (value as? KotlinInt)?.intValue ?? 100
        })
        handles.append(FlowWatchKt.watch(flow: playback.isPlaying) { [weak self] value in
            self?.isPlaying = (value as? KotlinBoolean)?.boolValue ?? false
        })
        handles.append(FlowWatchKt.watch(flow: playback.currentMeasureIndex) { [weak self] value in
            self?.lastMeasureIndex = (value as? KotlinInt)?.intValue ?? -1
            self?.updatePlayingPosition()
        })
        handles.append(FlowWatchKt.watch(flow: playback.currentStrumIndex) { [weak self] value in
            self?.lastStrumIndex = (value as? KotlinInt)?.intValue ?? -1
            self?.updatePlayingPosition()
        })
    }

    private func updatePlayingPosition() {
        guard lastMeasureIndex >= 0, lastStrumIndex >= 0 else {
            playingSectionIndex = -1
            playingSectionProgress = 0
            return
        }
        playingSectionIndex = Int(core.getSectionIndexForMeasure(measureIndex: Int32(lastMeasureIndex)))
        playingSectionProgress = core.getSectionProgress(measureIndex: Int32(lastMeasureIndex), strumIndex: Int32(lastStrumIndex))
    }

    /// Chord labels for a section's mini progress track, positioned 0..1 on the same timeline
    /// as `playingSectionProgress` so they align pixel-exact with the moving fill.
    func chordMarks(forSection index: Int) -> [ChordMark] {
        core.getSectionChordMarks(index: Int32(index))
    }

    deinit {
        handles.forEach { $0.close() }
    }

    func selectSection(_ index: Int) {
        _ = core.selectSongSection(index: Int32(index))
    }

    /// Add a new section (blank name lets the shared model auto-name it "Section N"), using the
    /// current section's key/mode/tempo as the new one's starting point (mirrors Android).
    func addSection(name: String) {
        let progression = core.getCurrentProgression()
        core.addSongSection(
            name: name,
            currentKey: progression.key,
            currentMode: progression.mode,
            currentTempo: progression.tempo
        )
    }

    /// Reorder sections. `destination` uses SwiftUI's `onMove` convention (an index into the
    /// array *before* removal), so it's adjusted to a plain target index when moving downward.
    func moveSection(from source: IndexSet, to destination: Int) {
        guard let from = source.first else { return }
        let to = destination > from ? destination - 1 : destination
        guard to != from else { return }
        _ = core.moveSongSection(fromIndex: Int32(from), toIndex: Int32(to))
    }

    /// Move a section to an explicit target index (context-menu "move up"/"move down"), as
    /// opposed to `moveSection(from:to:)`'s drag-gesture index convention.
    func moveSection(_ index: Int, to newIndex: Int) {
        _ = core.moveSongSection(fromIndex: Int32(index), toIndex: Int32(newIndex))
    }

    func renameSection(_ index: Int, to name: String) {
        core.renameSongSection(index: Int32(index), newName: name)
    }

    /// Duplicate a section (matches Android: select it, then duplicate the now-current one).
    func duplicateSection(_ index: Int) {
        _ = core.selectSongSection(index: Int32(index))
        _ = core.duplicateCurrentSongSection()
    }

    /// Delete a section, using the current progression's key/mode/tempo as the fallback for a
    /// fresh empty section if this was the last one left (mirrors Android).
    func deleteSection(_ index: Int) {
        let progression = core.getCurrentProgression()
        _ = core.deleteSongSection(
            index: Int32(index),
            currentKey: progression.key,
            currentMode: progression.mode,
            currentTempo: progression.tempo
        )
    }

    func togglePlayback() {
        if isPlaying {
            playback.stop()
        } else {
            playback.playSong()
        }
    }

    func stop() {
        playback.stop()
    }

    func toggleLooping() {
        core.onSongRepeatToggle(isToggled: !isLooping)
    }

    func incrementTempoPercent() {
        core.incrementTempoPercent()
    }

    func decrementTempoPercent() {
        core.decrementTempoPercent()
    }

    /// Suggested base filename (without extension) for the exported MIDI file.
    var songFilenameBase: String {
        let trimmed = songName.trimmingCharacters(in: .whitespacesAndNewlines)
        let base = trimmed.isEmpty ? "song" : trimmed
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: " _-"))
        let sanitized = String(base.unicodeScalars.map { allowed.contains($0) ? Character($0) : "_" })
        return sanitized.isEmpty ? "song" : sanitized
    }

    /// Render the current song to a multi-track MIDI file. Mirrors Android's export:
    /// the whole song (all sections) with the selected instrument tracks.
    func exportMidiData(tracks: Set<MidiTrackType>) -> Data {
        let song = core.getCurrentSong()
        let kb = MidiExporter.shared.exportSong(song: song, tracks: tracks)
        let count = Int(kb.size)
        var bytes = [UInt8](repeating: 0, count: count)
        for i in 0..<count {
            bytes[i] = UInt8(bitPattern: kb.get(index: Int32(i)))
        }
        return Data(bytes)
    }
}
