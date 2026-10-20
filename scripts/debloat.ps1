<#
.SYNOPSIS
  Desactiva (NO desinstala) los paquetes de una lista. Todo se puede revertir con restore.ps1.
.DESCRIPTION
  - Formato de la lista: un paquete por línea; lo que va después de # es comentario.
  - Ignora paquetes que no existen en la TV y se niega a tocar los paquetes protegidos.
  - Anota cada paquete desactivado en state/<tv>/disabled.txt para poder restaurarlo.
.EXAMPLE
  pwsh scripts/debloat.ps1 -List examples/hisense-2k-atv4/debloat.txt -DryRun
  pwsh scripts/debloat.ps1 -List examples/hisense-2k-atv4/debloat.txt
#>
param(
    [Parameter(Mandatory = $true)][string]$List,
    [string]$Ip,
    [switch]$DryRun
)
. "$PSScriptRoot/common.ps1"

$dev = Get-Device $Ip
Assert-Connected $dev
$dir = Get-DeviceStateDir $dev
if (-not (Test-Path (Join-Path $dir "original"))) {
    Write-Host "Primero guardo el respaldo del estado original..."
    & "$PSScriptRoot/diagnose.ps1" -Ip $Ip -Top 0 | Out-Null
}

$installed = Invoke-Tv $dev shell pm list packages | ForEach-Object { $_ -replace "^package:", "" }
$alreadyOff = Invoke-Tv $dev shell pm list packages -d | ForEach-Object { $_ -replace "^package:", "" }
$before = Get-MemAvailableMB $dev
$done = @()

foreach ($p in Read-PackageList $List) {
    if ($Protected -contains $p) { Write-Host "  [protegido] $p" -ForegroundColor Yellow; continue }
    if ($installed -notcontains $p) { Write-Host "  [no existe] $p" -ForegroundColor DarkGray; continue }
    if ($alreadyOff -contains $p) { Write-Host "  [ya desactivado] $p" -ForegroundColor DarkGray; continue }
    if ($DryRun) { Write-Host "  [se desactivaría] $p"; continue }
    $r = Invoke-Tv $dev shell pm disable-user --user 0 $p
    if ("$r" -match "disabled") { Write-Host "  [desactivado] $p" -ForegroundColor Green; $done += $p }
    else { Write-Host "  [error] $p -> $r" -ForegroundColor Red }
}

if ($done) {
    Add-Unique (Join-Path $dir "disabled.txt") $done
    Start-Sleep -Seconds 3
    $after = Get-MemAvailableMB $dev
    Write-Host ""
    Write-Host "$($done.Count) paquetes desactivados. RAM disponible: $before MB -> $after MB" -ForegroundColor Green
    Write-Host "(La RAM se nota mejor después de reiniciar la TV.)  Para deshacer: scripts/restore.ps1"
}
