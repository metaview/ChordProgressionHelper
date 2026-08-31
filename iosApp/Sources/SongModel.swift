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
    }

    deinit {
        handles.forEach { $0.close() }
    }

    func selectSection(_ index: Int) {
        _ = core.selectSongSection(index: Int32(index))
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
}
