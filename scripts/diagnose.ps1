<#
.SYNOPSIS
  Diagnóstico de la TV: qué consume RAM, qué apps hay y qué launcher usa.
  La primera vez guarda un respaldo del estado original en state/<tv>/original/.
.EXAMPLE
  pwsh scripts/diagnose.ps1
  pwsh scripts/diagnose.ps1 -Top 25
#>
param([string]$Ip, [int]$Top = 15)
. "$PSScriptRoot/common.ps1"

$dev = Get-Device $Ip
Assert-Connected $dev
$dir = Get-DeviceStateDir $dev
$orig = Join-Path $dir "original"

if (-not (Test-Path $orig)) {
    New-Item -ItemType Directory -Force $orig | Out-Null
    Invoke-Tv $dev shell pm list packages -f | Set-Content (Join-Path $orig "packages-all.txt")
    Invoke-Tv $dev shell pm list packages -3 | Set-Content (Join-Path $orig "packages-user.txt")
    Invoke-Tv $dev shell pm list packages -d | Set-Content (Join-Path $orig "packages-disabled.txt")
    Invoke-Tv $dev shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME |
        Set-Content (Join-Path $orig "home.txt")
    Invoke-Tv $dev shell "settings get global window_animation_scale; settings get global transition_animation_scale; settings get global animator_duration_scale" |
        Set-Content (Join-Path $orig "animations.txt")
    Invoke-Tv $dev shell "grep -E 'MemTotal|MemAvailable' /proc/meminfo" | Set-Content (Join-Path $orig "meminfo.txt")
    Write-Host "Respaldo del estado original guardado en state/" -ForegroundColor Green
}

$model = Invoke-Tv $dev shell getprop ro.product.model
$ver   = Invoke-Tv $dev shell getprop ro.build.version.release
Write-Host ""
Write-Host "== $model · Android $ver =="
Write-Host ("RAM disponible ahora: {0} MB" -f (Get-MemAvailableMB $dev))

$launcher = (Invoke-Tv $dev shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | Select-Object -Last 1)
Write-Host "Launcher actual: $launcher"

Write-Host ""
Write-Host "== Top $Top procesos por RAM (PSS) =="
$mem = Invoke-Tv $dev shell dumpsys meminfo
$inSection = $false; $n = 0
foreach ($l in $mem) {
    if ($l -match "Total PSS by process") { $inSection = $true; continue }
    if ($inSection) {
        if ($l -notmatch "K:") { break }
        Write-Host $l
        if (++$n -ge $Top) { break }
    }
}

Write-Host ""
Write-Host "== Apps instaladas por el usuario =="
Invoke-Tv $dev shell pm list packages -3 | ForEach-Object { $_ -replace "^package:", "  " } | Sort-Object | Out-Host

Write-Host "Para ver TODOS los paquetes del sistema:  adb shell pm list packages -s"
Write-Host "Revisa examples/ por si ya existe una lista para tu modelo."
