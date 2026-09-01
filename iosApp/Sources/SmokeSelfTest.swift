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

    init(model: SongModel) { self.model = model }

    /// StateFlow deliveries hop through Dispatchers.Main, so between an action and its assertion we
    /// yield to the run loop. Steps are chained with small delays rather than read synchronously,
    /// precisely so the async bridge (not a direct `.value` read) is what we verify.
    func run() {
        log("starting")
        after(0.6) { self.checkInitialState() }
    }

    private func checkInitialState() {
        expect(!model.songName.isEmpty, "initial songName non-empty (was \"\(model.songName)\")")
        expect(!model.sectionNames.isEmpty,
               "initial sectionNames non-empty (was \(model.sectionNames))")
        expect(model.tempoPercent == 100,
               "initial tempoPercent == 100 (was \(model.tempoPercent))")
        log("initial state: songName=\"\(model.songName)\" sections=\(model.sectionNames) tempo=\(model.tempoPercent)%")
        after(0.4) { self.checkTempoRoundTrip() }
    }

    private func checkTempoRoundTrip() {
        let before = model.tempoPercent
        model.incrementTempoPercent()
        after(0.4) {
            self.expect(self.model.tempoPercent == before + 1,
                        "tempo increment round-trips \(before) -> \(before + 1) (was \(self.model.tempoPercent))")
            self.checkLoopRoundTrip()
        }
    }

    private func checkLoopRoundTrip() {
        let before = model.isLooping
        model.toggleLooping()
        after(0.4) {
            self.expect(self.model.isLooping == !before,
                        "loop toggle round-trips \(before) -> \(!before) (was \(self.model.isLooping))")
            self.finish()
        }
    }

    private func finish() {
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

    private func log(_ msg: String) { print("SMOKE-SELFTEST: \(msg)") }
}
