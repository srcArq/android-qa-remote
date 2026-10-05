<#
.SYNOPSIS
  Graba la pantalla del movil. Usa scrcpy si esta (PATH o tools\), si no adb screenrecord en tramos.

.EXAMPLE
  .\record.ps1                       # graba hasta Ctrl+C
  .\record.ps1 -Seconds 120 -Out recordings\prueba.mp4
  .\record.ps1 -Serial abc123 -ForceAdb
#>
param(
    [int]$Seconds = 0,          # 0 = hasta Ctrl+C
    [string]$Out,
    [string]$Serial,
    [switch]$ForceAdb,
    [string]$Adb = 'adb'
)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
if ($Adb -eq 'adb' -and -not (Get-Command adb -ErrorAction SilentlyContinue)) {
    $cand = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
    if (Test-Path $cand) { $Adb = $cand } else { throw 'No encuentro adb.' }
}
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
if (-not $Out) { $Out = Join-Path $root "recordings\rec-$stamp.mp4" }
$Out = [System.IO.Path]::GetFullPath($Out)
New-Item -ItemType Directory -Force (Split-Path $Out) | Out-Null
$sArgs = @(); if ($Serial) { $sArgs = @('-s', $Serial) }

$scrcpy = $null
if (-not $ForceAdb) {
    $cmd = Get-Command scrcpy -ErrorAction SilentlyContinue
    if ($cmd) { $scrcpy = $cmd.Source }
    else {
        $local = Get-ChildItem (Join-Path $root 'tools') -Recurse -Filter scrcpy.exe -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($local) { $scrcpy = $local.FullName }
    }
}

if ($scrcpy) {
    $a = $sArgs + @('--no-playback', '--no-audio', "--record=$Out")
    if ($Seconds -gt 0) { $a += "--time-limit=$Seconds" }
    Write-Output "Grabando con scrcpy -> $Out (Ctrl+C para parar)"
    & $scrcpy @a
    Write-Output "OK: $Out"
    exit 0
}

# Fallback: adb screenrecord (max 180 s por tramo) encadenado.
Write-Output 'scrcpy no encontrado: uso adb screenrecord por tramos de 180 s (Ctrl+C para parar).'
$base = [System.IO.Path]::ChangeExtension($Out, $null).TrimEnd('.')
$part = 0; $elapsed = 0
try {
    while ($Seconds -eq 0 -or $elapsed -lt $Seconds) {
        $part++
        $len = 180
        if ($Seconds -gt 0) { $len = [Math]::Min(180, $Seconds - $elapsed) }
        $remote = "/sdcard/qaremote-rec-$part.mp4"
        Write-Output "Tramo $part ($len s)..."
        & $Adb @sArgs shell screenrecord --time-limit $len $remote
        & $Adb @sArgs pull $remote ("{0}-part{1:D2}.mp4" -f $base, $part) | Out-Null
        & $Adb @sArgs shell rm -f $remote | Out-Null
        $elapsed += $len
    }
}
finally {
    # Recupera el tramo en curso si se corto con Ctrl+C.
    $remote = "/sdcard/qaremote-rec-$part.mp4"
    $local = "{0}-part{1:D2}.mp4" -f $base, $part
    if (-not (Test-Path $local)) {
        Start-Sleep -Milliseconds 800
        & $Adb @sArgs pull $remote $local 2>$null | Out-Null
        & $Adb @sArgs shell rm -f $remote 2>$null | Out-Null
    }
    Write-Output "Tramos guardados como $base-partNN.mp4 (unir con ffmpeg -f concat si hace falta)."
}
