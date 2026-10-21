<#
.SYNOPSIS
  Deshace TODO lo que hizo SlimTV en una TV: reactiva paquetes, devuelve el segundo plano,
  restablece las animaciones y desinstala el launcher.
.EXAMPLE
  pwsh scripts/restore.ps1
  pwsh scripts/restore.ps1 -KeepLauncher    # solo deshace la limpieza
#>
param([string]$Ip, [switch]$KeepLauncher)
. "$PSScriptRoot/common.ps1"

$dev = Get-Device $Ip
Assert-Connected $dev
$dir = Get-DeviceStateDir $dev

$disabled = Join-Path $dir "disabled.txt"
if (Test-Path $disabled) {
    foreach ($p in Get-Content $disabled | Where-Object { $_ }) {
        Invoke-Tv $dev shell pm enable --user 0 $p | Out-Null
        Write-Host "  [reactivado] $p"
    }
}

$bg = Join-Path $dir "background.txt"
if (Test-Path $bg) {
    foreach ($p in Get-Content $bg | Where-Object { $_ }) {
        Invoke-Tv $dev shell cmd appops set $p RUN_ANY_IN_BACKGROUND allow | Out-Null
        Write-Host "  [segundo plano permitido] $p"
    }
}

Invoke-Tv $dev shell "settings put global window_animation_scale 1.0; settings put global transition_animation_scale 1.0; settings put global animator_duration_scale 1.0" | Out-Null
Write-Host "  [animaciones] 1.0"

$saver = Join-Path $dir "screensaver.txt"
if (-not $KeepLauncher -and (Test-Path $saver)) {
    $prev = (Get-Content $saver -Raw).Trim()
    if ($prev -and $prev -ne "null") {
        Invoke-Tv $dev shell settings put secure screensaver_components $prev | Out-Null
    } else {
        Invoke-Tv $dev shell settings delete secure screensaver_components | Out-Null
    }
    Write-Host "  [protector] $prev"
}

if (-not $KeepLauncher) {
    Invoke-Tv $dev shell pm uninstall io.github.mlinescode.slimtv | Out-Null
    Write-Host "  [desinstalado] SlimTV"
}

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
if ($KeepLauncher) { $saver = $null }
foreach ($f in $disabled, $bg, $saver) { if ($f -and (Test-Path $f)) { Rename-Item $f "$(Split-Path $f -Leaf).restored-$stamp" } }
Invoke-Tv $dev shell input keyevent HOME | Out-Null
Write-Host ""
Write-Host "Listo: la TV quedó como venía de fábrica (el respaldo original sigue en state/)." -ForegroundColor Green
