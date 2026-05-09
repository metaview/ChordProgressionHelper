# decrypt_cpwd.ps1
# Sucht AndroidStudio c.pwd, entschlüsselt es per DPAPI und erzeugt:
# - c.pwd.unwrapped.txt (wenn decodierbar als UTF8-Text)
# - c.pwd.unwrapped.bin (binäre Ausgabe, falls nicht Text)
# - c.pwd.keyfile.bin (wenn ein YAML 'value: !!binary' Block gefunden und Base64-dekodiert)
#
# Bitte vorher: sichere ein Backup der Originaldateien!

Set-StrictMode -Version Latest

# ---- Konfiguration / Zielpfade ----
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$outTxt = Join-Path $projectRoot 'c.pwd.unwrapped.txt'
$outBin = Join-Path $projectRoot 'c.pwd.unwrapped.bin'
$outKey = Join-Path $projectRoot 'c.pwd.keyfile.bin'

# ---- Suche nach c.pwd in AndroidStudio Roaming-Ordnern ----
$roaming = [Environment]::GetFolderPath('ApplicationData')
$studioPattern = Join-Path $roaming 'Google\AndroidStudio*'
$candidateDirs = Get-ChildItem -Path $studioPattern -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending

# fallback: falls keine Studio-Ordner gefunden wurden, prüfen wir Unix-ähnliche Pfade (sicherheitshalber)
if (-not $candidateDirs) {
    Write-Host "Keine AndroidStudio-Roaming-Ordner gefunden unter $roaming"
} else {
    Write-Host "Gefundene AndroidStudio Roaming-Ordner:"
    $candidateDirs | ForEach-Object { Write-Host "  $_.FullName" }
}

$found = $null
foreach ($d in $candidateDirs) {
    $path = Join-Path $d.FullName 'c.pwd'
    if (Test-Path $path) { $found = $path; break }
}
if (-not $found) {
    # versuche ältere mögliche Pfade
    $alt = Join-Path $roaming 'Google\AndroidStudio2024.2\c.pwd'
    if (Test-Path $alt) { $found = $alt }
}
if (-not $found) {
    Write-Error "Keine c.pwd gefunden. Bitte überprüfe Pfade unter $roaming\Google\AndroidStudio*"
    exit 2
}
Write-Host "Benutze c.pwd: $found"

# ---- DPAPI Helper (CryptUnprotectData via P/Invoke) ----
Add-Type -TypeDefinition @'
using System;
using System.Text;
using System.Runtime.InteropServices;
public static class DPAPIHelper {
    [StructLayout(LayoutKind.Sequential)]
    public struct DATA_BLOB { public int cbData; public IntPtr pbData; }
    [DllImport("crypt32.dll", SetLastError = true)]
    public static extern bool CryptUnprotectData(ref DATA_BLOB pDataIn, StringBuilder ppszDataDescr, IntPtr pOptionalEntropy, IntPtr pvReserved, IntPtr pPromptStruct, int dwFlags, ref DATA_BLOB pDataOut);
    [DllImport("kernel32.dll")]
    public static extern IntPtr LocalFree(IntPtr hMem);
    public static byte[] Unprotect(byte[] encrypted) {
        DATA_BLOB inBlob = new DATA_BLOB();
        inBlob.cbData = encrypted.Length;
        inBlob.pbData = Marshal.AllocHGlobal(encrypted.Length);
        Marshal.Copy(encrypted, 0, inBlob.pbData, encrypted.Length);
        DATA_BLOB outBlob = new DATA_BLOB();
        bool success = CryptUnprotectData(ref inBlob, null, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, 0, ref outBlob);
        Marshal.FreeHGlobal(inBlob.pbData);
        if (!success) { int err = Marshal.GetLastWin32Error(); throw new Exception("CryptUnprotectData failed: " + err); }
        byte[] outBytes = new byte[outBlob.cbData];
        Marshal.Copy(outBlob.pbData, outBytes, 0, outBlob.cbData);
        LocalFree(outBlob.pbData);
        return outBytes;
    }
}
'@ -ErrorAction Stop

# ---- Lesen und Entschlüsseln ----
try {
    $enc = [System.IO.File]::ReadAllBytes($found)
    Write-Host "Gelesen: $($enc.Length) Bytes"
} catch {
    Write-Error "Fehler beim Lesen von $found : $_"
    exit 3
}

try {
    $plain = [DPAPIHelper]::Unprotect($enc)
    Write-Host "DPAPI Unprotect erfolgreich, $($plain.Length) Bytes erhalten."
} catch {
    Write-Error "DPAPI Entschlüsselung fehlgeschlagen: $_"
    exit 4
}

# ---- Versuch: als UTF8-Text schreiben ----
$wroteText = $false
try {
    $text = [System.Text.Encoding]::UTF8.GetString($plain)
    # optional: einfache Sanity-Check: mindestens ein Zeilenumbruch oder lesbare ASCII-Anteile
    if ($text.Length -gt 0) {
        Set-Content -Path $outTxt -Value $text -Encoding UTF8 -Force
        Write-Host "Schrieb Text-Datei: $outTxt"
        $wroteText = $true
    }
} catch {
    $wroteText = $false
}

if (-not $wroteText) {
    # schreibe binäre Datei
    try {
        [System.IO.File]::WriteAllBytes($outBin, $plain)
        Write-Host "Schrieb Binär-Datei: $outBin"
    } catch {
        Write-Error "Fehler beim Schreiben der Binärdatei: $_"
        exit 5
    }
}

# ---- Suche nach Base64-Block nach 'value: !!binary' und extrahiere falls vorhanden ----
$searchContent = $null
if (Test-Path $outTxt) { $searchContent = Get-Content -Path $outTxt -Raw } else { $searchContent = [System.Text.Encoding]::UTF8.GetString($plain) }

# Suche: entweder inline 'value: !!binary <base64>' oder indented block unter 'value: !!binary'
$singleLine = [Text.RegularExpressions.Regex]::Match($searchContent, 'value:\s*!!binary\s*([A-Za-z0-9+/=\s-]+)', [Text.RegularExpressions.RegexOptions]::IgnoreCase)
if ($singleLine.Success) {
    $b64 = ($singleLine.Groups[1].Value -replace '\s+','')
    try {
        [System.IO.File]::WriteAllBytes($outKey, [Convert]::FromBase64String($b64))
        Write-Host "Extrahiert und dekodiert inline Base64 nach 'value: !!binary' → $outKey"
    } catch {
        Write-Warning "Fehler beim Dekodieren des inline Base64: $_"
    }
} else {
    # Suche indented block (mehrere Zeilen, z.B. YAML folded block)
    $lines = $searchContent -split "(`r?`n)"
    $start = -1
    for ($i = 0; $i -lt $lines.Length; $i++) {
        if ($lines[$i] -match '^\s*value:\s*!!binary\s*$') { $start = $i + 1; break }
    }
    if ($start -ge 0) {
        $b64lines = @()
        for ($j = $start; $j -lt $lines.Length; $j++) {
            if ($lines[$j] -match '^\s*\S') { break }   # stop on next non-indented line
            $b64lines += $lines[$j].Trim()
        }
        $b64 = ($b64lines -join '')
        if ($b64) {
            try {
                [System.IO.File]::WriteAllBytes($outKey, [Convert]::FromBase64String($b64))
                Write-Host "Extrahiert und dekodiert indented Base64 nach 'value: !!binary' → $outKey"
            } catch {
                Write-Warning "Fehler beim Dekodieren des indented Base64: $_"
            }
        } else {
            Write-Host "Kein Base64-Block nach 'value: !!binary' gefunden."
        }
    } else {
        Write-Host "Kein 'value: !!binary' Block gefunden."
    }
}

# ---- Ausgabe: Kopf der Textdatei oder Hex-Vorschau der Binärdatei ----
if (Test-Path $outTxt) {
    Write-Host "---- Erste 40 Zeilen von $outTxt ----"
    Get-Content $outTxt -TotalCount 40 | ForEach-Object { Write-Host $_ }
} else {
    Write-Host "---- Hex-Vorschau der ersten 128 Bytes von $outBin ----"
    $b = [System.IO.File]::ReadAllBytes($outBin)
    $hex = ($b[0..([Math]::Min(127,$b.Length-1))] | ForEach-Object { '{0:x2}' -f $_ }) -join ' '
    Write-Host $hex
}

# ---- Auflistung erzeugter Dateien ----
Write-Host "`nErzeugte/gefunde Dateien im Projekt:"
Get-ChildItem -Path $projectRoot -Filter 'c.pwd*' -Force -ErrorAction SilentlyContinue | Select-Object FullName,Length,LastWriteTime | Format-Table -AutoSize

Write-Host "`nFertig. Falls du die Keyfile ($outKey) oder die Textausgabe hast, versuche, damit `c.kdbx` in KeePassXC (Key file) zu öffnen."
