<#
.SYNOPSIS
  Compila el launcher SlimTV SIN Gradle (aapt2 + javac + d8 + apksigner) -> dist/slimtv.apk
.DESCRIPTION
  Requisitos: Android SDK (build-tools y una plataforma >= 30) y un JDK 11 o superior.
  Firma: usa la llave de depuración (~/.android/debug.keystore). Para firmar un release,
  define SLIMTV_KEYSTORE, SLIMTV_KEYSTORE_PASS y SLIMTV_KEY_ALIAS.
.EXAMPLE
  pwsh scripts/build.ps1
  pwsh scripts/build.ps1 -Install
#>
param([switch]$Install, [string]$Ip)
. "$PSScriptRoot/common.ps1"

$sdk = Find-Sdk
if (-not $sdk) { throw "No encontré el Android SDK. Define ANDROID_HOME o instala Android Studio." }
$bt = Get-ChildItem (Join-Path $sdk "build-tools") -Directory |
    Sort-Object { [version]($_.Name -replace "[^\d.].*$", "") } | Select-Object -Last 1
$platform = Get-ChildItem (Join-Path $sdk "platforms") -Directory |
    Sort-Object { [int]($_.Name -replace "\D", "") } | Select-Object -Last 1
if (-not $bt -or -not $platform) { throw "Faltan build-tools o platforms en $sdk (instálalos desde el SDK Manager)." }
$jar = Join-Path $platform.FullName "android.jar"
$ext = if ($IsWin) { ".exe" } else { "" }
$bat = if ($IsWin) { ".bat" } else { "" }
$aapt2 = Join-Path $bt.FullName "aapt2$ext"
$d8 = Join-Path $bt.FullName "d8$bat"
$zipalign = Join-Path $bt.FullName "zipalign$ext"
$apksigner = Join-Path $bt.FullName "apksigner$bat"
Write-Host "Usando build-tools $($bt.Name) y $($platform.Name)"

$src = Join-Path $Root "launcher"
$out = Join-Path $src "build"
$dist = Join-Path $Root "dist"
Remove-Item -Recurse -Force $out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$out/gen", "$out/classes", "$out/dex", $dist | Out-Null

& $aapt2 compile --dir "$src/res" -o "$out/res.zip"
if ($LASTEXITCODE) { throw "aapt2 compile falló" }
& $aapt2 link -I $jar --manifest "$src/AndroidManifest.xml" --java "$out/gen" `
    --min-sdk-version 26 --target-sdk-version 30 -o "$out/unsigned.apk" "$out/res.zip"
if ($LASTEXITCODE) { throw "aapt2 link falló" }

$sources = Get-ChildItem -Recurse "$src/src", "$out/gen" -Filter *.java | ForEach-Object FullName
& javac -nowarn -source 11 -target 11 -encoding UTF-8 -classpath $jar -d "$out/classes" @sources 2>&1 |
    Where-Object { $_ -notmatch "warning|^Note:|^Picked up" } | Out-Host
if ($LASTEXITCODE) { throw "javac falló" }

$classes = Get-ChildItem -Recurse "$out/classes" -Filter *.class | ForEach-Object FullName
& $d8 --release --min-api 26 --lib $jar --output "$out/dex" @classes
if ($LASTEXITCODE) { throw "d8 falló" }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$out/unsigned.apk", "Update")
[void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$out/dex/classes.dex", "classes.dex")
$zip.Dispose()

& $zipalign -f -p 4 "$out/unsigned.apk" "$out/aligned.apk"
if ($LASTEXITCODE) { throw "zipalign falló" }

$apk = Join-Path $dist "slimtv.apk"
if ($env:SLIMTV_KEYSTORE) {
    & $apksigner sign --ks $env:SLIMTV_KEYSTORE --ks-pass "env:SLIMTV_KEYSTORE_PASS" `
        --ks-key-alias $env:SLIMTV_KEY_ALIAS --out $apk "$out/aligned.apk" 2>&1 |
        Where-Object { $_ -notmatch "^WARNING" } | Out-Host
} else {
    $ks = Join-Path $HOME ".android/debug.keystore"
    if (-not (Test-Path $ks)) {
        New-Item -ItemType Directory -Force (Split-Path $ks) | Out-Null
        & keytool -genkeypair -keystore $ks -storepass android -keypass android -alias androiddebugkey `
            -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US" | Out-Null
    }
    & $apksigner sign --ks $ks --ks-pass pass:android --key-pass pass:android `
        --ks-key-alias androiddebugkey --out $apk "$out/aligned.apk" 2>&1 |
        Where-Object { $_ -notmatch "^WARNING" } | Out-Host
}
if ($LASTEXITCODE) { throw "apksigner falló" }
Write-Host ("APK listo: dist/slimtv.apk ({0} KB)" -f [math]::Round((Get-Item $apk).Length / 1KB)) -ForegroundColor Green

if ($Install) { & "$PSScriptRoot/install.ps1" -Ip $Ip -Apk $apk }
