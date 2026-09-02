# Chord Progression Helper

An Android app for creating and playing chord progressions.

## Features

- **Key and Mode Selection**: Choose from various musical keys and modes (Major, Minor, Dorian, Mixolydian)
- **Scale Degree Chords**: Automatically displays the correct scale degree chords based on selected key and mode
- **Drag and Drop Interface**: Easily place chords into measures using a quarter note grid
- **Chord Persistence**: Chords remain active until the next chord is placed
- **Related Chords**: Select additional chord variations based on the current chord
- **Strumming Patterns**: Customize strumming patterns for each measure
- **Playback**: Play your chord progression with audio synthesis

## Building

This is a standard Android project using Gradle. To build:

```bash
./gradlew build
```

To run on an emulator or device:

```bash
./gradlew installDebug
```

## Usage

1. Select a musical key from the dropdown (C, D, E, etc.)
2. Select a mode (Major, Minor, Dorian, or Mixolydian)
3. Available scale degree chords will be displayed
4. **To place a chord** (two methods):
   - **Tap method**: Tap a chord to select it, then tap a quarter note slot in a measure to place it
   - **Drag & drop method**: Long press a chord and drag it to any quarter note slot, then release
5. Long press a quarter note slot to remove a chord
6. Tap the strumming pattern text to change it for that measure
7. Use the "Add Measure" button to add more measures
8. Press "Play" to hear your progression
9. Press "Clear" to start over

## Requirements

- Android SDK 24 (Android 7.0) or higher
- Android Studio for development