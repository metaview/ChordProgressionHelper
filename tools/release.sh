#!/usr/bin/env bash
#
# release.sh — Ein-Klick-Release fuer Ubuntu/Bash (Portierung von release.ps1)
#
# EINZIGE MANUELLE VORARBEIT
#   In gradle.properties APP_VERSION_CODE und APP_VERSION_NAME hochsetzen.
#   Danach nur noch:  ./tools/release.sh
#
# WAS DAS SKRIPT AUTOMATISCH MACHT (damit man nichts vergisst)
#   1. Versionscode fest in app/build.gradle schreiben (F-Droid liest ihn so aus dem Tag)
#   2. app/build.gradle + gradle.properties committen
#   3. Git-Tag v<version> anlegen
#   4. Commit + Tag nach GitLab pushen
#   5. Release-APK bauen
#   6. APK ins GitLab Package Registry hochladen
#   7. GitLab-Release erstellen
#   8. APK als Release-Asset verlinken
#
#   Version und Versionscode kommen ausschliesslich aus gradle.properties — eine
#   einzige Quelle, kein Vertippen, kein separates Taggen von Hand.
#
# TOKEN
#   GitLab Personal Access Token (api-scope) per Umgebungsvariable GITLAB_TOKEN
#   oder interaktiv (verdeckte Eingabe).
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

# ----- Konfiguration -------------------------------------------------------
ProjectId='77601409'
PackageName='ChordProgressionHelper'
ApiBase="https://gitlab.com/api/v4/projects/${ProjectId}"

# Projekt-Root = ein Verzeichnis ueber diesem Skript (tools/)
ScriptDir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"
RepoRoot="$(dirname -- "$ScriptDir")"
cd "$RepoRoot"

PropsFile="$RepoRoot/gradle.properties"
GradleFile="$RepoRoot/app/build.gradle"

# ----- Version + Versionscode aus gradle.properties lesen ------------------
read_prop() { grep -E "^[[:space:]]*$1[[:space:]]*=" "$PropsFile" | head -1 | cut -d'=' -f2 | tr -d '[:space:]'; }
Version="$(read_prop APP_VERSION_NAME)"
AppVersionCode="$(read_prop APP_VERSION_CODE)"

if [[ -z "$Version" ]]; then err 'APP_VERSION_NAME fehlt in gradle.properties.'; exit 1; fi
if [[ ! "$AppVersionCode" =~ ^[0-9]+$ ]]; then
    err "APP_VERSION_CODE ist kein gueltiger Integer: '$AppVersionCode'"; exit 1
fi
Tag="v${Version}"
Branch="$(git rev-parse --abbrev-ref HEAD)"

printf '\n'
printf 'aus gradle.properties:\n'
printf '%sVersion      : %s%s\n' "$C_CYAN" "$Version" "$C_RESET"
printf '%sVersionscode : %s%s\n' "$C_CYAN" "$AppVersionCode" "$C_RESET"
printf '%sTag          : %s%s\n' "$C_CYAN" "$Tag" "$C_RESET"
printf '%sBranch       : %s%s\n' "$C_CYAN" "$Branch" "$C_RESET"
printf 'Changelog für Versionscode angelegt?\n'
printf '\n'

# ----- Auf andere, noch nicht committete Aenderungen hinweisen -------------
OtherChanges="$(git status --porcelain --untracked-files=no | grep -vE ' (app/build\.gradle|gradle\.properties)$' || true)"
if [[ -n "$OtherChanges" ]]; then
    warn 'Achtung: es gibt weitere uncommittete Aenderungen, die NICHT ins Release-Tag kommen:'
    printf '%s\n' "$OtherChanges" >&2
    printf '\n'
fi

# ----- Einmalige Bestaetigung ----------------------------------------------
read -r -p "Release $Tag jetzt committen, taggen, pushen und veroeffentlichen? (j/N) " ok
[[ "$ok" =~ ^[jJyY]$ ]] || { err 'Abgebrochen.'; exit 1; }

# ----- Token abfragen (verdeckt) -------------------------------------------
Token="${GITLAB_TOKEN:-}"
if [[ -z "$Token" ]]; then
    read -r -s -p 'GitLab Personal Access Token (api-scope): ' Token
    printf '\n'
fi
if [[ -z "$Token" ]]; then err 'Kein Token angegeben.'; exit 1; fi

# ----- 1. Versionscode fest in build.gradle schreiben ----------------------
info "[1/8] Schreibe versionCode $AppVersionCode fest in app/build.gradle ..."
# Ersetzt die 'versionCode ...'-Zeile (egal ob Integer.parseInt(...) oder Literal); Einrueckung bleibt.
sed -i -E "s/^([[:space:]]*)versionCode[[:space:]].*/\1versionCode ${AppVersionCode}/" "$GradleFile"
if ! grep -qE "^[[:space:]]*versionCode[[:space:]]+${AppVersionCode}[[:space:]]*$" "$GradleFile"; then
    err "Konnte versionCode nicht in $GradleFile setzen."; exit 1
fi

# ----- 2. Versionsdateien committen ----------------------------------------
info "[2/8] Committe Versionsdateien ..."
git add "$GradleFile" "$PropsFile"
if git diff --cached --quiet; then
    detail "      Nichts zu committen (Version bereits eingetragen)."
else
    git commit -m "Release $Tag" >/dev/null
    detail "      Commit 'Release $Tag' erstellt."
fi

# ----- 3. Tag anlegen ------------------------------------------------------
info "[3/8] Lege Git-Tag '$Tag' an ..."
if git rev-parse -q --verify "refs/tags/$Tag" >/dev/null; then
    detail "      Tag existiert bereits lokal - ok."
else
    git tag -a "$Tag" -m "Release $Tag"
    detail "      Tag '$Tag' erstellt."
fi

# ----- 4. Push (Commit + Tag) ----------------------------------------------
info "[4/8] Pushe Branch '$Branch' und Tag '$Tag' nach GitLab ..."
git push origin "$Branch"
git push origin "$Tag" || warn "Tag-Push meldete einen Fehler (existiert er evtl. schon auf GitLab?) - fahre fort."

# ----- 5. APK aus sauberem Tag-Checkout bauen ------------------------------
#   F-Droid baut aus einem pristinen Baum (git checkout -f <tag> + git clean -dffx).
#   Damit die veroeffentlichte Referenz-APK BYTE-genau dem entspricht, was F-Droid
#   reproduziert, bauen wir NICHT im (moeglicherweise verschmutzten) Arbeits-
#   verzeichnis, sondern in einem wegwerfbaren git-worktree des Tags.
info "[5/8] Baue Release-APK aus sauberem Checkout von '$Tag' ..."
BuildDir="$(mktemp -d)"
cleanup_worktree() {
    [[ -n "${BuildDir:-}" && -d "$BuildDir" ]] || return 0
    git worktree remove --force "$BuildDir" 2>/dev/null || rm -rf "$BuildDir"
    git worktree prune 2>/dev/null || true
}
trap cleanup_worktree EXIT
git worktree add --detach "$BuildDir" "$Tag"
# Signing-Konfiguration (gitignored, absolute Keystore-/SDK-Pfade) in den Worktree
# kopieren, damit assembleRelease dort signieren kann.
if [[ -f "$RepoRoot/local.properties" ]]; then
    cp "$RepoRoot/local.properties" "$BuildDir/local.properties"
fi
( cd "$BuildDir" && ./gradlew clean assembleRelease )
ApkPath="$BuildDir/app/build/outputs/apk/release/app-release.apk"
if [[ ! -f "$ApkPath" ]]; then err "APK nicht gefunden unter: $ApkPath"; exit 1; fi
apkSize="$(awk "BEGIN { printf \"%.2f\", $(stat -c%s "$ApkPath") / 1048576 }")"
detail "      APK ok (sauberer Tag-Build): $ApkPath ($apkSize MB)"

# ----- 6. APK hochladen (PUT) ----------------------------------------------
PackageUrl="${ApiBase}/packages/generic/${PackageName}/${Version}/app-release.apk"
info "[6/8] Lade APK hoch -> $PackageUrl"
upBody="$(curl --fail-with-body -sS -X PUT \
    --header "PRIVATE-TOKEN: ${Token}" \
    --header 'Content-Type: application/octet-stream' \
    --upload-file "$ApkPath" \
    "$PackageUrl")" || { err "Upload fehlgeschlagen: $upBody"; exit 1; }
detail "      Upload ok: ${upBody}"

# ----- 7. Release erstellen (falls noch nicht vorhanden) -------------------
info "[7/8] Erstelle Release '$Tag' ..."
httpCode="$(curl -sS -o /dev/null -w '%{http_code}' \
    --header "PRIVATE-TOKEN: ${Token}" \
    "${ApiBase}/releases/${Tag}")"
if [[ "$httpCode" == "200" ]]; then
    detail "      Release existiert bereits - ueberspringe Erstellung."
else
    relBody="$(curl --fail-with-body -sS -X POST \
        --header "PRIVATE-TOKEN: ${Token}" \
        --header 'Content-Type: application/json' \
        --data "$(printf '{"tag_name":"%s","name":"Release %s","description":"Release %s"}' "$Tag" "$Tag" "$Tag")" \
        "${ApiBase}/releases")" || { err "Release-Erstellung fehlgeschlagen: $relBody"; exit 1; }
    detail "      Release erstellt."
fi

# ----- 8. APK als Release-Asset verlinken ----------------------------------
info "[8/8] Verlinke APK mit Release ..."
linkData="$(printf '{"name":"app-release.apk","url":"%s","direct_asset_path":"/app-release.apk","link_type":"package"}' "$PackageUrl")"
linkBody="$(curl --fail-with-body -sS -X POST \
    --header "PRIVATE-TOKEN: ${Token}" \
    --header 'Content-Type: application/json' \
    --data "$linkData" \
    "${ApiBase}/releases/${Tag}/assets/links")" || true
if printf '%s' "$linkBody" | grep -q 'direct_asset_url'; then
    assetUrl="$(printf '%s' "$linkBody" | sed -n 's/.*"direct_asset_url":"\([^"]*\)".*/\1/p')"
    detail "      Verlinkt: ${assetUrl}"
elif printf '%s' "$linkBody" | grep -qE 'already exists|has already been taken'; then
    detail "      Link existiert bereits - ok."
else
    err "Verlinken fehlgeschlagen: $linkBody"; exit 1
fi

printf '\n'
printf '%sFertig! Release %s ist online:%s\n' "$C_CYAN" "$Tag" "$C_RESET"
printf '%shttps://gitlab.com/metaview/ChordProgressionHelper/-/releases/%s%s\n' "$C_CYAN" "$Tag" "$C_RESET"
