<#
.SYNOPSIS
  Controla un movil Android via ADB a traves de la app QA Remote (AccessibilityService).

.EXAMPLE
  .\qa-remote.ps1 tap 540 1200
  .\qa-remote.ps1 longtap 540 1200 -Ms 1000
  .\qa-remote.ps1 swipe 540 1800 540 600 -Ms 400
  .\qa-remote.ps1 text "Hola, ¿qué tal?"
  .\qa-remote.ps1 tapText "Iniciar sesión"
  .\qa-remote.ps1 tapId "login_button"
  .\qa-remote.ps1 wait "Bienvenido" -Ms 10000
  .\qa-remote.ps1 dump -Out dump.json
  .\qa-remote.ps1 screenshot -Out pantalla.png
  .\qa-remote.ps1 back | home | recents
  .\qa-remote.ps1 tap 100 200 -Serial abc123
  .\qa-remote.ps1 awake on    # pantalla siempre encendida durante la prueba
  .\qa-remote.ps1 awake off   # restaura el tiempo de bloqueo original
  .\qa-remote.ps1 awake status
#>
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateSet('tap', 'longtap', 'swipe', 'text', 'back', 'home', 'recents', 'tapText', 'tapId', 'dump', 'screenshot', 'wait', 'awake')]
    [string]$Command,
    [Parameter(Position = 1, ValueFromRemainingArguments = $true)]
    [string[]]$Rest = @(),
    [string]$Serial,
    [int]$Ms = 0,
    [string]$Out,
    [string]$Adb = 'adb'
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$Component = 'dev.qaremote/.CommandReceiver'
$Action = 'dev.qaremote.CMD'
$DumpPath = '/sdcard/Android/data/dev.qaremote/files/dump.json'

if ($Adb -eq 'adb' -and -not (Get-Command adb -ErrorAction SilentlyContinue)) {
    $cand = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
    if (Test-Path $cand) { $Adb = $cand } else { throw 'No encuentro adb (PATH o SDK).' }
}

function Invoke-Adb([string[]]$a) {
    $full = @()
    if ($Serial) { $full += @('-s', $Serial) }
    $full += $a
    $ErrorActionPreference = 'Continue'
    & $Adb @full 2>&1 | ForEach-Object { "$_" }
}

function Need([int]$n) {
    if ($Rest.Count -lt $n) { throw "El comando '$Command' necesita $n argumento(s)." }
}

function To-B64([string]$s) { [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($s)) }

function Send-Cmd([string]$cmd, [string[]]$extras = @()) {
    # am broadcast corre en el shell del movil: los extras numericos/base64 no necesitan comillas.
    $line = "am broadcast -n $Component -a $Action --es cmd $cmd " + ($extras -join ' ')
    $outp = (Invoke-Adb @('shell', $line)) -join "`n"
    if ($outp -match 'result=(-?\d+)(?:, data="(.*)")?') {
        $code = [int]$Matches[1]
        $data = $Matches[2]
        if ($code -eq 0) { Write-Host $data; return $true }
        if ($code -eq -1 -or -not $data) { Write-Host "ERROR: sin respuesta de la app (¿instalada?) · $outp" }
        else { Write-Host $data }
        return $false
    }
    Write-Host "ERROR: salida inesperada de am broadcast: $outp"
    return $false
}

$ok = $true
switch ($Command) {
    'tap' { Need 2; $ok = Send-Cmd 'tap' @("--es x $($Rest[0])", "--es y $($Rest[1])") }
    'longtap' {
        Need 2; $d = if ($Ms) { $Ms } else { 800 }
        $ok = Send-Cmd 'longtap' @("--es x $($Rest[0])", "--es y $($Rest[1])", "--es ms $d")
    }
    'swipe' {
        Need 4; $d = if ($Ms) { $Ms } else { 300 }
        $ok = Send-Cmd 'swipe' @("--es x $($Rest[0])", "--es y $($Rest[1])", "--es x2 $($Rest[2])", "--es y2 $($Rest[3])", "--es ms $d")
    }
    { $_ -in 'back', 'home', 'recents' } { $ok = Send-Cmd $Command }
    { $_ -in 'text', 'tapText', 'tapId' } {
        Need 1; $ok = Send-Cmd $Command @("--es b64 $(To-B64 ($Rest -join ' '))")
    }
    'wait' {
        Need 1; $d = if ($Ms) { $Ms } else { 5000 }
        $ok = Send-Cmd 'wait' @("--es b64 $(To-B64 ($Rest -join ' '))", "--es ms $d")
    }
    'dump' {
        $ok = Send-Cmd 'dump'
        if ($ok) {
            $tmp = if ($Out) { $Out } else { Join-Path $env:TEMP 'qaremote-dump.json' }
            Invoke-Adb @('pull', $DumpPath, $tmp) | Out-Null
            $json = Get-Content -Raw -Encoding UTF8 $tmp
            if (-not $Out) { Remove-Item $tmp -ErrorAction SilentlyContinue }
            Write-Output $json
        }
    }
    'screenshot' {
        $file = if ($Out) { $Out } elseif ($Rest.Count) { $Rest[0] } else { "screenshot-$(Get-Date -Format yyyyMMdd-HHmmss).png" }
        $file = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) $file))
        # cmd.exe: la redireccion de PowerShell 5.1 corrompe binarios.
        $s = if ($Serial) { "-s $Serial " } else { '' }
        cmd /c "`"$Adb`" $s exec-out screencap -p > `"$file`""
        if ((Test-Path $file) -and (Get-Item $file).Length -gt 0) { Write-Output "OK: captura en $file" }
        else { Write-Output 'ERROR: captura vacia (¿FLAG_SECURE?)'; $ok = $false }
    }
    'awake' {
        # Usa adb directamente (no la app): 'settings put system' y 'svc power'
        # funcionan desde el shell aunque el fabricante bloquee la inyeccion.
        # El valor original se guarda por dispositivo para restaurarlo luego.
        Need 1
        $id = if ($Serial) { $Serial } else { ((Invoke-Adb @('get-serialno')) -join '').Trim() }
        $state = Join-Path $env:LOCALAPPDATA "qa-remote\awake-$id.txt"
        switch ($Rest[0]) {
            'on' {
                if (-not (Test-Path $state)) {
                    $orig = ((Invoke-Adb @('shell', 'settings get system screen_off_timeout')) -join '').Trim()
                    New-Item -ItemType Directory -Force (Split-Path $state) | Out-Null
                    Set-Content -Path $state -Value $orig -Encoding ASCII
                }
                Invoke-Adb @('shell', 'settings put system screen_off_timeout 1800000') | Out-Null
                Invoke-Adb @('shell', 'svc power stayon usb') | Out-Null
                Write-Output "OK: awake on · pantalla encendida mientras haya USB (original guardado: $(Get-Content $state))"
            }
            'off' {
                $orig = if (Test-Path $state) { (Get-Content $state).Trim() } else { '30000' }
                Invoke-Adb @('shell', "settings put system screen_off_timeout $orig") | Out-Null
                Invoke-Adb @('shell', 'svc power stayon false') | Out-Null
                Remove-Item $state -ErrorAction SilentlyContinue
                Write-Output "OK: awake off · bloqueo restaurado a $orig ms"
            }
            'status' {
                $cur = ((Invoke-Adb @('shell', 'settings get system screen_off_timeout')) -join '').Trim()
                $stay = ((Invoke-Adb @('shell', 'dumpsys power')) | Select-String 'mStayOn=') -join ''
                $saved = if (Test-Path $state) { (Get-Content $state).Trim() } else { '(ninguno)' }
                Write-Output "OK: screen_off_timeout=$cur · $($stay.Trim()) · original guardado=$saved"
            }
            default { throw "Uso: awake on|off|status" }
        }
    }
}
if (-not $ok) { exit 1 }
