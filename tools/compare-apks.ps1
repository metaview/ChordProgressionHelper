param(
    [Parameter(Mandatory=$true)] [string]$apk1,
    [Parameter(Mandatory=$true)] [string]$apk2,
    [switch]$IgnoreMetaInf
)

function Get-ZipEntryHashes([string]$zipPath, [switch]$ignoreMeta) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($zipPath)
    try {
        $entries = @()
        foreach ($e in $zip.Entries) {
            if ($ignoreMeta -and $e.FullName -match '^META-INF/') { continue }
            $stream = $e.Open()
            try {
                $sha = [System.Security.Cryptography.SHA256]::Create()
                $hashBytes = $sha.ComputeHash($stream)
                $hashHex = ($hashBytes | ForEach-Object { $_.ToString('x2') }) -join ''
                $entries += [PSCustomObject]@{
                    Name = $e.FullName
                    Size = $e.Length
                    Hash = $hashHex
                }
            } finally { $stream.Dispose() }
        }
        return $entries | Sort-Object -Property Name
    } finally { $zip.Dispose() }
}

if (-not (Test-Path $apk1)) { Write-Error "APK1 not found: $apk1"; exit 2 }
if (-not (Test-Path $apk2)) { Write-Error "APK2 not found: $apk2"; exit 2 }

Write-Host "Computing entry hashes for:`n $apk1`n $apk2`n"
$h1 = Get-ZipEntryHashes $apk1 -ignoreMeta:$IgnoreMetaInf
$h2 = Get-ZipEntryHashes $apk2 -ignoreMeta:$IgnoreMetaInf

# Build lookup
$d1 = @{ }
foreach ($e in $h1) { $d1[$e.Name] = $e }
$d2 = @{ }
foreach ($e in $h2) { $d2[$e.Name] = $e }

$allKeys = ($d1.Keys + $d2.Keys) | Sort-Object -Unique

$diffs = @()
foreach ($k in $allKeys) {
    $a = $d1[$k]
    $b = $d2[$k]
    if (-not $a) { $diffs += [PSCustomObject]@{ Entry = $k; Status = 'Only in APK2' }; continue }
    if (-not $b) { $diffs += [PSCustomObject]@{ Entry = $k; Status = 'Only in APK1' }; continue }
    if ($a.Hash -ne $b.Hash) {
        $diffs += [PSCustomObject]@{ Entry = $k; Status = 'Different'; Size1 = $a.Size; Size2 = $b.Size; Hash1 = $a.Hash; Hash2 = $b.Hash }
    }
}

if ($diffs.Count -eq 0) { Write-Host 'APKs are identical at the entry/hash level.'; exit 0 }

Write-Host "Found differences:`n"
$diffs | Format-Table -AutoSize

# Exit with non-zero so CI can detect differences
exit 1

