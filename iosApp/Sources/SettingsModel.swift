import Foundation
import Shared

/// ObservableObject bridge over the shared SettingsStore. iOS counterpart of Android's
/// SettingsActivity + StrummingTimingActivity. Unlike the other *Model classes, SettingsStore
/// is a plain mutable-property object (no StateFlow), so each control's initial value is read
/// once in init() and written straight back to the store on every change — same as Android's
/// direct-write-on-change listeners.
final class SettingsModel: ObservableObject {
    private let env = IosAppEnvironment.companion.shared
    private var core: SettingsStore { env.settings }

    // MARK: - Sound levels (percent, 0...200 — matches Android's seekbar range)

    @Published var drumLevelPercent: Double { didSet { core.drumLevel = Float(drumLevelPercent / 100) } }
    @Published var soloLevelPercent: Double { didSet { core.soloLevel = Float(soloLevelPercent / 100) } }
    @Published var strumLevelPercent: Double { didSet { core.strumLevel = Float(strumLevelPercent / 100) } }
    @Published var shuffleFactorPercent: Double { didSet { core.shuffleFactor = Float(shuffleFactorPercent / 100) } }
    @Published var strumCrunchPercent: Double { didSet { core.strumCrunchLevel = Float(strumCrunchPercent / 100) } }
    @Published var soloCrunchPercent: Double { didSet { core.soloCrunchLevel = Float(soloCrunchPercent / 100) } }

    @Published var strumPreset: SoundPreset { didSet { core.strumPreset = strumPreset } }
    @Published var soloPreset: SoundPreset { didSet { core.soloPreset = soloPreset } }

    // MARK: - Count-in (0/2/4/8 beats)

    @Published var countInBeats: Int32 { didSet { core.countInBeats = countInBeats } }
    @Published var countInBeatsSong: Int32 { didSet { core.countInBeatsSong = countInBeatsSong } }

    // MARK: - Previews

    @Published var isChordPreviewEnabled: Bool { didSet { core.isChordPreviewEnabled = isChordPreviewEnabled } }
    @Published var isPatternPreviewEnabled: Bool { didSet { core.isStrumPatternPreviewEnabled = isPatternPreviewEnabled } }
    @Published var isDrumPreviewEnabled: Bool { didSet { core.isDrumPreviewEnabled = isDrumPreviewEnabled } }
    @Published var isTemplatePreviewEnabled: Bool { didSet { core.isTemplatePreviewEnabled = isTemplatePreviewEnabled } }
    @Published var isLoopingProgressionEnabled: Bool { didSet { core.isLoopingProgressionEnabled = isLoopingProgressionEnabled } }

    // MARK: - New-progression defaults

    @Published var defaultKey: Key {
        didSet {
            guard oldValue != defaultKey else { return }
            core.defaultKeyName = defaultKey.name
            maybeApplyKeyToSong(oldKey: oldValue, newKey: defaultKey)
        }
    }
    @Published var defaultBpm: Int32 { didSet { core.defaultBpm = defaultBpm } }

    // MARK: - Strum timing (separate screen)

    @Published var upStrokeOffsetMs: Int32 { didSet { core.strokeOffsetMs = upStrokeOffsetMs } }
    @Published var upStringStaggerMs: Int32 { didSet { core.stringStaggerMs = upStringStaggerMs } }
    @Published var downStrokeOffsetMs: Int32 { didSet { core.downStrokeOffsetMs = downStrokeOffsetMs } }
    @Published var downStringStaggerMs: Int32 { didSet { core.downStringStaggerMs = downStringStaggerMs } }

    /// Song-wide transpose confirmation when the default key changes and the current song
    /// already has chords — mirrors Android's SettingsActivity.maybeApplyKeyToSong.
    @Published var transposeConfirmationNewKey: Key?
    private var transposeConfirmationOldKey: Key?

    let allKeys: [Key] = (IosModelBridgeKt.allKeys() as? [Key]) ?? []

    init() {
        let core = env.settings
        drumLevelPercent = Double(core.drumLevel * 100)
        soloLevelPercent = Double(core.soloLevel * 100)
        strumLevelPercent = Double(core.strumLevel * 100)
        shuffleFactorPercent = Double(core.shuffleFactor * 100)
        strumCrunchPercent = Double(core.strumCrunchLevel * 100)
        soloCrunchPercent = Double(core.soloCrunchLevel * 100)
        strumPreset = core.strumPreset
        soloPreset = core.soloPreset
        countInBeats = core.countInBeats
        countInBeatsSong = core.countInBeatsSong
        isChordPreviewEnabled = core.isChordPreviewEnabled
        isPatternPreviewEnabled = core.isStrumPatternPreviewEnabled
        isDrumPreviewEnabled = core.isDrumPreviewEnabled
        isTemplatePreviewEnabled = core.isTemplatePreviewEnabled
        isLoopingProgressionEnabled = core.isLoopingProgressionEnabled
        let keys = (IosModelBridgeKt.allKeys() as? [Key]) ?? []
        defaultKey = keys.first { $0.name == core.defaultKeyName } ?? keys.first ?? Key.c
        defaultBpm = core.defaultBpm
        upStrokeOffsetMs = core.strokeOffsetMs
        upStringStaggerMs = core.stringStaggerMs
        downStrokeOffsetMs = core.downStrokeOffsetMs
        downStringStaggerMs = core.downStringStaggerMs
    }

    func previewSolo() {
        env.settingsPreview.previewSolo()
    }

    func previewStrum() {
        env.settingsPreview.previewStrum()
    }

    /// Restores the "recommended" sound defaults (not necessarily SettingsStore's own raw
    /// per-key defaults) — matches Android's resetSoundButton exactly.
    func resetSoundDefaults() {
        drumLevelPercent = 100
        soloLevelPercent = 150
        strumLevelPercent = 100
        shuffleFactorPercent = 0
        strumCrunchPercent = 100
        soloCrunchPercent = 100
        resetStrumTiming()
    }

    func resetStrumTiming() {
        upStrokeOffsetMs = 10
        upStringStaggerMs = 12
        downStrokeOffsetMs = 0
        downStringStaggerMs = 12
    }

    private func maybeApplyKeyToSong(oldKey: Key, newKey: Key) {
        let progressions = env.songViewModel.getUniqueSongProgressions()
        let hasChords = progressions.contains { prog in
            let measures = (prog.measures as? [Measure]) ?? []
            return measures.contains { measure in
                !((measure.chordEvents as? [Measure.ChordEvent] ?? []).isEmpty)
            }
        }
        guard hasChords else { return }
        transposeConfirmationOldKey = oldKey
        transposeConfirmationNewKey = newKey
    }

    /// `transpose: true` shifts every chord/note in the song to the new key; `false` only
    /// relabels the key without changing any pitches.
    func confirmSongTranspose(transpose: Bool) {
        guard let newKey = transposeConfirmationNewKey, let oldKey = transposeConfirmationOldKey else { return }
        let progressions = env.songViewModel.getUniqueSongProgressions()
        if transpose {
            let shift = Transposer.shared.semitoneShift(oldKey: oldKey, newKey: newKey)
            for prog in progressions {
                Transposer.shared.transpose(progression: prog, semitoneShift: shift)
                prog.key = newKey
            }
        } else {
            for prog in progressions {
                prog.key = newKey
            }
        }
        env.session.save()
        transposeConfirmationNewKey = nil
        transposeConfirmationOldKey = nil
    }

    func cancelSongTranspose() {
        transposeConfirmationNewKey = nil
        transposeConfirmationOldKey = nil
    }
}
