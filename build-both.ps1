<#
build-both.ps1 - build the HBC plugin once per supported ATAK-CIV line.

An ATAK plugin APK can only declare ONE plugin-api string and the loader
requires an exact match (see app/build.gradle), so each supported ATAK
line gets its own build of this same source. This script extracts each
SDK zip once (cached), then runs Gradle per line with -PATAK_VERSION and
-Ptakdev.plugin pointing into that SDK.

Examples:
  .\build-both.ps1                            # civ debug APKs for 5.7.0 + 5.8.0
  .\build-both.ps1 -Task assembleCivRelease   # proguarded release builds
  .\build-both.ps1 -Versions 5.8.0            # just one line
  .\build-both.ps1 -OutDir C:\temp\apks       # copy results elsewhere
#>
param(
    [string[]] $Versions = @('5.7.0', '5.8.0'),
    [string]   $Task     = 'assembleCivDebug',
    [string]   $SdkCache = (Join-Path $env:LOCALAPPDATA 'ATAK-SDKs'),
    [string]   $OutDir   = '\\Primary\David\TAK Files\01. ATAK APK Files\03. HBC ATAK Plugin\00. Nightly Build'
)

# ATAK-CIV SDK zips per supported line. Update paths/versions here as new
# ATAK releases come out.
$SdkZips = @{
    '5.7.0' = '\\Primary\David\TAK Files\01. ATAK APK Files\01. ATAK Releases\ATAK 5.7.0\ATAK-CIV-5.7.0.15-SDK.zip'
    '5.8.0' = '\\Primary\David\TAK Files\01. ATAK APK Files\01. ATAK Releases\ATAK 5.8.0\ATAK-CIV-5.8.0.5-SDK.zip'
}

$ErrorActionPreference = 'Stop'
$repo = $PSScriptRoot

# --- JDK 17+ (AGP requirement); fall back to Android Studio's JBR ----------
function Get-JavaMajor([string] $javaExe) {
    try {
        $line = (& $javaExe -version 2>&1 | Select-Object -First 1).ToString()
        if ($line -match 'version "([0-9]+)') { return [int]$Matches[1] }
    } catch { }
    return 0
}
$javaHome = $env:JAVA_HOME
if (-not $javaHome -or (Get-JavaMajor (Join-Path $javaHome 'bin\java.exe')) -lt 17) {
    $jbr = 'C:\Program Files\Android\Android Studio\jbr'
    if (Test-Path (Join-Path $jbr 'bin\java.exe')) {
        Write-Host "JAVA_HOME is not JDK 17+; using Android Studio JBR: $jbr"
        $javaHome = $jbr
    } else {
        throw 'No JDK 17+ found. Set JAVA_HOME or install Android Studio.'
    }
}
$env:JAVA_HOME = $javaHome

# --- local.properties bootstrap (sdk.dir; takdev.plugin is passed per build)
$localProps = Join-Path $repo 'local.properties'
if (-not (Test-Path $localProps)) {
    $androidSdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME }
                  else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    if (-not (Test-Path $androidSdk)) {
        throw "Android SDK not found at $androidSdk - create local.properties manually."
    }
    "sdk.dir=$($androidSdk -replace '\\', '/')" | Set-Content $localProps -Encoding ASCII
    Write-Host "Created local.properties with sdk.dir=$androidSdk"
}

New-Item -ItemType Directory -Force -Path $OutDir, $SdkCache | Out-Null
$built = @()

foreach ($ver in $Versions) {
    if (-not $SdkZips.ContainsKey($ver)) {
        throw "No SDK zip configured for ATAK $ver - edit `$SdkZips in build-both.ps1."
    }
    $zip = $SdkZips[$ver]
    if (-not (Test-Path $zip)) { throw "SDK zip not found: $zip" }

    $sdkName = [IO.Path]::GetFileNameWithoutExtension($zip)
    $sdkDir  = Join-Path $SdkCache $sdkName
    if (-not (Test-Path (Join-Path $sdkDir 'main.jar'))) {
        Write-Host "Extracting $sdkName -> $SdkCache ..."
        Expand-Archive -Path $zip -DestinationPath $SdkCache -Force
    }
    $takdevJar = Get-ChildItem $sdkDir -Recurse -Filter 'atak-gradle-takdev.jar' |
                 Select-Object -First 1
    if (-not $takdevJar) { throw "atak-gradle-takdev.jar not found under $sdkDir" }

    # takdev stages the SDK's debug keystore automatically only when the
    # plugin lives inside the SDK's samples/ tree; a standalone checkout
    # needs it copied to app/build/android_keystore (the signingConfigs
    # path) before validateSigning runs.
    $sdkKeystore = Join-Path $sdkDir 'android_keystore'
    if (Test-Path $sdkKeystore) {
        $ksDest = Join-Path $repo 'app\build\android_keystore'
        New-Item -ItemType Directory -Force -Path (Split-Path $ksDest) | Out-Null
        Copy-Item $sdkKeystore $ksDest -Force
    }

    Write-Host ''
    Write-Host ('=' * 70)
    Write-Host "Building $Task for ATAK $ver   (SDK: $sdkName)"
    Write-Host ('=' * 70)
    Push-Location $repo
    try {
        # sdk.path switches takdev into its Offline DevKit mode (it then
        # pulls main.jar / keystore / mapping / core proguard rules straight
        # from the unzipped SDK instead of trying the TAK maven repo).
        & .\gradlew.bat --no-daemon "-PATAK_VERSION=$ver" `
            "-Ptakdev.plugin=$($takdevJar.FullName)" `
            "-Psdk.path=$sdkDir" $Task
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle build failed for ATAK $ver (exit $LASTEXITCODE)"
        }
    } finally { Pop-Location }

    $apks = Get-ChildItem (Join-Path $repo 'app\build\outputs\apk') -Recurse `
            -Filter "*-$ver-*.apk"
    if (-not $apks) { throw "Build reported success but no *-$ver-*.apk found." }
    foreach ($apk in $apks) {
        Copy-Item $apk.FullName $OutDir -Force
        $built += $apk.Name
        Write-Host "  -> $(Join-Path $OutDir $apk.Name)"
    }
}

Write-Host ''
Write-Host 'Done. Built:'
$built | ForEach-Object { Write-Host "  $_" }
