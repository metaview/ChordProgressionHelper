# decrypt_cpwd_try_localmachine.ps1
# Versucht, c.pwd per DPAPI LocalMachine, dann CurrentUser zu entschlüsseln.
# Wenn erfolgreich: schreibt c.pwd.unwrapped.bin / c.pwd.unwrapped.txt und extrahiert ggf. c.pwd.keyfile.bin.
Set-StrictMode -Version Latest

$path = 'C:\Users\metav\AppData\Roaming\Google\AndroidStudio2025.2.2\c.pwd'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$outTxt = Join-Path $projectRoot 'c.pwd.unwrapped.txt'
$outBin = Join-Path $projectRoot 'c.pwd.unwrapped.bin'
$outKey = Join-Path $projectRoot 'c.pwd.keyfile.bin'

if (-not (Test-Path $path)) {
    Write-Error "c.pwd nicht gefunden: $path"
    exit 2
}
Write-Host "Benutze c.pwd: $path"
$b = [System.IO.File]::ReadAllBytes($path)
Write-Host "Gelesen: $($b.Length) bytes"

function TryProtectedDataUnprotect($bytes, $scopeName) {
    try {
        $scope = [System.Security.Cryptography.DataProtectionScope]::$scopeName
        $plain = [System.Security.Cryptography.ProtectedData]::Unprotect($bytes, $null, $scope)
        return @{ Ok = $true; Plain = $plain; Scope = $scopeName }
    } catch {
        return @{ Ok = $false; Error = $_.Exception.Message; Scope = $scopeName }
    }
}

# Try LocalMachine first
$result = $null
try {
    $result = TryProtectedDataUnprotect $b 'LocalMachine'
    if ($result.Ok) { Write-Host "LocalMachine Unprotect: OK (bytes: $($result.Plain.Length))" }
    else { Write-Host "LocalMachine Unprotect fehlgeschlagen: $($result.Error)" }
} catch {
    Write-Host "ProtectedData::Unprotect (LocalMachine) war nicht möglich: $($_.Exception.Message)"
}

if (-not $result.Ok) {
    # Try CurrentUser
    try {
        $result = TryProtectedDataUnprotect $b 'CurrentUser'
        if ($result.Ok) { Write-Host "CurrentUser Unprotect: OK (bytes: $($result.Plain.Length))" }
        else { Write-Host "CurrentUser Unprotect fehlgeschlagen: $($result.Error)" }
    } catch {
        Write-Host "ProtectedData::Unprotect (CurrentUser) war nicht möglich: $($_.Exception.Message)"
    }
}

# If ProtectedData not available or both failed, optionally try P/Invoke CryptUnprotectData (no scope)
if (-not $result.Ok) {
    Write-Host "Versuche Fallback mit CryptUnprotectData (P/Invoke) ohne Scope..."
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class CryptHelper {
    [StructLayout(LayoutKind.Sequential)]
    public struct DATA_BLOB { public int cbData; public IntPtr pbData; }
    [DllImport("crypt32.dll", SetLastError=true)]
    public static extern bool CryptUnprotectData(ref DATA_BLOB pDataIn, IntPtr ppszDataDescr, IntPtr pOptionalEntropy, IntPtr pvReserved, IntPtr pPromptStruct, int dwFlags, ref DATA_BLOB pDataOut);
    [DllImport("kernel32.dll")]
    public static extern IntPtr LocalFree(IntPtr hMem);
}
'@ -ErrorAction SilentlyContinue
    try {
        $in = New-Object CryptHelper+DATA_BLOB
        $in.cbData = $b.Length
        $in.pbData = [Runtime.InteropServices.Marshal]::AllocHGlobal($b.Length)
        [Runtime.InteropServices.Marshal]::Copy($b,0,$in.pbData,$b.Length)
        $out = New-Object CryptHelper+DATA_BLOB
        $ok = [CryptHelper]::CryptUnprotectData([ref]$in,[IntPtr]::Zero,[IntPtr]::Zero,[IntPtr]::Zero,[IntPtr]::Zero,0,[ref]$out)
        [Runtime.InteropServices.Marshal]::FreeHGlobal($in.pbData)
        if ($ok) {
            $plain = New-Object byte[] $out.cbData
            [Runtime.InteropServices.Marshal]::Copy($out.pbData,$plain,0,$out.cbData)
            [CryptHelper]::LocalFree($out.pbData) | Out-Null
            $result = @{ Ok = $true; Plain = $plain; Scope = 'PInvoke' }
            Write-Host "CryptUnprotectData (P/Invoke) erfolgreich, bytes: $($plain.Length)"
        } else {
            $err = [Runtime.InteropServices.Marshal]::GetLastWin32Error()
            Write-Host \"CryptUnprotectData (P/Invoke) fehlgeschlagen, Fehlercode: $err\"
        }
    } catch {
        Write-Host \"P/Invoke Entschlüsselung Fehlgeschlagen: $($_.Exception.Message)\"
    }
}

if ($result -and $result.Ok) {
    $plain = $result.Plain
    # try save as UTF8 text
    $isText = $false
    try {
        $text = [System.Text.Encoding]::UTF8.GetString($plain)
        if ($text -and $text.Length -gt 0) {
            Set-Content -Path $outTxt -Value $text -Encoding UTF8 -Force
            Write-Host \"Wrote text: $outTxt\"
            $isText = $true
        }
    } catch { $isText = $false }

    if (-not $isText) {
        [System.IO.File]::WriteAllBytes($outBin, $plain)
        Write-Host \"Wrote binary: $outBin\"
    }

    # try extract base64 block after 'value: !!binary'
    $search = $null
    if (Test-Path $outTxt) { $search = Get-Content $outTxt -Raw } else { $search = [System.Text.Encoding]::UTF8.GetString($plain) }
    $m = [Text.RegularExpressions.Regex]::Match($search, 'value:\s*!!binary\s*([A-Za-z0-9+/=\s-]+)', [Text.RegularExpressions.RegexOptions]::IgnoreCase)
    if ($m.Success) {
        $b64 = ($m.Groups[1].Value -replace '\s+','')
        try {
            [System.IO.File]::WriteAllBytes($outKey, [Convert]::FromBase64String($b64))
            Write-Host \"Extracted keyfile to: $outKey\"
        } catch {
            Write-Warning \"Failed to decode base64 inline: $($_.Exception.Message)\"
        }
    } else {
        Write-Host \"No inline 'value: !!binary' match; try opening the produced file(s) in KeePassXC.\"
    }
} else {
    Write-Host "Alle Entschlüsselungsversuche sind fehlgeschlagen. Mögliche Ursachen:"
    Write-Host "- Die Datei wurde für einen anderen Windows-Benutzer verschlüsselt."
    Write-Host "- Die Datei ist beschädigt oder hat zusätzliche Entropie."
    Write-Host "- JetBrains hat ein anderes Format benutzt."
    Write-Host ""
    Write-Host "Wenn du möchtest, poste die Ausgaben (Fehlertexte) hier, ansonsten prüfe ob die c.pwd auf einem anderen Benutzer/Computer erzeugt wurde."
}
