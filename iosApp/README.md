# iOS app (scaffold)

SwiftUI front-end over the `:shared` KMP module. Status: **unverified scaffold** — written on
Linux, compiled only by the GitHub Actions workflow (`.github/workflows/ios.yml`); expect the
first CI runs to surface interop/naming fixes.

## Build (CI)

Pushes touching `shared/`, `iosApp/` or the workflow trigger the `iOS` workflow on a macOS
runner: it links the Kotlin framework for the simulator, generates the Xcode project with
XcodeGen and builds the app unsigned.

## Build (on a Mac)

```sh
brew install xcodegen
cd iosApp
xcodegen generate
open ChordProgressionHelper.xcodeproj   # build/run in Xcode (needs a recent Xcode; High Sierra's Xcode 10 is too old)
```

The Xcode pre-build phase runs `./gradlew :shared:embedAndSignAppleFrameworkForXcode
-PenableIosTargets=true` automatically. The `.xcodeproj` is generated and not committed;
`project.yml` is the source of truth.

## Architecture notes

- The Gradle iOS targets only exist when `-PenableIosTargets=true` is set, so the Linux/F-Droid
  Android build stays byte-identical.
- `IosAppEnvironment.shared` (shared/src/iosMain) is the composition root: installs the platform
  seams (NSLog logger, AVAudioEngine sink, GCD audio queue), builds storage/settings/session and
  the shared view-model cores, and exposes `IosPlaybackController` (replaces Android's
  PlaybackService).
- The native C++ audio engine is not wired up yet (`UnavailableNativeAudioBridge`); all synthesis
  runs on the pure-Kotlin fallback paths, which are feature-complete.
- Swift observes the cores' StateFlows via `FlowWatchKt.watch` (see `SongModel.swift`).
