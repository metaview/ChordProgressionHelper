<#
.SYNOPSIS
    Ein-Klick-Release fuer Windows/PowerShell (identischer Ablauf wie tools/release.sh).

.BESCHREIBUNG
    Einzige manuelle Vorarbeit: in gradle.properties APP_VERSION_CODE und
    APP_VERSION_NAME hochsetzen. Danach nur noch:  .\tools\release.ps1

    Was das Skript automatisch macht (damit man nichts vergisst):
      1. Versionscode fest in app/build.gradle schreiben (F-Droid liest ihn so aus dem Tag)
      2. app/build.gradle + gradle.properties committen
      3. Git-Tag v<version> anlegen
      4. Commit + Tag nach GitLab pushen
      5. Release-APK bauen
      6. APK ins GitLab Package Registry hochladen
      7. GitLab-Release erstellen
      8. APK als Release-Asset verlinken

    Version und Versionscode kommen ausschliesslich aus gradle.properties — eine
    einzige Quelle, kein Vertippen, kein separates Taggen von Hand.

.HINWEIS
    Token: GitLab Personal Access Token (api-scope) per Umgebungsvariable GITLAB_TOKEN
    oder interaktiv (verdeckte Eingabe).

.BEISPIEL
    .\tools\release.ps1
    $env:GITLAB_TOKEN = 'glpat-xxxx'; .\tools\release.ps1
#>

$ErrorActionPreference = 'Stop'
# Native Kommandos (git, gradlew) sollen bei Exit-Code <> 0 NICHT automatisch werfen
# (PowerShell 7.4+). Erfolg/Fehler pruefen wir gezielt ueber $LASTEXITCODE, denn einige
# git-Aufrufe liefern absichtlich einen Exit-Code <> 0 (z.B. 'git diff --quiet').
$PSNativeCommandUseErrorActionPreference = $false

# ----- Konfiguration -------------------------------------------------------
$ProjectId   = '77601409'
$PackageName = 'ChordProgressionHelper'
$ApiBase     = "https://gitlab.com/api/v4/projects/$ProjectId"

# Projekt-Root = ein Verzeichnis ueber diesem Skript (tools/)
$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

$PropsFile  = Join-Path $RepoRoot 'gradle.properties'
$GradleFile = Join-Path $RepoRoot 'app\build.gradle'

# ----- Version + Versionscode aus gradle.properties lesen ------------------
function Read-Prop([string]$Name) {
    $line = Select-String -Path $PropsFile -Pattern "^\s*$Name\s*=" | Select-Object -First 1
    if (-not $line) { return $null }
    return ($line.Line -split '=', 2)[1].Trim()
}
$Version        = Read-Prop 'APP_VERSION_NAME'
$AppVersionCode = Read-Prop 'APP_VERSION_CODE'

if ([string]::IsNullOrWhiteSpace($Version)) { throw 'APP_VERSION_NAME fehlt in gradle.properties.' }
if ($AppVersionCode -notmatch '^\d+$')      { throw "APP_VERSION_CODE ist kein gueltiger Integer: '$AppVersionCode'" }

$Tag    = "v$Version"
$Branch = (git rev-parse --abbrev-ref HEAD).Trim()

Write-Host ""
Write-Host "Version      : $Version"        -ForegroundColor Cyan
Write-Host "Versionscode : $AppVersionCode" -ForegroundColor Cyan
Write-Host "Tag          : $Tag"            -ForegroundColor Cyan
Write-Host "Branch       : $Branch"         -ForegroundColor Cyan
Write-Host ""

# ----- Auf andere, noch nicht committete Aenderungen hinweisen -------------
$OtherChanges = git status --porcelain --untracked-files=no |
    Where-Object { $_ -notmatch ' (app/build\.gradle|gradle\.properties)$' }
if ($OtherChanges) {
    Write-Warning 'Achtung: es gibt weitere uncommittete Aenderungen, die NICHT ins Release-Tag kommen:'
    $OtherChanges | ForEach-Object { Write-Host $_ -ForegroundColor Yellow }
    Write-Host ""
}

# ----- Einmalige Bestaetigung ----------------------------------------------
$ok = Read-Host "Release $Tag jetzt committen, taggen, pushen und veroeffentlichen? (j/N)"
if ($ok -notmatch '^[jJyY]$') { throw 'Abgebrochen.' }

# ----- Token abfragen (verdeckt) -------------------------------------------
$Token = $env:GITLAB_TOKEN
if ([string]::IsNullOrWhiteSpace($Token)) {
    $secureToken = Read-Host 'GitLab Personal Access Token (api-scope)' -AsSecureString
    $Token = [System.Runtime.InteropServices.Marshal]::PtrToStringAuto(
        [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureToken))
}
if ([string]::IsNullOrWhiteSpace($Token)) { throw 'Kein Token angegeben.' }
$Headers = @{ 'PRIVATE-TOKEN' = $Token }

# ----- 1. Versionscode fest in build.gradle schreiben ----------------------
Write-Host "[1/8] Schreibe versionCode $AppVersionCode fest in app/build.gradle ..." -ForegroundColor Green
# Ersetzt die 'versionCode ...'-Zeile (egal ob Integer.parseInt(...) oder Literal); Einrueckung bleibt.
$content = Get-Content -Path $GradleFile -Raw
$content = [regex]::Replace($content, '(?m)^(\s*)versionCode[ \t].*$', "`${1}versionCode $AppVersionCode")
# Ohne zusaetzlichen Zeilenumbruch schreiben (Set-Content wuerde einen anhaengen)
[System.IO.File]::WriteAllText($GradleFile, $content)
if (-not (Select-String -Path $GradleFile -Pattern "^\s*versionCode\s+$AppVersionCode\s*$" -Quiet)) {
    throw "Konnte versionCode nicht in $GradleFile setzen."
}

# ----- 2. Versionsdateien committen ----------------------------------------
Write-Host "[2/8] Committe Versionsdateien ..." -ForegroundColor Green
git add $GradleFile $PropsFile
git diff --cached --quiet
if ($LASTEXITCODE -eq 0) {
    Write-Host "      Nichts zu committen (Version bereits eingetragen)." -ForegroundColor DarkGray
} else {
    git commit -m "Release $Tag" | Out-Null
    Write-Host "      Commit 'Release $Tag' erstellt." -ForegroundColor DarkGray
}

# ----- 3. Tag anlegen ------------------------------------------------------
Write-Host "[3/8] Lege Git-Tag '$Tag' an ..." -ForegroundColor Green
git rev-parse -q --verify "refs/tags/$Tag" *> $null
if ($LASTEXITCODE -eq 0) {
    Write-Host "      Tag existiert bereits lokal - ok." -ForegroundColor DarkGray
} else {
    git tag -a $Tag -m "Release $Tag"
    Write-Host "      Tag '$Tag' erstellt." -ForegroundColor DarkGray
}

# ----- 4. Push (Commit + Tag) ----------------------------------------------
Write-Host "[4/8] Pushe Branch '$Branch' und Tag '$Tag' nach GitLab ..." -ForegroundColor Green
git push origin $Branch
if ($LASTEXITCODE -ne 0) { throw "Push von Branch '$Branch' fehlgeschlagen." }
git push origin $Tag
if ($LASTEXITCODE -ne 0) {
    Write-Warning "Tag-Push meldete einen Fehler (existiert er evtl. schon auf GitLab?) - fahre fort."
}

# ----- 5. APK bauen --------------------------------------------------------
Write-Host "[5/8] Baue Release-APK ..." -ForegroundColor Green
& "$RepoRoot\gradlew.bat" assembleRelease
if ($LASTEXITCODE -ne 0) { throw "Gradle-Build fehlgeschlagen (Exit $LASTEXITCODE)." }

$ApkPath = Join-Path $RepoRoot 'app\build\outputs\apk\release\app-release.apk'
if (-not (Test-Path $ApkPath)) { throw "APK nicht gefunden unter: $ApkPath" }
$apkSize = [math]::Round((Get-Item $ApkPath).Length / 1MB, 2)
Write-Host "      APK ok: $ApkPath ($apkSize MB)" -ForegroundColor DarkGray

# ----- 6. APK ins Package Registry hochladen (PUT) -------------------------
$PackageUrl = "$ApiBase/packages/generic/$PackageName/$Version/app-release.apk"
Write-Host "[6/8] Lade APK hoch -> $PackageUrl" -ForegroundColor Green
try {
    $up = Invoke-RestMethod -Method Put -Uri $PackageUrl -Headers $Headers `
        -InFile $ApkPath -ContentType 'application/octet-stream'
    Write-Host "      Upload ok: $($up.message)" -ForegroundColor DarkGray
} catch {
    Write-Host ($_.ErrorDetails.Message) -ForegroundColor Red
    throw "Upload fehlgeschlagen: $($_.Exception.Message)"
}

# ----- 7. Release erstellen (falls noch nicht vorhanden) -------------------
Write-Host "[7/8] Erstelle Release '$Tag' ..." -ForegroundColor Green
$releaseExists = $false
try {
    Invoke-RestMethod -Method Get -Uri "$ApiBase/releases/$Tag" -Headers $Headers | Out-Null
    $releaseExists = $true
    Write-Host "      Release existiert bereits - ueberspringe Erstellung." -ForegroundColor DarkGray
} catch {
    # 404 = existiert noch nicht -> anlegen
}

if (-not $releaseExists) {
    $body = @{
        tag_name    = $Tag
        name        = "Release $Tag"
        description = "Release $Tag"
    } | ConvertTo-Json
    try {
        Invoke-RestMethod -Method Post -Uri "$ApiBase/releases" -Headers $Headers `
            -Body $body -ContentType 'application/json' | Out-Null
        Write-Host "      Release erstellt." -ForegroundColor DarkGray
    } catch {
        Write-Host ($_.ErrorDetails.Message) -ForegroundColor Red
        throw "Release-Erstellung fehlgeschlagen: $($_.Exception.Message)"
    }
}

# ----- 8. APK als Release-Asset verlinken ----------------------------------
Write-Host "[8/8] Verlinke APK mit Release ..." -ForegroundColor Green
$linkBody = @{
    name              = "app-release.apk"
    url               = $PackageUrl
    direct_asset_path = "/app-release.apk"
    link_type         = "package"
} | ConvertTo-Json
try {
    $link = Invoke-RestMethod -Method Post -Uri "$ApiBase/releases/$Tag/assets/links" `
        -Headers $Headers -Body $linkBody -ContentType 'application/json'
    Write-Host "      Verlinkt: $($link.direct_asset_url)" -ForegroundColor DarkGray
} catch {
    $msg = $_.ErrorDetails.Message
    if ($msg -match 'already exists') {
        Write-Host "      Link existiert bereits - ok." -ForegroundColor DarkGray
    } else {
        Write-Host $msg -ForegroundColor Red
        throw "Verlinken fehlgeschlagen: $($_.Exception.Message)"
    }
}

Write-Host ""
Write-Host "Fertig! Release $Tag ist online:" -ForegroundColor Cyan
Write-Host "https://gitlab.com/metaview/ChordProgressionHelper/-/releases/$Tag" -ForegroundColor Cyan
