<#
.SYNOPSIS
  Conecta con la TV por Wi-Fi (ADB) y guarda su IP para los demás scripts.
.EXAMPLE
  pwsh scripts/connect.ps1 -Ip 192.168.1.50
#>
param([string]$Ip)
. "$PSScriptRoot/common.ps1"

$dev = Get-Device $Ip
Write-Host "Conectando a $dev ..."
& $Adb connect $dev | Out-Host
$state = (& $Adb -s $dev get-state 2>&1) -join " "

if ($state -notmatch "device") {
    Write-Host ""
    Write-Host "La TV todavía no autoriza esta computadora." -ForegroundColor Yellow
    Write-Host "  1. Mira la pantalla de la TV: debe aparecer '¿Permitir la depuración USB?'"
    Write-Host "  2. Marca 'Permitir siempre desde esta computadora' y pulsa Aceptar."
    Write-Host "  3. Vuelve a correr este script."
    Write-Host "Si no aparece el aviso: apaga y prende 'Depuración USB' en Opciones de desarrollador."
    exit 1
}

Set-Content -Path $IpFile -Value ($dev -replace ":5555$", "")
$model = Invoke-Tv $dev shell getprop ro.product.model
$brand = Invoke-Tv $dev shell getprop ro.product.manufacturer
$ver   = Invoke-Tv $dev shell getprop ro.build.version.release
$ram   = Invoke-Tv $dev shell "grep MemTotal /proc/meminfo"
$ramMb = if ("$ram" -match "(\d+)") { [math]::Round([int64]$Matches[1] / 1024) } else { "?" }

Write-Host ""
Write-Host "Conectado: $brand $model · Android $ver · RAM total ${ramMb} MB" -ForegroundColor Green
Write-Host "Siguiente paso:  scripts/diagnose.ps1"
