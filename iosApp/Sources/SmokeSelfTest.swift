import Foundation
import os
import Shared

/// Headless startup self-test, run only when the app is launched with `CPH_SMOKE=1` in its
/// environment (CI passes it via `SIMCTL_CHILD_CPH_SMOKE=1`). Normal launches never run it.
///
/// It drives the shared core through the SwiftUI-facing `SongModel` and asserts that each change
/// round-trips back through the Kotlin `StateFlow` -> FlowWatch -> `@Published` bridge — the exact
/// path SwiftUI relies on, which linking/compiling alone never exercises. It prints one
/// `SMOKE-SELFTEST:` line per check and a final `SMOKE-SELFTEST: PASS` / `FAIL` sentinel that the CI
/// greps for. The app keeps running afterwards, so the "survive 20s" liveness check still applies.
@MainActor
final class SmokeSelfTest {
    static var isEnabled: Bool {
        ProcessInfo.processInfo.environment["CPH_SMOKE"] == "1"
    }

    private let model: SongModel
    private var failures: [String] = []

    /// Shared with the background watchdog, so it must be reachable off the main thread. Records the
    /// last step we *entered* (not finished); if the run wedges, the watchdog prints this name.
    private let progress = Progress()

    init(model: SongModel) { self.model = model }

    /// StateFlow deliveries hop through Dispatchers.Main, so between an action and its assertion we
    /// yield to the run loop. Steps are chained with small delays rather than read synchronously,
    /// precisely so the async bridge (not a direct `.value` read) is what we verify.
    func run() {
        step("starting")
        startWatchdog()
        after(0.6) { self.checkInitialState() }
    }

    /// Runs on a background queue so it fires *even if the main thread is blocked* — the whole point is
    /// to tell a main-thread stall (no further step logs at all) apart from a normal failed assertion.
    /// A silent 20s CI timeout becomes an actionable FAIL that names the step we were stuck in.
    private func startWatchdog() {
        let progress = self.progress
        DispatchQueue.global().asyncAfter(deadline: .now() + 25.0) {
            guard !progress.done else { return }
            emit("SMOKE-SELFTEST: FAIL — watchdog fired after 25s, stuck in step \"\(progress.step)\" "
                 + "(no PASS/FAIL reached; main thread likely blocked here)")
            emit("SMOKE-SELFTEST: FAIL (watchdog)")
        }
    }

    private func checkInitialState() {
        step("checkInitialState")
        expect(!model.songName.isEmpty, "initial songName non-empty (was \"\(model.songName)\")")
        expect(!model.sectionNames.isEmpty,
               "initial sectionNames non-empty (was \(model.sectionNames))")
        expect(model.tempoPercent == 100,
               "initial tempoPercent == 100 (was \(model.tempoPercent))")
        log("initial state: songName=\"\(model.songName)\" sections=\(model.sectionNames) tempo=\(model.tempoPercent)%")
        after(0.4) { self.checkTempoRoundTrip() }
    }

    private func checkTempoRoundTrip() {
        step("checkTempoRoundTrip")
        let before = model.tempoPercent
        model.incrementTempoPercent()
        after(0.4) {
            self.expect(self.model.tempoPercent == before + 1,
                        "tempo increment round-trips \(before) -> \(before + 1) (was \(self.model.tempoPercent))")
            self.checkLoopRoundTrip()
        }
    }

    private func checkLoopRoundTrip() {
        step("checkLoopRoundTrip")
        let before = model.isLooping
        model.toggleLooping()
        after(0.4) {
            self.expect(self.model.isLooping == !before,
                        "loop toggle round-trips \(before) -> \(!before) (was \(self.model.isLooping))")
            self.checkPlaybackStarts()
        }
    }

    /// Drives the actual playback path: play() creates the audio sink + AVAudioEngine and starts the
    /// background render loop, and flips isPlaying (which must reach @Published through the bridge).
    /// We don't assert sound came out — just that the control path runs without crashing and the
    /// state propagates. Playback runs on the background audio queue, so the main run loop (and these
    /// steps) keep ticking even if rendering stalls.
    private func checkPlaybackStarts() {
        step("checkPlaybackStarts")
        log("starting playback ...")
        model.togglePlayback()
        after(1.5) {
            self.expect(self.model.isPlaying, "playback started: isPlaying == true (was \(self.model.isPlaying))")
            self.checkPlaybackStops()
        }
    }

    private func checkPlaybackStops() {
        step("checkPlaybackStops")
        model.stop()
        after(1.0) {
            self.expect(!self.model.isPlaying, "playback stopped: isPlaying == false (was \(self.model.isPlaying))")
            self.checkPatternEditors()
        }
    }

    /// Exercises the Etappe-2 per-measure pattern editors end to end: build each editor from the
    /// shared factory, mutate it, run a one-measure preview through the FlowWatch bridge, and save
    /// back into the progression. Linking never covers the Kotlin/Native bridging these hit.
    private func checkPatternEditors() {
        step("checkPatternEditors")

        let drum = DrumEditorModel(measureIndex: 0)
        expect(drum.steps.count == 8, "drum editor has 8 steps (was \(drum.steps.count))")
        expect(!drum.defaultPatterns.isEmpty, "drum editor exposes default patterns")
        drum.toggleKick(1)
        drum.toggleSnare(2)
        if let first = drum.defaultPatterns.first { drum.selectPreset(first) }
        drum.save()
        log("drum editor: presets=\(drum.defaultPatterns.count) used=\(drum.usedPatterns.count)")

        let strum = StrummingEditorModel(measureIndex: 0)
        expect(strum.strums.count == 8, "strumming editor has 8 steps (was \(strum.strums.count))")
        let strumBefore = PatternDisplay.strumSignature0(strum.strums)
        strum.cycle(0)
        expect(PatternDisplay.strumSignature0(strum.strums) != strumBefore,
               "strumming cycle changed step 0 (\(strumBefore))")
        strum.save()

        let solo = SoloEditorModel(measureIndex: 0)
        expect(solo.measureCount >= 1, "solo editor has >=1 measure (was \(solo.measureCount))")
        expect(solo.slots(0).count == 8, "solo measure has 8 slots (was \(solo.slots(0).count))")
        expect(!solo.scalePitchClasses().isEmpty, "solo editor computes a scale")
        solo.cycleEditMode() // PREVIEW -> EDIT
        expect(solo.editMode == SoloEditMode.edit, "solo edit mode is EDIT after one cycle")
        solo.selectSlot(measure: 0, slot: 0)
        solo.pressKey(pitchClass: 0, octaveOffset: 0)
        solo.releaseKey()
        expect(PatternDisplay.isNote(solo.slots(0)[0]), "solo pressKey wrote a note at slot 0")
        solo.save()

        after(0.5) {
            solo.togglePreview()
            self.after(1.0) {
                self.expect(solo.isPreviewing, "pattern preview started (isPreviewing was \(solo.isPreviewing))")
                self.expect(solo.playingSlot >= 0, "pattern preview reports a playing slot (was \(solo.playingSlot))")
                // Exercise the actual reported bug: toggling a second time (not stopPreview())
                // must stop it, or the loop (shouldLoop = { true }) never ends.
                solo.togglePreview()
                self.after(0.8) {
                    self.expect(!solo.isPreviewing, "pattern preview stopped via togglePreview (isPreviewing was \(solo.isPreviewing))")
                    self.expect(solo.playingSlot == -1, "playing slot resets after stop (was \(solo.playingSlot))")
                    self.checkProgressionLibrary()
                }
            }
        }
    }

    /// Exercises the Etappe-3 New/Load/Save flow: template list, applying a template (key/tempo
    /// round-trip through the FlowWatch bridge), and a save -> load -> delete round trip through
    /// ProgressionStorage. Uses a throwaway name and deletes it again so repeated runs don't
    /// accumulate saved progressions.
    private func checkProgressionLibrary() {
        step("checkProgressionLibrary")
        let testName = "SMOKE_TEST_TMP_\(Int(Date().timeIntervalSince1970))"

        let prog = ProgressionModel(env: IosAppEnvironment.companion.shared)
        expect(!prog.allTemplates.isEmpty, "progression templates list non-empty (was \(prog.allTemplates.count))")
        expect(!prog.allKeys.isEmpty, "key list non-empty for the template picker")

        prog.saveNamedProgression(testName)
        let namesAfterSave = prog.savedProgressionNames()
        expect(namesAfterSave.contains(testName), "save: \"\(testName)\" appears in saved names (was \(namesAfterSave))")
        log("progression preview for saved name: \(prog.progressionPreview(testName) ?? "<empty>")")

        guard let template = prog.allTemplates.first else {
            expect(false, "no template available to apply — skipping template/load checks")
            cleanupLibraryTest(prog, testName)
            return
        }

        prog.confirmNewProgression(template: template, key: .g, tempo: 111)
        after(0.4) {
            self.expect(prog.key == Key.g, "confirmNewProgression applied key G (was \(prog.key.displayName))")
            self.expect(prog.tempo == 111, "confirmNewProgression applied tempo 111 (was \(prog.tempo))")
            self.expect(!prog.measures.isEmpty, "template produced at least one measure")

            prog.loadProgression(testName)
            self.after(0.4) {
                self.cleanupLibraryTest(prog, testName)
            }
        }
    }

    private func cleanupLibraryTest(_ prog: ProgressionModel, _ testName: String) {
        prog.deleteProgression(testName)
        let namesAfterDelete = prog.savedProgressionNames()
        expect(!namesAfterDelete.contains(testName), "delete: \"\(testName)\" removed from saved names")
        finish()
    }

    private func finish() {
        progress.done = true
        if failures.isEmpty {
            emit("SMOKE-SELFTEST: PASS")
        } else {
            for f in failures { emit("SMOKE-SELFTEST: FAILED — \(f)") }
            emit("SMOKE-SELFTEST: FAIL (\(failures.count) failure(s))")
        }
    }

    private func expect(_ condition: Bool, _ what: String) {
        if condition {
            log("ok — \(what)")
        } else {
            failures.append(what)
            log("FAIL — \(what)")
        }
    }

    private func after(_ seconds: Double, _ block: @escaping () -> Void) {
        DispatchQueue.main.asyncAfter(deadline: .now() + seconds, execute: block)
    }

    /// Records + logs entry into a step. The name is what the background watchdog reports if we wedge.
    private func step(_ name: String) {
        progress.step = name
        log(name)
    }

    private func log(_ msg: String) { emit("SMOKE-SELFTEST: \(msg)") }
}

/// Dedicated os_log subsystem the CI greps for. We can't rely on the app's *stdout* being captured:
/// `simctl launch --console-pty` redirected to a file goes empty on newer Xcode/simulator runner
/// images (the app runs fine, but not a byte reaches the file). The simulator's *unified log* is the
/// stable channel, so CI streams `log stream --predicate 'subsystem == "de.metaviewsoft.smoke"'`
/// instead. `privacy: .public` is required — os_log redacts interpolated strings as `<private>` by
/// default, which would blank out every sentinel.
private let smokeLog = Logger(subsystem: "de.metaviewsoft.smoke", category: "selftest")

/// Emits one line to BOTH stdout (kept for local `simctl launch --console` runs) and the unified log
/// (what CI actually reads). Prints + flushes stdout per line because CI *kills* the app at the end
/// of the window rather than letting it exit, so a buffered tail (including the PASS sentinel) would
/// be lost. Note: `setbuf(stdout, nil)` is NOT a valid alternative — it is undefined behavior once
/// the stream has been written to, and the Kotlin runtime already logs to stdout during startup, so
/// forcing it unbuffered corrupted the stream. A plain per-line `fflush` is well-defined and
/// thread-safe (stdio locks internally), so it also works from the background watchdog.
private func emit(_ line: String) {
    print(line)
    fflush(stdout)
    smokeLog.log("\(line, privacy: .public)")
}

/// Lock-guarded, non-isolated progress shared between the `@MainActor` self-test (writer, on main)
/// and the watchdog (reader, on a background queue). `@unchecked Sendable`: correctness comes from
/// the lock, which the compiler can't verify.
private final class Progress: @unchecked Sendable {
    private let lock = NSLock()
    private var _step = "init"
    private var _done = false

    var step: String {
        get { lock.lock(); defer { lock.unlock() }; return _step }
        set { lock.lock(); _step = newValue; lock.unlock() }
    }
    var done: Bool {
        get { lock.lock(); defer { lock.unlock() }; return _done }
        set { lock.lock(); _done = newValue; lock.unlock() }
    }
}
