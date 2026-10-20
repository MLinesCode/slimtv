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

if (-not $KeepLauncher) {
    Invoke-Tv $dev shell pm uninstall io.github.mlinescode.slimtv | Out-Null
    Write-Host "  [desinstalado] SlimTV"
}

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
foreach ($f in $disabled, $bg) { if (Test-Path $f) { Rename-Item $f "$(Split-Path $f -Leaf).restored-$stamp" } }
Invoke-Tv $dev shell input keyevent HOME | Out-Null
Write-Host ""
Write-Host "Listo: la TV quedó como venía de fábrica (el respaldo original sigue en state/)." -ForegroundColor Green
