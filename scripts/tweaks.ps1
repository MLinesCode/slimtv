<#
.SYNOPSIS
  Ajustes de fluidez: animaciones al doble de velocidad. -Reset las devuelve a la normalidad.
.EXAMPLE
  pwsh scripts/tweaks.ps1
  pwsh scripts/tweaks.ps1 -Scale 0     # sin animaciones
  pwsh scripts/tweaks.ps1 -Reset
#>
param([string]$Ip, [double]$Scale = 0.5, [switch]$Reset)
. "$PSScriptRoot/common.ps1"

$dev = Get-Device $Ip
Assert-Connected $dev
if ($Reset) { $Scale = 1.0 }
$s = $Scale.ToString([Globalization.CultureInfo]::InvariantCulture)
Invoke-Tv $dev shell "settings put global window_animation_scale $s; settings put global transition_animation_scale $s; settings put global animator_duration_scale $s" | Out-Null
Write-Host "Escala de animaciones: $s" -ForegroundColor Green
