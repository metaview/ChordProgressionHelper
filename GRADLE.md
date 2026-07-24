# Top 10 Gradle-Befehle für den täglichen Workflow

1. **./gradlew app:build** – Vollständiger Build (kompilieren + testen)
2. **./gradlew app:clean** – Build-Verzeichnis löschen (bei Problemen)
3. **./gradlew app:compileDebugKotlin** – Schnelle Kotlin-Syntaxprüfung
4. **./gradlew app:installDebug** – APK auf Emulator/Gerät installieren
5. **./gradlew app:lint** – Code-Qualitätsprüfung
6. **./gradlew app:test** – Unit-Tests ausführen
7. **./gradlew app:assembleDebug** – Debug-APK bauen (ohne Tests)
8. **./gradlew app:connectedDebugAndroidTest** – Instrumented Tests auf Gerät
9. **./gradlew app:bundleRelease** – Release-Bundle für Play Store
10. **./gradlew app:lintFix** – Lint-Probleme automatisch beheben

---

## Alle verfügbaren Task-Gruppen

### Build
```bash
./gradlew app:assemble
./gradlew app:assembleDebug
./gradlew app:assembleRelease
./gradlew app:build
./gradlew app:clean
./gradlew app:bundle
./gradlew app:bundleRelease
```

### Kompilieren
```bash
./gradlew app:compileDebugKotlin
./gradlew app:compileReleaseKotlin
./gradlew app:compileDebugJavaWithJavac
```

### Tests und Checks
```bash
./gradlew app:test
./gradlew app:testDebugUnitTest
./gradlew app:check
./gradlew app:lint
./gradlew app:lintDebug
./gradlew app:lintRelease
./gradlew app:connectedDebugAndroidTest
```

### Installieren auf Gerät/Emulator
```bash
./gradlew app:installDebug
./gradlew app:installRelease
./gradlew app:uninstallDebug
./gradlew app:uninstallAll
```

### Hilfreiche Info-Befehle
```bash
./gradlew tasks
./gradlew tasks --all
./gradlew app:tasks --all
./gradlew dependencies
./gradlew app:dependencies
./gradlew projects
./gradlew properties
```

---

## App aus der Konsole starten

**Build + Install + Run (schnellste Variante):**
```bash
./gradlew app:installDebug && adb shell am start -n de.metaviewsoft.chordprogressionhelper.debug/de.metaviewsoft.chordprogressionhelper.ProgressionActivity
```

**Nur starten (wenn bereits installiert):**
```bash
adb shell am start -n de.metaviewsoft.chordprogressionhelper.debug/de.metaviewsoft.chordprogressionhelper.ProgressionActivity
```

**Release-Version starten:**
```bash
adb shell am start -n de.metaviewsoft.chordprogressionhelper/de.metaviewsoft.chordprogressionhelper.ProgressionActivity
```

**Auf spezifischem Gerät starten:**
```bash
adb -s <device_id> shell am start -n de.metaviewsoft.chordprogressionhelper.debug/de.metaviewsoft.chordprogressionhelper.ProgressionActivity
```

**Mit Logs im gleichen Terminal:**
```bash
./gradlew app:installDebug && adb shell am start -n de.metaviewsoft.chordprogressionhelper.debug/de.metaviewsoft.chordprogressionhelper.ProgressionActivity && adb logcat
```

**Alle verbundenen Geräte/Emulatoren anzeigen:**
```bash
adb devices
```

**App stoppen:**
```bash
adb shell am force-stop de.metaviewsoft.chordprogressionhelper.debug
```
