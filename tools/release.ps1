<#
.SYNOPSIS
    Baut die Release-APK, laedt sie ins GitLab Generic Package Registry hoch,
    erstellt das GitLab-Release und verlinkt die APK als Release-Asset.

.HINWEIS
    Version und Git-Tag (v<version>) muessen VORHER von dir gesetzt/gepusht sein.
    Dieses Skript baut nur, laedt hoch und erstellt/aktualisiert das Release.

.BEISPIEL
    .\tools\release.ps1
    .\tools\release.ps1 -Version 0.97
#>

param(
    [string]$Version
)

$ErrorActionPreference = 'Stop'

# ----- Konfiguration -------------------------------------------------------
$ProjectId   = '77601409'
$PackageName = 'ChordProgressionHelper'
$ApiBase     = "https://gitlab.com/api/v4/projects/$ProjectId"

# Projekt-Root = ein Verzeichnis ueber diesem Skript (tools/)
$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

# ----- Version abfragen ----------------------------------------------------
if (-not $Version) {
    $Version = Read-Host 'Versionsnummer (z.B. 0.97)'
}
$Version = $Version.Trim().TrimStart('v')
if ([string]::IsNullOrWhiteSpace($Version)) {
    throw 'Keine Version angegeben.'
}
$Tag = "v$Version"

Write-Host ""
Write-Host "Version : $Version"  -ForegroundColor Cyan
Write-Host "Tag     : $Tag"      -ForegroundColor Cyan
Write-Host ""

# ----- Warnen, falls Tag lokal nicht existiert -----------------------------
$existingTag = git tag --list $Tag
if (-not $existingTag) {
    Write-Warning "Git-Tag '$Tag' existiert lokal nicht. Das Release wird trotzdem versucht,"
    Write-Warning "aber der Tag muss auf GitLab existieren, sonst schlaegt die Release-Erstellung fehl."
    $go = Read-Host "Trotzdem fortfahren? (j/N)"
    if ($go -notmatch '^[jJyY]') { throw 'Abgebrochen.' }
}

# ----- Token verdeckt abfragen ---------------------------------------------
$secureToken = Read-Host 'GitLab Personal Access Token (api-scope)' -AsSecureString
$Token = [System.Runtime.InteropServices.Marshal]::PtrToStringAuto(
    [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureToken))
if ([string]::IsNullOrWhiteSpace($Token)) {
    throw 'Kein Token angegeben.'
}
$Headers = @{ 'PRIVATE-TOKEN' = $Token }

# ----- 1. APK bauen --------------------------------------------------------
Write-Host "[1/4] Baue Release-APK ..." -ForegroundColor Green
& "$RepoRoot\gradlew.bat" assembleRelease
if ($LASTEXITCODE -ne 0) { throw "Gradle-Build fehlgeschlagen (Exit $LASTEXITCODE)." }

$ApkPath = Join-Path $RepoRoot 'app\build\outputs\apk\release\app-release.apk'
if (-not (Test-Path $ApkPath)) {
    throw "APK nicht gefunden unter: $ApkPath"
}
$apkSize = [math]::Round((Get-Item $ApkPath).Length / 1MB, 2)
Write-Host "      APK ok: $ApkPath ($apkSize MB)" -ForegroundColor DarkGray

# ----- 2. APK ins Package Registry hochladen (PUT) -------------------------
$PackageUrl = "$ApiBase/packages/generic/$PackageName/$Version/app-release.apk"
Write-Host "[2/4] Lade APK hoch -> $PackageUrl" -ForegroundColor Green
try {
    $up = Invoke-RestMethod -Method Put -Uri $PackageUrl -Headers $Headers `
        -InFile $ApkPath -ContentType 'application/octet-stream'
    Write-Host "      Upload ok: $($up.message)" -ForegroundColor DarkGray
} catch {
    Write-Host ($_.ErrorDetails.Message) -ForegroundColor Red
    throw "Upload fehlgeschlagen: $($_.Exception.Message)"
}

# ----- 3. Release erstellen (falls noch nicht vorhanden) -------------------
Write-Host "[3/4] Erstelle Release '$Tag' ..." -ForegroundColor Green
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
        description  = "Release $Tag"
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

# ----- 4. APK als Release-Asset verlinken ----------------------------------
Write-Host "[4/4] Verlinke APK mit Release ..." -ForegroundColor Green
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
