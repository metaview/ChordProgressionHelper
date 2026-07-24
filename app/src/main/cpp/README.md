# Native Audio Engine

Diese native C++ Library implementiert Performance-kritische Audio-Synthese-Funktionen für die ChordProgressionHelper-App.

## Architektur

### Komponenten

1. **audio_engine.h/.cpp** - Kern der Audio-Synthese-Engine
   - Karplus-Strong String-Synthese für Saiteninstrumente
   - Additive Synthese für Piano-Sounds
   - Drum-Synthese (Kick, Snare, HiHat)
   - Utility-Funktionen (MIDI→Frequenz, PCM-Konvertierung)

2. **native_audio_jni.cpp** - JNI-Bindings
   - Verbindet C++ Engine mit Kotlin/Java-Code
   - Automatische Array-Konvertierung
   - Error-Handling und Logging

3. **NativeAudio.kt** - Kotlin-Wrapper
   - Typsichere Kotlin-API
   - Automatisches Resource-Management
   - Fallback zu Kotlin-Implementierung wenn Library nicht verfügbar

## Performance-Vorteile

Die native Implementierung bietet:

- **~3-5x schnellere Audio-Synthese** durch optimierten C++ Code
- **Geringere CPU-Last** durch `-ffast-math` und `-O3` Optimierungen
- **Weniger Garbage Collection** durch direkte Array-Manipulation
- **Bessere Audio-Latenz** durch schnellere Buffer-Generierung

## Fallback-Mechanismus

Der Code ist hybrid implementiert:

```kotlin
if (NativeAudio.isAvailable()) {
    // Native C++ Implementierung (schnell)
    NativeAudio.addKick(buffer, duration, levelScale, envelopeScale, drumLevel)
} else {
    // Kotlin Fallback (langsamer, aber funktioniert immer)
    // ... Kotlin implementation ...
}
```

Dies garantiert:
- ✅ App funktioniert auch wenn native Library fehlt
- ✅ Automatischer Fallback bei Laufzeitfehlern
- ✅ Einfaches Debugging durch Kotlin-Code

## Build-Konfiguration

### NDK-Version
- **Erforderlich:** NDK 27.0.12077973 oder höher
- **CMake:** 3.22.1+

### Unterstützte ABIs
- armeabi-v7a (32-bit ARM)
- arm64-v8a (64-bit ARM)
- x86 (32-bit Intel)
- x86_64 (64-bit Intel)

### Compiler-Flags
```cmake
-std=c++17         # C++17 Standard
-O3                # Maximum optimization
-ffast-math        # Fast floating-point math (acceptable for audio)
-Wall              # All warnings
```

## Nutzung

### Von Kotlin aus

```kotlin
// Automatisch: Native wenn verfügbar, sonst Kotlin
audioPlayer.playProgression(...)

// Manuell native API nutzen
if (NativeAudio.isAvailable()) {
    val buffer = DoubleArray(44100)
    NativeAudio.addKick(buffer, 22050, 1.0, 1.0, 1.0)
    
    // Karplus-Strong String
    NativeAudio.KarplusString(440.0, 44100, 3).use { string ->
        string.pluck()
        repeat(44100) { i ->
            buffer[i] = string.tick()
        }
    }
}
```

### Logging

Die Library loggt automatisch:
```
I/AudioPlayer: Native audio library status: LOADED
I/ChordHelper-Native: NativeAudio library loaded
```

Bei Fehlern:
```
E/NativeAudio: Failed to load native audio library: ...
W/AudioPlayer: Native addKick failed, using fallback: ...
```

## Entwicklung

### Lokaler Build

```bash
./gradlew :app:assembleDebug
```

### Native Code debuggen

1. Android Studio → Run → Edit Configurations
2. Debugger → Debug type: **Dual (Java + Native)**
3. Breakpoints in C++ Dateien setzen
4. Debug starten

### Performance-Profiling

```bash
# Native Code Profile
./gradlew :app:assembleRelease
adb shell simpleperf record -p <pid> -o /data/local/tmp/perf.data
adb pull /data/local/tmp/perf.data
simpleperf report -i perf.data
```

## Troubleshooting

### Library lädt nicht

**Problem:** `UnsatisfiedLinkError: dlopen failed`

**Lösung:**
1. NDK installiert? Android Studio → SDK Manager → SDK Tools → NDK
2. `Build → Clean Project → Rebuild Project`
3. ABI für Gerät vorhanden? Siehe `ndk { abiFilters ... }`

### Native Crashes

**Problem:** SIGSEGV / SIGABRT in native Code

**Lösung:**
1. Logcat checken: `adb logcat | grep -i native`
2. Native Debugger anhängen (siehe oben)
3. Array-Bounds prüfen (keine `buffer[i]` ohne Größen-Check)

### Build-Fehler

**Problem:** `No toolchains found in the NDK`

**Lösung:**
```gradle
// In app/build.gradle
ndkVersion "27.0.12077973"  // Explizite Version angeben
```

## Weitere Optimierungen (TODO)

- [ ] SIMD/NEON Optimierungen für ARM (2-4x speedup möglich)
- [ ] Buffer-Pooling in native Code
- [ ] OpenSL ES für direkten Audio-Output (geringere Latenz)
- [ ] Oboe Library für bessere Audio-Performance

## Lizenz

Siehe [LICENSE](../../../../../LICENSE) im Root-Verzeichnis.
