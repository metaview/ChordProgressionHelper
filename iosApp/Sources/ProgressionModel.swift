import Foundation
import Shared

/// ObservableObject bridge over the shared ProgressionViewModelCore + IosProgressionPlaybackController.
/// Mirrors SongModel: collects the core's StateFlows via FlowWatch and republishes them for SwiftUI,
/// and forwards user actions back into the core.
///
/// The edited progression IS the current section of the shared song (session.currentProgression),
/// so opening this screen just needs to refresh the core's derived state for that section.
final class ProgressionModel: ObservableObject {
    private let core: ProgressionViewModelCore
    private let playback: IosProgressionPlaybackController
    private let templatePreviewController: IosTemplatePreviewController
    private var handles: [WatchHandle] = []

    // Chord palettes
    @Published var scaleDegreeChords: [Chord] = []
    @Published var relatedChords: [Chord] = []
    @Published var borrowedMinorChords: [Chord] = []
    @Published var borrowedMajorChords: [Chord] = []

    // Editing state
    @Published var measures: [Measure] = []
    @Published var selectedChord: Chord?
    @Published var suggestedChord: Chord?
    @Published var targetChord: Chord?
    @Published var key: Key = .c
    @Published var tempo: Int = 120
    @Published var isLooping: Bool = false

    // Playback
    @Published var isPlaying: Bool = false
    @Published var currentMeasureIndex: Int = -1

    // Dialogs driven by the core
    @Published var deleteConfirmationMeasure: Int?
    @Published var transposeConfirmationKey: Key?
    @Published var showNewProgressionConfirmation: Bool = false

    /// Template preview (the "new progression" picker auditions a template before committing it).
    @Published var isTemplatePreviewPlaying: Bool = false

    /// All keys for the key picker (from the Kotlin bridge helper).
    let allKeys: [Key] = (IosModelBridgeKt.allKeys() as? [Key]) ?? []

    /// Built-in progression templates for the "new progression" picker.
    let allTemplates: [ProgressionTemplate] = (IosModelBridgeKt.allTemplates() as? [ProgressionTemplate]) ?? []

    init(env: IosAppEnvironment) {
        core = env.progressionViewModel
        playback = env.progressionPlayback
        templatePreviewController = env.templatePreview

        // Sync the core's derived state (chords, measures, key, tempo) to whichever section
        // is currently selected in the shared song before we start observing.
        core.refreshUIAfterProgressionChange()

        handles.append(FlowWatchKt.watch(flow: core.scaleDegreeChords) { [weak self] value in
            self?.scaleDegreeChords = value as? [Chord] ?? []
        })
        handles.append(FlowWatchKt.watch(flow: core.relatedChords) { [weak self] value in
            self?.relatedChords = value as? [Chord] ?? []
        })
        handles.append(FlowWatchKt.watch(flow: core.borrowedMinorChords) { [weak self] value in
            self?.borrowedMinorChords = value as? [Chord] ?? []
        })
        handles.append(FlowWatchKt.watch(flow: core.borrowedMajorChords) { [weak self] value in
            self?.borrowedMajorChords = value as? [Chord] ?? []
        })
        handles.append(FlowWatchKt.watch(flow: core.measures) { [weak self] value in
            self?.measures = value as? [Measure] ?? []
        })
        handles.append(FlowWatchKt.watch(flow: core.selectedChord) { [weak self] value in
            self?.selectedChord = value as? Chord
        })
        handles.append(FlowWatchKt.watch(flow: core.suggestedChord) { [weak self] value in
            self?.suggestedChord = value as? Chord
        })
        handles.append(FlowWatchKt.watch(flow: core.targetChord) { [weak self] value in
            self?.targetChord = value as? Chord
        })
        handles.append(FlowWatchKt.watch(flow: core.key) { [weak self] value in
            if let k = value as? Key { self?.key = k }
        })
        handles.append(FlowWatchKt.watch(flow: core.tempo) { [weak self] value in
            self?.tempo = (value as? KotlinInt)?.intValue ?? 120
        })
        handles.append(FlowWatchKt.watch(flow: core.isProgressionLooping) { [weak self] value in
            self?.isLooping = (value as? KotlinBoolean)?.boolValue ?? false
        })
        handles.append(FlowWatchKt.watch(flow: core.showDeleteConfirmation) { [weak self] value in
            self?.deleteConfirmationMeasure = (value as? KotlinInt)?.intValue
        })
        handles.append(FlowWatchKt.watch(flow: core.showTransposeConfirmation) { [weak self] value in
            self?.transposeConfirmationKey = value as? Key
        })
        handles.append(FlowWatchKt.watch(flow: core.showNewProgressionConfirmation) { [weak self] value in
            self?.showNewProgressionConfirmation = (value as? KotlinBoolean)?.boolValue ?? false
        })
        handles.append(FlowWatchKt.watch(flow: templatePreviewController.isPlaying) { [weak self] value in
            self?.isTemplatePreviewPlaying = (value as? KotlinBoolean)?.boolValue ?? false
        })
        handles.append(FlowWatchKt.watch(flow: playback.isPlaying) { [weak self] value in
            self?.isPlaying = (value as? KotlinBoolean)?.boolValue ?? false
        })
        handles.append(FlowWatchKt.watch(flow: playback.currentMeasureIndex) { [weak self] value in
            self?.currentMeasureIndex = (value as? KotlinInt)?.intValue ?? -1
        })
    }

    deinit {
        playback.stop()
        templatePreviewController.stop()
        handles.forEach { $0.close() }
    }

    // MARK: - Chord palette / selection

    /// Select a chord and start its sustained preview immediately (key-down).
    func pressChord(_ chord: Chord) {
        core.setSelectedChord(chord: chord, ownerId: "IOS", startPreviewImmediately: true)
    }

    /// Let the currently-held chord preview ring out (key-up).
    func releaseChord() {
        core.releaseChordPreview()
    }

    /// True when `selectedChord` is the Power-chord variant of `chord` (same root + scale degree,
    /// e.g. base "G" while a "G5" built from it is selected). A selected Power chord isn't the
    /// same Chord object as its palette base, so this matches them back up to keep the base item
    /// highlighted and showing the Power name — mirrors Android's ChordAdapter.isSelectedAsPower.
    func isSelectedAsPower(_ chord: Chord) -> Bool {
        guard let selected = selectedChord, selected.quality == ChordType.power else { return false }
        return selected.root.displayName == chord.root.displayName
            && selected.scaleDegreeName == chord.scaleDegreeName
    }

    /// Turn a palette chord into its Power-chord variant (e.g. G -> G5) and select it.
    func makePowerChord(_ chord: Chord) {
        let powerChord = Chord(root: chord.root, quality: ChordType.power, scaleDegreeName: chord.scaleDegreeName)
        core.setSelectedChord(chord: powerChord, ownerId: "IOS", startPreviewImmediately: true)
    }

    // MARK: - Measures

    /// Chord in effect at this quarter-note slot: the most recent chord at or before it, matching
    /// Android's grid (a chord placed at a slot carries forward until a later slot overrides it).
    func chord(inMeasure measure: Measure, quarterNote: Int) -> Chord? {
        measure.getChordAt(eighthNoteIndex: Int32(quarterNote * 2))
    }

    /// Place the currently selected chord at the given quarter-note slot.
    func addSelectedChord(measureIndex: Int, quarterNote: Int) {
        core.addChordToMeasure(measureIndex: Int32(measureIndex), eighthNoteIndex: Int32(quarterNote * 2))
    }

    func addMeasure() {
        core.addMeasure()
    }

    /// Non-empty measures route through a confirmation dialog (see deleteConfirmationMeasure).
    func requestRemoveMeasure(_ index: Int) {
        core.removeMeasure(measureIndex: Int32(index))
    }

    func confirmRemoveMeasure(_ index: Int) {
        core.confirmRemoveMeasure(measureIndex: Int32(index))
        core.onDeleteConfirmationHandled()
    }

    func cancelRemoveMeasure() {
        core.onDeleteConfirmationHandled()
    }

    // MARK: - Key / tempo / loop

    /// Changing the key on a progression that already has chords routes through a transpose
    /// confirmation (see transposeConfirmationKey); an empty progression changes key directly.
    func setKey(_ newKey: Key) {
        core.setKey(key: newKey)
    }

    func confirmTranspose(_ newKey: Key, transpose: Bool) {
        core.confirmTranspose(newKey: newKey, transpose: transpose)
    }

    func cancelTranspose() {
        core.onTransposeConfirmationHandled()
    }

    func incrementTempo() { core.incrementTempo() }
    func decrementTempo() { core.decrementTempo() }

    func toggleLooping() {
        core.onRepeatToggle(isToggled: !isLooping)
    }

    // MARK: - Playback

    func togglePlayback() {
        if isPlaying {
            playback.stop()
        } else {
            playback.play()
        }
    }

    func stop() {
        playback.stop()
    }

    // MARK: - New / Load / Save (Etappe 3)

    /// Opens the "new progression" flow: a confirmation alert, then the template picker.
    func requestNewProgression() {
        core.requestNewProgression()
    }

    func cancelNewProgression() {
        core.onNewProgressionConfirmationHandled()
    }

    /// Replaces the current section's progression with an empty one (template == nil) or one
    /// built from `template`, in `key` at `tempo`.
    func confirmNewProgression(template: ProgressionTemplate?, key: Key, tempo: Int) {
        core.confirmNewProgression(template: template, newKey: key, newTempo: KotlinInt(int: Int32(tempo)))
        core.onNewProgressionConfirmationHandled()
    }

    /// Loops a throwaway progression built from `template` (nil = empty) so the picker can be
    /// auditioned before committing. Call `stopTemplatePreview()` when the picker closes.
    func previewTemplate(_ template: ProgressionTemplate?, key: Key, tempo: Int) {
        let progression = IosModelBridgeKt.buildProgressionFromTemplate(template: template, key: key, tempo: Int32(tempo))
        templatePreviewController.play(progression: progression)
    }

    func stopTemplatePreview() {
        templatePreviewController.stop()
    }

    func savedProgressionNames() -> [String] {
        (core.getSavedProgressionNames() as? [String]) ?? []
    }

    func progressionPreview(_ name: String) -> String? {
        core.getProgressionPreview(name: name)
    }

    func saveNamedProgression(_ name: String) {
        core.saveNamedProgression(name: name)
    }

    func loadProgression(_ name: String) {
        core.loadProgression(name: name)
    }

    func deleteProgression(_ name: String) {
        core.deleteProgression(name: name)
    }

    // MARK: - Helpers

    /// Stable string identity for a chord, used for SwiftUI selection highlighting and ForEach ids
    /// (avoids relying on Kotlin/Native equality bridging).
    func chordKey(_ chord: Chord) -> String {
        chord.getDisplayName() + "|" + (chord.getRomanNumeral() ?? "")
    }

    func isSelected(_ chord: Chord) -> Bool {
        guard let selected = selectedChord else { return false }
        return chordKey(selected) == chordKey(chord)
    }
}
