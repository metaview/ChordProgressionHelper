import SwiftUI
import Shared

/// iOS counterpart of Android's SettingsActivity + StrummingTimingActivity. Presented as a sheet
/// from SongScreen's "..." menu. SettingsModel writes straight to the shared SettingsStore on
/// every change (no Save button, matching Android's direct-write listeners).
struct SettingsScreen: View {
    @StateObject private var model = SettingsModel()
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationView {
            Form {
                drumsSection
                begleitungSection
                soloSection
                rhythmSection
                countInSection
                previewsSection
                newProgressionDefaultsSection
                playbackSection
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .alert(
                "Change Key",
                isPresented: transposeBinding,
                presenting: model.transposeConfirmationNewKey
            ) { newKey in
                Button("Transpose") { model.confirmSongTranspose(transpose: true) }
                Button("Set Key Only") { model.confirmSongTranspose(transpose: false) }
                Button("Cancel", role: .cancel) { model.cancelSongTranspose() }
            } message: { newKey in
                Text("Transpose existing chords to ") + Text(verbatim: newKey.displayName) + Text(" as well?")
            }
        }
    }

    private var transposeBinding: Binding<Bool> {
        Binding(
            get: { model.transposeConfirmationNewKey != nil },
            set: { if !$0 { model.cancelSongTranspose() } }
        )
    }

    // MARK: - Drums

    private var drumsSection: some View {
        Section("Drums") {
            percentSlider("Level", value: $model.drumLevelPercent)
            percentSlider("Envelope", value: $model.envelopeScalePercent)
            percentSlider("Hi-Hat Highpass", value: $model.hiHatHighpassPercent)
        }
    }

    // MARK: - Begleitung (Strumming)

    private var begleitungSection: some View {
        Section("Begleitung") {
            percentSlider("Level", value: $model.strumLevelPercent)
            soundPresetPicker("Sound", selection: $model.strumPreset)
            percentSlider("Crunch", value: $model.strumCrunchPercent)
            Button { model.previewStrum() } label: {
                Label("Preview", systemImage: "play.circle")
            }
            NavigationLink("Strum Timing") {
                StrumTimingScreen(model: model)
            }
        }
    }

    // MARK: - Solo

    private var soloSection: some View {
        Section("Solo") {
            percentSlider("Level", value: $model.soloLevelPercent)
            soundPresetPicker("Sound", selection: $model.soloPreset)
            percentSlider("Crunch", value: $model.soloCrunchPercent)
            Button { model.previewSolo() } label: {
                Label("Preview", systemImage: "play.circle")
            }
        }
    }

    private func soundPresetPicker(_ title: LocalizedStringKey, selection: Binding<SoundPreset>) -> some View {
        // Binds through the enum's plain Int ordinal — Picker's native selection binding needs
        // reliable Hashable/Equatable, which this codebase avoids trusting for bridged Kotlin
        // objects (see ProgressionScreen's key Menu, built the same way for the same reason).
        let ordinalBinding = Binding<Int32>(
            get: { selection.wrappedValue.ordinal },
            set: { newOrdinal in
                if let preset = SoundPreset.entries.first(where: { $0.ordinal == newOrdinal }) {
                    selection.wrappedValue = preset
                }
            }
        )
        return Picker(title, selection: ordinalBinding) {
            Text("Clean").tag(SoundPreset.clean.ordinal)
            Text("Overdrive").tag(SoundPreset.overdrive.ordinal)
            Text("Piano").tag(SoundPreset.piano.ordinal)
        }
        .pickerStyle(.segmented)
    }

    // MARK: - Rhythm

    private var rhythmSection: some View {
        Section("Rhythm") {
            percentSlider("Shuffle", value: $model.shuffleFactorPercent)
        }
    }

    // MARK: - Count-in

    private var countInSection: some View {
        Section("Count-In") {
            countInPicker("Progression", selection: $model.countInBeats)
            countInPicker("Song", selection: $model.countInBeatsSong)
        }
    }

    private func countInPicker(_ title: LocalizedStringKey, selection: Binding<Int32>) -> some View {
        Picker(title, selection: selection) {
            Text("Off").tag(Int32(0))
            Text(verbatim: "2").tag(Int32(2))
            Text(verbatim: "4").tag(Int32(4))
            Text(verbatim: "8").tag(Int32(8))
        }
    }

    // MARK: - Previews

    private var previewsSection: some View {
        Section("Previews") {
            Toggle("Chord Preview", isOn: $model.isChordPreviewEnabled)
            Toggle("Pattern Preview", isOn: $model.isPatternPreviewEnabled)
            Toggle("Drum Preview", isOn: $model.isDrumPreviewEnabled)
            Toggle("Template Preview", isOn: $model.isTemplatePreviewEnabled)
        }
    }

    // MARK: - New progression defaults

    private var newProgressionDefaultsSection: some View {
        Section("New Progression Defaults") {
            HStack {
                Text("Default Key")
                Spacer()
                KeyPickerButton(keys: model.allKeys, selectedKey: model.defaultKey, showsIcon: false) {
                    model.defaultKey = $0
                }
                .foregroundStyle(.secondary)
            }
            Stepper(value: $model.defaultBpm, in: 60...240) {
                HStack {
                    Text("Default Tempo")
                    Spacer()
                    Text(verbatim: "\(model.defaultBpm)")
                        .foregroundStyle(.secondary)
                }
            }
        }
    }

    // MARK: - Playback

    private var playbackSection: some View {
        Section("Playback") {
            Toggle("Loop New Progressions", isOn: $model.isLoopingProgressionEnabled)
        }
    }

    // MARK: - Shared slider row

    private func percentSlider(_ title: LocalizedStringKey, value: Binding<Double>) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                Text(title)
                Spacer()
                Text(verbatim: "\(Int(value.wrappedValue.rounded()))%")
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
            }
            Slider(value: value, in: 0...200, step: 1)
        }
    }
}

/// Fine strum-timing controls (up/down stroke offset + string stagger, in ms) — Android's
/// separate StrummingTimingActivity, reachable from the main Settings screen here instead.
struct StrumTimingScreen: View {
    @ObservedObject var model: SettingsModel

    var body: some View {
        Form {
            Section("Up Stroke") {
                msSlider("Stroke Offset", value: $model.upStrokeOffsetMs)
                msSlider("String Stagger", value: $model.upStringStaggerMs)
            }
            Section("Down Stroke") {
                msSlider("Stroke Offset", value: $model.downStrokeOffsetMs)
                msSlider("String Stagger", value: $model.downStringStaggerMs)
            }
            Section {
                Button(role: .destructive) { model.resetStrumTiming() } label: {
                    Text("Reset Timing Defaults")
                }
            }
        }
        .navigationTitle("Strum Timing")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func msSlider(_ title: LocalizedStringKey, value: Binding<Int32>) -> some View {
        let doubleBinding = Binding<Double>(
            get: { Double(value.wrappedValue) },
            set: { value.wrappedValue = Int32($0.rounded()) }
        )
        return VStack(alignment: .leading, spacing: 2) {
            HStack {
                Text(title)
                Spacer()
                Text(verbatim: "\(value.wrappedValue)ms")
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
            }
            Slider(value: doubleBinding, in: 0...100, step: 1)
        }
    }
}
