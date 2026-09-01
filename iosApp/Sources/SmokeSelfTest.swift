import Foundation

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
        // CI captures this process's stdout via `simctl` and then *kills* the app at the end of the
        // smoke window rather than letting it exit — so a block-buffered stdout would strand the tail
        // (including the PASS sentinel) in the buffer, unflushed, and lost. Force unbuffered output so
        // every line reaches CI the moment it is printed.
        setbuf(stdout, nil)
        step("starting")
        startWatchdog()
        after(0.6) { self.checkInitialState() }
    }

    /// Runs on a background queue so it fires *even if the main thread is blocked* — the whole point is
    /// to tell a main-thread stall (no further step logs at all) apart from a normal failed assertion.
    /// A silent 20s CI timeout becomes an actionable FAIL that names the step we were stuck in.
    private func startWatchdog() {
        let progress = self.progress
        DispatchQueue.global().asyncAfter(deadline: .now() + 14.0) {
            guard !progress.done else { return }
            print("SMOKE-SELFTEST: FAIL — watchdog fired after 14s, stuck in step \"\(progress.step)\" "
                  + "(no PASS/FAIL reached; main thread likely blocked here)")
            print("SMOKE-SELFTEST: FAIL (watchdog)")
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
            self.finish()
        }
    }

    private func finish() {
        progress.done = true
        if failures.isEmpty {
            print("SMOKE-SELFTEST: PASS")
        } else {
            for f in failures { print("SMOKE-SELFTEST: FAILED — \(f)") }
            print("SMOKE-SELFTEST: FAIL (\(failures.count) failure(s))")
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

    private func log(_ msg: String) { print("SMOKE-SELFTEST: \(msg)") }
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
