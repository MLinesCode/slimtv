<#
.SYNOPSIS
  Impide que las apps se abran solas en segundo plano (al encender la TV, por ejemplo).
  Siguen funcionando normal cuando las abres tú.
.DESCRIPTION
  Usa el permiso RUN_ANY_IN_BACKGROUND de Android (appops). En TVs de 1 GB es el cambio
  que más RAM libera: Netflix, HBO Max y similares se cargan solas al arrancar.
  Sin -List, aplica a TODAS las apps instaladas por el usuario.
.EXAMPLE
  pwsh scripts/restrict-background.ps1
  pwsh scripts/restrict-background.ps1 -List examples/hisense-2k-atv4/background.txt
#>
param([string]$List, [string]$Ip)
. "$PSScriptRoot/common.ps1"

$dev = Get-Device $Ip
Assert-Connected $dev
$dir = Get-DeviceStateDir $dev

$installed = Invoke-Tv $dev shell pm list packages | ForEach-Object { $_ -replace "^package:", "" }
if ($List) { $pkgs = Read-PackageList $List }
else { $pkgs = Invoke-Tv $dev shell pm list packages -3 | ForEach-Object { $_ -replace "^package:", "" } }

$before = Get-MemAvailableMB $dev
$done = @()
foreach ($p in $pkgs) {
    if ($installed -notcontains $p -or $Protected -contains $p) { continue }
    Invoke-Tv $dev shell cmd appops set $p RUN_ANY_IN_BACKGROUND ignore | Out-Null
    Invoke-Tv $dev shell am force-stop $p | Out-Null
    Write-Host "  [sin segundo plano] $p" -ForegroundColor Green
    $done += $p
}
Add-Unique (Join-Path $dir "background.txt") $done
Start-Sleep -Seconds 3
Write-Host ""
Write-Host ("{0} apps restringidas. RAM disponible: {1} MB -> {2} MB" -f $done.Count, $before, (Get-MemAvailableMB $dev)) -ForegroundColor Green
