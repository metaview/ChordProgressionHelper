import SwiftUI
import Shared

@main
struct ChordProgressionHelperApp: App {
    // Touching the environment here runs the shared module's platform installation
    // (loggers, audio seams, storage) before any view appears.
    private let env = IosAppEnvironment.companion.shared

    var body: some Scene {
        WindowGroup {
            SongScreen(model: SongModel(env: env))
        }
    }
}
