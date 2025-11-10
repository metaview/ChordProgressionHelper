# Chord Progression Helper - Feature Details

## Music Theory Implementation

### Keys
The app supports all 12 chromatic keys:
- C, C#, D, D#, E, F, F#, G, G#, A, A#, B

### Modes
Four musical modes are implemented:
1. **Major (Ionian)**: I, ii, iii, IV, V, vi, vii°
2. **Minor (Aeolian)**: i, ii°, III, iv, v, VI, VII
3. **Dorian**: i, ii, III, IV, v, vi°, VII
4. **Mixolydian**: I, ii, iii°, IV, v, vi, VII

### Chord Qualities
The following chord qualities are available:
- Major
- Minor
- Diminished
- Augmented
- Dominant 7th
- Major 7th
- Minor 7th
- Suspended 2nd
- Suspended 4th

## User Interface

### Key/Mode Selection
- Two spinners at the top allow selecting the key and mode
- Changing either automatically updates the available chords

### Chord Palette
- Displays all 7 scale degree chords horizontally
- Shows chord names (e.g., "C", "Dm") and Roman numerals (e.g., "I", "ii")
- Selected chord is highlighted

### Measure View
- Each measure shows 4 quarter note slots
- Displays chord name or "-" if empty
- Shows current strumming pattern
- Measures are numbered sequentially

### Controls
- **Play**: Starts playback of the progression
- **Stop**: Stops playback
- **Add Measure**: Adds a new measure at the end
- **Clear**: Removes all chords from all measures

## Audio Playback

The audio playback system uses Android's AudioTrack API with:
- Sine wave synthesis for chord tones
- ADSR envelope (Attack, Decay, Sustain, Release)
- Proper timing based on tempo (default 120 BPM)
- Quarter note resolution

## Technical Architecture

### MVVM Pattern
- **Model**: Data classes (Key, Mode, Chord, Measure, etc.)
- **View**: MainActivity with RecyclerViews
- **ViewModel**: ProgressionViewModel manages state and business logic

### Key Components
- **ChordAdapter**: Manages chord palette display
- **MeasureAdapter**: Manages measure list display
- **AudioPlayer**: Handles audio synthesis and playback
- **ProgressionViewModel**: Coordinates data and actions

### Testing
Unit tests cover:
- Chord theory correctness (scale degrees, modes)
- Chord placement and persistence
- Related chord generation
- Measure management
