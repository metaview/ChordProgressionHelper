# UI Overview - Chord Progression Helper

## Main Screen Layout

```
┌─────────────────────────────────────────────┐
│   Chord Progression Helper                  │
│                                              │
│  Key: [C  ▼]     Mode: [Major ▼]           │
│                                              │
│  Available Chords:                           │
│  ┌───┐ ┌───┐ ┌───┐ ┌───┐ ┌───┐ ┌───┐ ┌───┐│
│  │ C │ │Dm │ │Em │ │ F │ │ G │ │Am │ │Bdim││
│  │ I │ │ii │ │iii│ │IV │ │ V │ │vi │ │vii°││
│  └───┘ └───┘ └───┘ └───┘ └───┘ └───┘ └───┘│
│                                              │
│  Progression:                                │
│  ┌────────────────────────────────────────┐ │
│  │ Measure 1            D D D D          │ │
│  │ ┌───┐ ┌───┐ ┌───┐ ┌───┐              │ │
│  │ │ C │ │ - │ │ - │ │ - │              │ │
│  │ └───┘ └───┘ └───┘ └───┘              │ │
│  └────────────────────────────────────────┘ │
│  ┌────────────────────────────────────────┐ │
│  │ Measure 2            D D D D          │ │
│  │ ┌───┐ ┌───┐ ┌───┐ ┌───┐              │ │
│  │ │ F │ │ - │ │ G │ │ - │              │ │
│  │ └───┘ └───┘ └───┘ └───┘              │ │
│  └────────────────────────────────────────┘ │
│  ┌────────────────────────────────────────┐ │
│  │ Measure 3            D U D U          │ │
│  │ ┌───┐ ┌───┐ ┌───┐ ┌───┐              │ │
│  │ │Am │ │ - │ │ - │ │ - │              │ │
│  │ └───┘ └───┘ └───┘ └───┘              │ │
│  └────────────────────────────────────────┘ │
│  ┌────────────────────────────────────────┐ │
│  │ Measure 4            D D D D          │ │
│  │ ┌───┐ ┌───┐ ┌───┐ ┌───┐              │ │
│  │ │ G │ │ - │ │ C │ │ - │              │ │
│  │ └───┘ └───┘ └───┘ └───┘              │ │
│  └────────────────────────────────────────┘ │
│                                              │
│  [ Play ]  [ Stop ]  [ Add Measure ] [Clear]│
└─────────────────────────────────────────────┘
```

## User Interactions

### 1. Key and Mode Selection
- **Spinners at top**: Tap to open dropdown
- **12 Keys**: C, C#, D, D#, E, F, F#, G, G#, A, A#, B
- **4 Modes**: Major, Minor, Dorian, Mixolydian
- **Effect**: Changes available chords immediately

### 2. Chord Selection (Horizontal Scroll)
- **Tap a chord**: Highlights it (blue background)
- **Long press a chord**: Starts drag operation for drag & drop placement
- **Shows**: Chord name (e.g., "C", "Dm") and Roman numeral (e.g., "I", "ii")
- **7 chords**: Based on scale degrees of selected key/mode

### 3. Measure Grid
- **Each measure has**:
  - Measure number (e.g., "Measure 1")
  - Strumming pattern (tap to change)
  - 4 quarter note slots (48dp height for compact display)
  
- **Quarter Note Slots**:
  - Shows "-" if empty
  - Shows chord name if filled (e.g., "C", "Dm")
  - **Tap**: Place selected chord here
  - **Drag & drop**: Drag a chord from the palette and drop it here
  - **Visual feedback**: Slot becomes semi-transparent when chord is dragged over it
  - **Long press**: Remove chord from this slot

### 4. Strumming Patterns
- **Tap pattern text** to open dialog
- **6 Options**:
  1. D D D D (Down Down Down Down)
  2. D D U U D U (Down Down Up Up Down Up)
  3. D U D U (Down Up Down Up)
  4. D X U D U (Down Mute Up Down Up)
  5. D (Single Down)
  6. Fingerpicking

### 5. Control Buttons
- **Play**: Start playback (button disabled during playback)
- **Stop**: Stop playback (enabled only during playback)
- **Add Measure**: Adds new empty measure at end
- **Clear**: Removes all chords from all measures

## Example Usage Flow

### Method 1: Tap to Place
1. **Select Key**: Tap "Key" spinner → Select "C"
2. **Select Mode**: Tap "Mode" spinner → Select "Major"
3. **View Chords**: See I (C), ii (Dm), iii (Em), IV (F), V (G), vi (Am), vii° (Bdim)
4. **Select Chord**: Tap "C" chord → It becomes highlighted
5. **Place Chord**: Tap first quarter note in Measure 1 → "C" appears
6. **Continue**: Select "F", place in Measure 2, etc.
7. **Change Pattern**: Tap "D D D D" → Select "D U D U"
8. **Play**: Tap "Play" → Hear progression with selected tempo and patterns
9. **Stop**: Tap "Stop" if needed
10. **Modify**: Long press any chord to remove, or add more measures

### Method 2: Drag & Drop
1. **Select Key & Mode**: Same as Method 1
2. **Long Press Chord**: Long press "C" chord → Drag shadow appears
3. **Drag**: Move finger to desired quarter note slot → Slot becomes semi-transparent
4. **Drop**: Release finger → "C" appears in the slot
5. **Continue**: Drag and drop other chords as needed

## Color Coding
- **Selected Chord**: Light blue background (#BBDEFB)
- **Normal Chord**: White background
- **Quarter Note Empty**: Light gray background (#F5F5F5)
- **Quarter Note Filled**: Light green background (#C8E6C9)

## Chord Persistence Example

```
Measure with chords at positions 0 and 2:
┌───┐ ┌───┐ ┌───┐ ┌───┐
│ C │ │ - │ │ G │ │ - │
└───┘ └───┘ └───┘ └───┘
  0     1     2     3

Actual playback:
Beat 0: Play C
Beat 1: Continue C (no new chord)
Beat 2: Play G
Beat 3: Continue G (no new chord)
```

## Responsive Design
- **Scrolling**: Chord palette scrolls horizontally
- **Scrolling**: Measure list scrolls vertically
- **Adapts**: Works on phones and tablets
- **Material Design**: Follows Android design guidelines

## Accessibility
- **Touch Targets**: All interactive elements are at least 48dp
- **Contrast**: Good color contrast for readability
- **Feedback**: Visual feedback on all interactions
