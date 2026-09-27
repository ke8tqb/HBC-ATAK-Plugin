<#
make-submission.ps1 - package the source zip for the TAK.gov third-party
pipeline (tak.gov -> Resources -> Third Party Pipeline).

Produces a zip that meets the pipeline's source-archive requirements:
  - single root folder (HBC-ATAK-Plugin/) - the pipeline names its APKs
    after this folder
  - Gradle scripts, wrapper, and all tracked sources at that root
  - no local.properties, no build outputs, no prebuilt APKs
  - the target ATAK line pinned via ATAK_VERSION in gradle.properties
    (stamps the plugin-api and selects the takdev maven artifact version)

Examples:
  .\make-submission.ps1                     # targets ATAK 5.7.0
  .\make-submission.ps1 -AtakVersion 5.8.0  # targets ATAK 5.8.0
#>
param(
    [string] $AtakVersion = '5.7.0',
    [string] $OutDir = '\\Primary\David\TAK Files\01. ATAK APK Files\03. HBC ATAK Plugin\02. Zip To Be Submitted'
)

$ErrorActionPreference = 'Stop'
$repo = $PSScriptRoot
$root = 'HBC-ATAK-Plugin'

$hash = (git -C $repo rev-parse --short=8 HEAD).Trim()
$dirty = git -C $repo status --porcelain
if ($dirty) {
    Write-Warning 'Working tree has uncommitted changes - the zip contains committed (HEAD) content only.'
}
$pluginVer = (Select-String -Path (Join-Path $repo 'app\build.gradle') `
              -Pattern 'PLUGIN_VERSION\s*=\s*"([^"]+)"').Matches[0].Groups[1].Value

$stage = Join-Path $env:TEMP ('hbc-submission-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $stage | Out-Null
try {
    # Tracked files only: local.properties, build/, SDK folders etc. are
    # gitignored and therefore never in the archive. Prebuilt APKs are
    # tracked but not source - exclude them.
    $tmpZip = Join-Path $stage 'src.zip'
    git -C $repo archive --format=zip -o $tmpZip --prefix="$root/" HEAD ':(exclude)prebuilt'
    if ($LASTEXITCODE -ne 0) { throw 'git archive failed' }
    Expand-Archive $tmpZip $stage
    Remove-Item $tmpZip

    # Pin the ATAK line for this submission.
    Add-Content (Join-Path $stage "$root\gradle.properties") @"

# TAK third-party pipeline target: stamps the plugin-api string and selects
# the takdev maven artifact version. (Command-line -PATAK_VERSION overrides.)
ATAK_VERSION=$AtakVersion
"@

    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
    $zip = Join-Path $OutDir "$root-src-$pluginVer-$hash-atak$AtakVersion.zip"
    if (Test-Path $zip) { Remove-Item $zip }
    Compress-Archive -Path (Join-Path $stage $root) -DestinationPath $zip

    Write-Host "Submission zip : $zip"
    Write-Host "Root folder    : $root  (pipeline names its APKs after this)"
    Write-Host "ATAK line      : $AtakVersion"
    Write-Host "Plugin version : $pluginVer ($hash)"
} finally {
    Remove-Item $stage -Recurse -Force -ErrorAction SilentlyContinue
}
