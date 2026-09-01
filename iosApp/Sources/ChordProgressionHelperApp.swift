import SwiftUI
import Shared

@main
struct ChordProgressionHelperApp: App {
    private let model: SongModel

    // Retained for the duration of the run so it isn't deallocated mid-test. nil unless CPH_SMOKE=1.
    private let selfTest: SmokeSelfTest?

    init() {
        // Touching the environment here runs the shared module's platform installation
        // (loggers, audio seams, storage) before any view appears.
        let env = IosAppEnvironment.companion.shared
        let model = SongModel(env: env)
        self.model = model
        self.selfTest = SmokeSelfTest.isEnabled ? SmokeSelfTest(model: model) : nil
    }

    var body: some Scene {
        WindowGroup {
            SongScreen(model: model)
                .onAppear { selfTest?.run() }
        }
    }
}
