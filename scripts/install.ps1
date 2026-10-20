<#
.SYNOPSIS
  Instala SlimTV, lo pone como pantalla de inicio y desactiva el launcher de fábrica.
.DESCRIPTION
  Antes de desactivar el launcher original comprueba que SlimTV abre bien.
  -KeepStockLauncher deja el launcher de fábrica activo (útil para probar).
.EXAMPLE
  pwsh scripts/install.ps1
  pwsh scripts/install.ps1 -Apk ./slimtv.apk      # usando el APK de GitHub Releases
#>
param([string]$Ip, [string]$Apk, [switch]$KeepStockLauncher)
. "$PSScriptRoot/common.ps1"

$pkg = "io.github.mlinescode.slimtv"
$activity = "$pkg/.MainActivity"
# Launchers de fábrica conocidos (Android TV y Google TV) y el asistente que roba el botón Home.
$stock = @(
    "com.google.android.tvlauncher",
    "com.google.android.apps.tv.launcherx",
    "com.google.android.leanbacklauncher",
    "com.google.android.leanbacklauncher.partnercustomizer",
    "com.google.android.tungsten.setupwraith"
)

$dev = Get-Device $Ip
Assert-Connected $dev
$dir = Get-DeviceStateDir $dev
if (-not $Apk) { $Apk = Join-Path $Root "dist/slimtv.apk" }
if (-not (Test-Path $Apk)) { & "$PSScriptRoot/build.ps1"; }

Write-Host "Instalando $Apk ..."
Invoke-Tv $dev install -r $Apk | Out-Host

Invoke-Tv $dev shell am start -n $activity | Out-Null
$top = ""
for ($i = 0; $i -lt 10 -and "$top" -notmatch [regex]::Escape($pkg); $i++) {
    Start-Sleep -Seconds 2
    $top = (Invoke-Tv $dev shell dumpsys activity activities | Select-String "ResumedActivity" | Select-Object -First 1)
}
if ("$top" -notmatch [regex]::Escape($pkg)) {
    throw "SlimTV no abrió correctamente ($top). No toqué el launcher original."
}
Write-Host "SlimTV abre correctamente." -ForegroundColor Green

if (-not $KeepStockLauncher) {
    $installed = Invoke-Tv $dev shell pm list packages | ForEach-Object { $_ -replace "^package:", "" }
    $done = @()
    foreach ($p in $stock) {
        if ($installed -contains $p) {
            Invoke-Tv $dev shell pm disable-user --user 0 $p | Out-Null
            Write-Host "  [desactivado] $p"
            $done += $p
        }
    }
    Add-Unique (Join-Path $dir "disabled.txt") $done
}

Invoke-Tv $dev shell cmd package set-home-activity $activity | Out-Null
Invoke-Tv $dev shell am force-stop com.android.tv.settings | Out-Null
Invoke-Tv $dev shell input keyevent HOME | Out-Null
$h = Invoke-Tv $dev shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | Select-Object -Last 1
Write-Host "Pantalla de inicio: $h" -ForegroundColor Green
