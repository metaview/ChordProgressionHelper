#!/usr/bin/env bash
#
# verify-reproducible.sh — prueft, ob der Release-Build byte-identisch reproduzierbar ist
#
# WARUM
#   F-Droid baut deine App aus dem Quellcode in einem ANDEREN Verzeichnis als du
#   und vergleicht das Ergebnis byte-genau mit deinem hochgeladenen APK
#   (Binary-Verifikation). Baut man zweimal am selben Pfad, versteckt das genau
#   die haeufigste Fehlerquelle: absolute Build-Pfade, die in die .so-Dateien
#   sickern. Dieses Skript baut deshalb in ZWEI verschiedenen Pfaden und
#   vergleicht die APK-Eintraege (ohne META-INF/, da die Signatur ohnehin
#   ausgeklammert wird).
#
# NUTZUNG
#   ./tools/verify-reproducible.sh
#
#   Exit 0 = reproduzierbar (alle Eintraege identisch)
#   Exit 1 = Unterschiede gefunden (werden aufgelistet)
#

set -euo pipefail

# ----- Farben (nur wenn Terminal) ------------------------------------------
if [[ -t 1 ]]; then
    C_CYAN=$'\e[36m'; C_GREEN=$'\e[32m'; C_GRAY=$'\e[90m'; C_RED=$'\e[31m'; C_YELLOW=$'\e[33m'; C_RESET=$'\e[0m'
else
    C_CYAN=''; C_GREEN=''; C_GRAY=''; C_RED=''; C_YELLOW=''; C_RESET=''
fi
info()  { printf '%s%s%s\n' "$C_GREEN"  "$*" "$C_RESET"; }
detail(){ printf '%s%s%s\n' "$C_GRAY"   "$*" "$C_RESET"; }
warn()  { printf '%s%s%s\n' "$C_YELLOW" "$*" "$C_RESET" >&2; }
err()   { printf '%s%s%s\n' "$C_RED"    "$*" "$C_RESET" >&2; }

# ----- Projekt-Root = ein Verzeichnis ueber diesem Skript (tools/) ---------
ScriptDir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"
RepoRoot="$(dirname -- "$ScriptDir")"

command -v unzip     >/dev/null || { err 'unzip fehlt (sudo apt install unzip).'; exit 1; }
command -v rsync     >/dev/null || { err 'rsync fehlt (sudo apt install rsync).'; exit 1; }
command -v sha256sum >/dev/null || { err 'sha256sum fehlt.'; exit 1; }

ApkRel='app/build/outputs/apk/release/app-release.apk'

# ----- Zwei klar verschiedene Build-Pfade (unterschiedliche Laenge!) -------
# Unterschiedliche Pfadlaengen decken Padding-/Pfad-Leaks besser auf.
WorkA="$(mktemp -d /tmp/cph-repro-a.XXXX)"
WorkB="$(mktemp -d /tmp/cph-repro-bbbbbbbbbbbbbb.XXXX)"
cleanup() { rm -rf "$WorkA" "$WorkB"; }
trap cleanup EXIT

# ----- Arbeitskopien anlegen (ohne Build-Artefakte, mit local.properties) --
# local.properties wird mitkopiert, damit der SDK-Pfad gefunden wird; die
# Signatur ist fuer den Vergleich egal (META-INF/ wird ignoriert).
sync_tree() {
    rsync -a --delete \
        --exclude '.git/' \
        --exclude 'build/' \
        --exclude '.gradle/' \
        --exclude '**/build/' \
        --exclude '**/.cxx/' \
        "$RepoRoot/." "$1/"
}

build_in() {
    local dir="$1" label="$2"
    info "[$label] Baue Release-APK in $dir ..."
    ( cd "$dir" && ./gradlew --no-configuration-cache :app:assembleRelease -q )
    [[ -f "$dir/$ApkRel" ]] || { err "[$label] APK nicht gefunden: $dir/$ApkRel"; exit 1; }
}

# ----- Per-Eintrag-Hashes eines APK (ohne META-INF/) -----------------------
entry_hashes() {
    local apk="$1" out="$2"
    local ex; ex="$(mktemp -d)"
    unzip -qq -o "$apk" -d "$ex"
    ( cd "$ex" && find . -type f ! -path './META-INF/*' -exec sha256sum {} \; ) \
        | sed 's|  \./|  |' | sort -k2 > "$out"
    rm -rf "$ex"
}

printf '\n'
detail "Build A: $WorkA"
detail "Build B: $WorkB"
printf '\n'

sync_tree "$WorkA"
sync_tree "$WorkB"

build_in "$WorkA" 'A'
build_in "$WorkB" 'B'

HashA="$(mktemp)"; HashB="$(mktemp)"
entry_hashes "$WorkA/$ApkRel" "$HashA"
entry_hashes "$WorkB/$ApkRel" "$HashB"

printf '\n'
info 'Vergleiche APK-Eintraege (ohne META-INF/) ...'

# ----- Vergleich -----------------------------------------------------------
if diff -q "$HashA" "$HashB" >/dev/null; then
    printf '\n%sREPRODUZIERBAR: alle Eintraege identisch.%s\n' "$C_GREEN" "$C_RESET"
    rm -f "$HashA" "$HashB"
    exit 0
fi

err ''
err 'NICHT reproduzierbar - abweichende Eintraege:'
# Nur die Namen der abweichenden Dateien zeigen (2. Spalte = Pfad).
join -j 2 -o '0,1.1,2.1' <(sort -k2 "$HashA") <(sort -k2 "$HashB") 2>/dev/null \
    | awk '$2 != $3 { print "  differ:   " $1 }'
comm -23 <(awk '{print $2}' "$HashA" | sort) <(awk '{print $2}' "$HashB" | sort) \
    | sed 's/^/  nur in A: /'
comm -13 <(awk '{print $2}' "$HashA" | sort) <(awk '{print $2}' "$HashB" | sort) \
    | sed 's/^/  nur in B: /'

printf '\n'
warn 'Tipp: Abweichungen unter lib/**/*.so deuten auf Pfad-/Build-Umgebungs-Leaks'
warn 'im Native-Code hin (siehe -ffile-prefix-map / debugSymbolLevel in app/build.gradle).'
rm -f "$HashA" "$HashB"
exit 1
