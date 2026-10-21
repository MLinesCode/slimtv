# Funciones compartidas por todos los scripts de SlimTV. No se ejecuta directo.
$ErrorActionPreference = "Stop"

$script:Root     = Split-Path $PSScriptRoot -Parent
$script:IsWin    = $env:OS -eq "Windows_NT"
$script:IpFile   = Join-Path $Root ".tv-ip"
$script:StateDir = Join-Path $Root "state"

# Paquetes que SlimTV nunca desactiva aunque aparezcan en una lista: sin ellos la TV
# puede quedar sin control remoto, sin imagen, sin Play Store o sin poder arrancar.
$script:Protected = @(
    "android", "com.android.systemui", "com.android.settings", "com.android.tv.settings",
    "com.android.providers.settings", "com.android.providers.tv", "com.android.shell",
    "com.android.bluetooth", "com.android.inputdevices", "com.android.packageinstaller",
    "com.google.android.packageinstaller", "com.google.android.permissioncontroller",
    "com.google.android.gms", "com.google.android.gsf", "com.android.vending",
    "com.google.android.webview", "com.google.android.inputmethod.latin",
    "com.google.android.tv.remote.service", "com.google.android.ext.services",
    "com.mediatek.tv.service", "com.mediatek.tvinput", "com.mediatek.wwtv.tvcenter",
    "com.airoha.android.btd"
)

function Find-Sdk {
    $candidates = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)
    if ($IsWin) { $candidates += "$env:LOCALAPPDATA\Android\Sdk" }
    else { $candidates += "$HOME/Library/Android/sdk", "$HOME/Android/Sdk" }
    foreach ($c in $candidates) { if ($c -and (Test-Path $c)) { return $c } }
    return $null
}

function Find-Adb {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $sdk = Find-Sdk
    if ($sdk) {
        $exe = if ($IsWin) { "adb.exe" } else { "adb" }
        $p = Join-Path (Join-Path $sdk "platform-tools") $exe
        if (Test-Path $p) { return $p }
    }
    throw "No encontré adb. Instala 'Android SDK Platform-Tools': https://developer.android.com/tools/releases/platform-tools"
}

$script:Adb = Find-Adb

function Resolve-TvIp([string]$Ip) {
    if ($Ip) { return $Ip }
    if (Test-Path $IpFile) { return (Get-Content $IpFile -Raw).Trim() }
    throw "Falta la IP de la TV. Corre primero:  scripts/connect.ps1 -Ip 192.168.x.x"
}

function Get-Device([string]$Ip) {
    $ip = Resolve-TvIp $Ip
    if ($ip -notmatch ":") { $ip = "${ip}:5555" }
    return $ip
}

# Uso: Invoke-Tv <dispositivo> <argumentos de adb...>
# Sin bloque param a propósito: así "-r", "-n", "-f"... llegan tal cual a adb.
function Invoke-Tv {
    $device = $args[0]
    $rest = @($args | Select-Object -Skip 1)
    $out = & $Adb -s $device @rest 2>&1
    return ($out | Where-Object { $_ -notmatch "Init wrapper|open mma dev" })
}

function Assert-Connected([string]$Device) {
    $null = & $Adb connect $Device 2>&1
    $state = (& $Adb -s $Device get-state 2>&1) -join " "
    if ($state -notmatch "device") {
        throw "La TV $Device no está lista ($state). Corre scripts/connect.ps1 y acepta el aviso en la TV."
    }
}

function Get-DeviceStateDir([string]$Device) {
    $dir = Join-Path $StateDir ($Device -replace "[:.]", "_")
    New-Item -ItemType Directory -Force $dir | Out-Null
    return $dir
}

function Read-PackageList([string]$Path) {
    Get-Content $Path | ForEach-Object { ($_ -split "#")[0].Trim() } | Where-Object { $_ }
}

function Add-Unique([string]$File, [string[]]$Items) {
    $existing = @()
    if (Test-Path $File) { $existing = Get-Content $File }
    $new = $Items | Where-Object { $_ -and ($existing -notcontains $_) }
    if ($new) { Add-Content -Path $File -Value $new }
}

# Apps de música del mini reproductor (launcher/res/values/config.xml). Deben poder sonar en
# segundo plano: con RUN_ANY_IN_BACKGROUND restringido, Spotify se corta al salir de la app.
function Get-MusicApps {
    $config = Join-Path $Root "launcher/res/values/config.xml"
    if (-not (Test-Path $config)) { return @() }
    $xml = [xml](Get-Content $config -Raw)
    return @($xml.resources.'string-array' | Where-Object { $_.name -eq "music_apps" } |
        ForEach-Object { $_.item } | Where-Object { $_ })
}

function Get-MemAvailableMB([string]$Device) {
    $line = Invoke-Tv $Device shell "grep MemAvailable /proc/meminfo"
    if ("$line" -match "(\d+)") { return [math]::Round([int64]$Matches[1] / 1024) }
    return $null
}
