import Foundation
import Shared

/// ObservableObject wrappers over the shared `DrumPatternEditor` / `StrummingPatternEditor` /
/// `SoloPatternEditor`. Those editors are plain Kotlin objects with synchronous mutations and
/// no Combine, so each wrapper bumps `revision` after every edit to drive SwiftUI updates and
/// re-reads the editor's snapshot accessors. Auditioning goes through the shared
/// `IosPatternPreviewController` (a looping one-measure preview); "Fertig" writes the result
/// back through `ProgressionViewModelCore.set…Pattern`.

// MARK: - Drums

final class DrumEditorModel: ObservableObject {
    let measureIndex: Int
    private let env = IosAppEnvironment.companion.shared
    private let editor: DrumPatternEditor
    private var previewHandle: WatchHandle?

    @Published private(set) var revision = 0
    @Published var isPreviewing = false

    init(measureIndex: Int) {
        self.measureIndex = measureIndex
        self.editor = IosAppEnvironment.companion.shared.makeDrumEditor(measureIndex: Int32(measureIndex))
        previewHandle = FlowWatchKt.watch(flow: env.patternPreview.isPlaying) { [weak self] value in
            self?.isPreviewing = (value as? KotlinBoolean)?.boolValue ?? false
        }
    }

    deinit {
        env.patternPreview.stop()
        previewHandle?.close()
    }

    var stepCount: Int { _ = revision; return Int(editor.stepCount()) }
    var steps: [DrumStep] { _ = revision; return editor.currentSteps() }
    var usedPatterns: [DrumPattern] { editor.usedPatterns }
    var defaultPatterns: [DrumPattern] { editor.defaultPatterns }

    func toggleKick(_ i: Int) { editor.toggleKick(index: Int32(i)); env.patternPreview.tapKick(); bump() }
    func toggleSnare(_ i: Int) { editor.toggleSnare(index: Int32(i)); env.patternPreview.tapSnare(); bump() }
    func toggleHiHat(_ i: Int) { editor.toggleHiHat(index: Int32(i)); env.patternPreview.tapHiHat(); bump() }

    func selectPreset(_ pattern: DrumPattern) { editor.selectPreset(pattern: pattern); bump() }

    func togglePreview() {
        if isPreviewing { env.patternPreview.stop() } else { restartPreview() }
    }

    func stopPreview() { env.patternPreview.stop() }

    func save() {
        env.progressionViewModel.setDrumPattern(measureIndex: Int32(measureIndex), pattern: editor.build())
    }

    private func restartPreview() {
        env.patternPreview.playMeasure(
            session: env.session,
            prototypeMeasureIndex: Int32(measureIndex),
            drums: editor.build(),
            strumming: nil,
            solo: nil
        )
    }

    private func bump() {
        revision += 1
        if isPreviewing { restartPreview() }
    }
}

// MARK: - Strumming

final class StrummingEditorModel: ObservableObject {
    let measureIndex: Int
    private let env = IosAppEnvironment.companion.shared
    private let editor: StrummingPatternEditor
    private var previewHandle: WatchHandle?

    @Published private(set) var revision = 0
    @Published var isPreviewing = false

    init(measureIndex: Int) {
        self.measureIndex = measureIndex
        self.editor = IosAppEnvironment.companion.shared.makeStrummingEditor(measureIndex: Int32(measureIndex))
        previewHandle = FlowWatchKt.watch(flow: env.patternPreview.isPlaying) { [weak self] value in
            self?.isPreviewing = (value as? KotlinBoolean)?.boolValue ?? false
        }
    }

    deinit {
        env.patternPreview.stop()
        previewHandle?.close()
    }

    var strums: [Strum] { _ = revision; return editor.currentStrums() }
    var usedPatterns: [StrummingPattern] { editor.usedPatterns }
    var defaultPatterns: [StrummingPattern] { editor.defaultPatterns }

    func cycle(_ i: Int) { editor.cycle(index: Int32(i)); bump() }
    func selectPreset(_ pattern: StrummingPattern) { editor.selectPreset(pattern: pattern); bump() }

    func togglePreview() {
        if isPreviewing { env.patternPreview.stop() } else { restartPreview() }
    }

    func stopPreview() { env.patternPreview.stop() }

    func save() {
        env.progressionViewModel.setStrummingPattern(measureIndex: Int32(measureIndex), pattern: editor.build())
    }

    private func restartPreview() {
        env.patternPreview.playMeasure(
            session: env.session,
            prototypeMeasureIndex: Int32(measureIndex),
            drums: nil,
            strumming: editor.build(),
            solo: nil
        )
    }

    private func bump() {
        revision += 1
        if isPreviewing { restartPreview() }
    }
}

// MARK: - Solo

final class SoloEditorModel: ObservableObject {
    let measureIndex: Int
    private let env = IosAppEnvironment.companion.shared
    private let editor: SoloPatternEditor
    private var previewHandle: WatchHandle?

    @Published private(set) var revision = 0
    @Published var isPreviewing = false

    init(measureIndex: Int) {
        self.measureIndex = measureIndex
        self.editor = IosAppEnvironment.companion.shared.makeSoloEditor(measureIndex: Int32(measureIndex))
        env.patternPreview.warmUp()
        previewHandle = FlowWatchKt.watch(flow: env.patternPreview.isPlaying) { [weak self] value in
            self?.isPreviewing = (value as? KotlinBoolean)?.boolValue ?? false
        }
    }

    deinit {
        env.patternPreview.stop()
        env.patternPreview.releaseNote()
        previewHandle?.close()
    }

    var measureCount: Int { Int(editor.measureCount()) }
    var activeMeasure: Int { _ = revision; return Int(editor.activeMeasure) }
    var cursor: Int { _ = revision; return Int(editor.cursor) }
    var octave: Int { _ = revision; return Int(editor.octave) }
    var editMode: SoloEditMode { _ = revision; return editor.editMode }
    var hasClipboard: Bool { _ = revision; return editor.hasClipboard() }

    func slots(_ measure: Int) -> [SoloSlot] { _ = revision; return editor.slots(measureIndex: Int32(measure)) }

    func scalePitchClasses() -> Set<Int> {
        Set(editor.scalePitchClasses().map { $0.intValue })
    }

    func rootPitchClass(measure: Int, slot: Int) -> Int {
        Int(editor.rootPitchClassAt(measureIndex: Int32(measure), slotIndex: Int32(slot)))
    }

    /// Chord name at the slot where it starts (e.g. Android's chordRow), or nil elsewhere —
    /// a chord in effect but starting earlier isn't repeated at every slot it carries through.
    func chordLabel(measure: Int, slot: Int) -> String? {
        let events = env.getMeasureChordEvents(measureIndex: Int32(measure))
        return events.first { Int($0.quarterNote) * 2 == slot }?.chord.getDisplayName()
    }

    func selectSlot(measure: Int, slot: Int) {
        editor.selectSlot(measureIndex: Int32(measure), slotIndex: Int32(slot))
        bump()
    }

    func cycleEditMode() { editor.cycleEditMode(); bump() }
    func octaveUp() { editor.octaveUp(); bump() }
    func octaveDown() { editor.octaveDown(); bump() }
    func setRest() { editor.setRestAtCursor(); bump() }
    func setLetRing() { editor.setLetRingAtCursor(); bump() }

    func pressKey(pitchClass: Int, octaveOffset: Int) {
        let midi = editor.pressKey(pitchClass: Int32(pitchClass), octaveOffset: Int32(octaveOffset))
        env.patternPreview.startNote(midi: midi)
        bump()
    }

    func releaseKey() {
        env.patternPreview.releaseNote()
    }

    func copyAll() { editor.doCopyAll(); bump() }
    func pasteAll() { _ = editor.pasteAll(); bump() }

    func togglePreview() {
        if isPreviewing { env.patternPreview.stop() } else { restartPreview() }
    }

    func stopPreview() { env.patternPreview.stop() }

    func save() {
        for (i, pattern) in editor.buildPatterns().enumerated() {
            env.progressionViewModel.setSoloPattern(measureIndex: Int32(i), pattern: pattern)
        }
    }

    private func restartPreview() {
        env.patternPreview.playMeasure(
            session: env.session,
            prototypeMeasureIndex: Int32(activeMeasure),
            drums: nil,
            strumming: nil,
            solo: editor.buildActivePattern()
        )
    }

    private func bump() {
        revision += 1
        if isPreviewing { restartPreview() }
    }
}

// MARK: - Shared display helpers

enum PatternDisplay {
    static func drumSignature(_ pattern: DrumPattern) -> String {
        pattern.steps.map { "\($0.kick ? 1 : 0)\($0.snare ? 1 : 0)\($0.hiHat ? 1 : 0)" }.joined(separator: "-")
    }

    static func strumSignature(_ pattern: StrummingPattern) -> String {
        pattern.strums.map { symbol(for: $0) }.joined()
    }

    static func strumSignature0(_ strums: [Strum]) -> String {
        strums.map { symbol(for: $0) }.joined()
    }

    static func symbol(for strum: Strum) -> String {
        if strum == Strum.down { return "↓" }
        if strum == Strum.up { return "↑" }
        if strum == Strum.mute { return "✕" }
        if strum == Strum.letring { return "→" }
        return "·" // REST
    }

    static let noteNames = ["C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B"]

    static func midiName(_ midi: Int) -> String {
        let octave = midi / 12 - 1
        return "\(noteNames[((midi % 12) + 12) % 12])\(octave)"
    }

    static func soloSlotLabel(_ slot: SoloSlot) -> String {
        if slot.kind == SoloSlotKind.note { return midiName(Int(slot.midi)) }
        if slot.kind == SoloSlotKind.letring { return "~" }
        return "–" // REST
    }

    static func isNote(_ slot: SoloSlot) -> Bool { slot.kind == SoloSlotKind.note }
}
