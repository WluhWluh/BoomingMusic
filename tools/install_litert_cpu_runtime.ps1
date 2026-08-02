param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,

    [Parameter(Mandatory = $true)]
    [string]$Package,

    [Parameter(Mandatory = $true)]
    [ValidateSet("arm64-v8a", "armeabi-v7a", "x86_64", "x86")]
    [string]$ProcessAbi,

    [string]$ReleaseTag = "downloadable-runtime-v2.1.5-bss.2-exp.2",

    [string]$CacheRoot = ""
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$adb = (Get-Command adb -ErrorAction Stop).Source

$assetByAbi = @{
    "arm64-v8a" = [ordered]@{
        fileName = "litert-cpu-core-2.1.5-bss.2-arm64-v8a.zip"
        sha256 = "ba566a2b0d3ee95190bced05bf20dff790ef90602af066fc502540c160a0ce49"
    }
    "armeabi-v7a" = [ordered]@{
        fileName = "litert-cpu-core-2.1.5-bss.2-armeabi-v7a.zip"
        sha256 = "a5c21e9c64030ae9a2a29c6d458e3f7b63018b766981064d570f19082c2c19c1"
    }
    "x86_64" = [ordered]@{
        fileName = "litert-cpu-core-2.1.5-bss.2-x86_64.zip"
        sha256 = "0425958720617ee00689af1efb1ac2dd03da8481ad0e03c42e0dff7006bc4d85"
    }
    "x86" = [ordered]@{
        fileName = "litert-cpu-core-2.1.5-bss.2-x86.zip"
        sha256 = "8ece235a9c1da2478c0ff6d5f7f13908aaca5dec7c8393a36c8058975bd4c975"
    }
}

function Invoke-Adb {
    & $adb -s $Serial @args
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed with exit code ${LASTEXITCODE}: $args"
    }
}

function Get-Sha256([string]$Path) {
    (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

$asset = $assetByAbi[$ProcessAbi]
if ($null -eq $asset) {
    throw "No pinned LiteRT CPU asset exists for $ProcessAbi."
}
if ([string]::IsNullOrWhiteSpace($CacheRoot)) {
    $CacheRoot = Join-Path $repoRoot "build\litert-runtime-cache"
}
$releaseDirectory = Join-Path $CacheRoot $ReleaseTag
$zipPath = Join-Path $releaseDirectory $asset.fileName
$extractDirectory = Join-Path $releaseDirectory ($asset.fileName -replace '\.zip$', '')
$manifestPath = Join-Path $extractDirectory "manifest.json"
$libraryPath = Join-Path $extractDirectory "libLiteRt.so"
$remoteTempRoot = "/data/local/tmp/booming-ss-litert-runtime-$([guid]::NewGuid().ToString('N'))"
$remoteRuntimeRoot = "no_backup/source-separation/runtimes/cpu/$ProcessAbi/current"

try {
    New-Item -ItemType Directory -Force -Path $releaseDirectory | Out-Null
    if (-not (Test-Path -LiteralPath $zipPath) -or (Get-Sha256 $zipPath) -ne $asset.sha256) {
        $url = "https://github.com/WluhWluh/bss-litert-android/releases/download/$ReleaseTag/$($asset.fileName)"
        $partPath = "$zipPath.part"
        Invoke-WebRequest -Uri $url -OutFile $partPath
        if ((Get-Sha256 $partPath) -ne $asset.sha256) {
            throw "LiteRT CPU ZIP SHA-256 mismatch for $ProcessAbi."
        }
        Move-Item -LiteralPath $partPath -Destination $zipPath -Force
    }
    if (-not (Test-Path -LiteralPath $manifestPath) -or
            -not (Test-Path -LiteralPath $libraryPath)) {
        if (Test-Path -LiteralPath $extractDirectory) {
            Remove-Item -LiteralPath $extractDirectory -Recurse -Force
        }
        Expand-Archive -LiteralPath $zipPath -DestinationPath $extractDirectory
    }

    $manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
    if ($manifest.component -ne "cpu-core" -or
            $manifest.abi -ne $ProcessAbi -or
            $manifest.files.Count -ne 1 -or
            $manifest.files[0].path -ne "libLiteRt.so") {
        throw "LiteRT CPU manifest does not match the requested ABI/component."
    }
    $manifestLibrary = $manifest.files[0]
    if ((Get-Item -LiteralPath $libraryPath).Length -ne [int64]$manifestLibrary.byteSize -or
            (Get-Sha256 $libraryPath) -ne $manifestLibrary.sha256.ToLowerInvariant()) {
        throw "LiteRT CPU library does not match its component manifest."
    }

    $manifestSha256 = Get-Sha256 $manifestPath
    $nowEpochMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $installRecord = [ordered]@{
        schemaVersion = 1
        componentId = "litert-cpu-core-$($manifest.runtimeArtifactVersion)-$ProcessAbi"
        componentType = "cpu-core"
        producerReleaseTag = $ReleaseTag
        producerReleaseVersion = $manifest.releaseVersion
        runtimeArtifactVersion = $manifest.runtimeArtifactVersion
        abi = $ProcessAbi
        innerManifestSha256 = $manifestSha256
        librarySha256 = $manifestLibrary.sha256.ToLowerInvariant()
        installedAtEpochMs = $nowEpochMs
        lastValidatedAtEpochMs = $nowEpochMs
    }
    $installRecordPath = Join-Path $extractDirectory "install.json"
    $installRecord | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $installRecordPath -Encoding utf8

    Invoke-Adb shell am force-stop $Package
    Invoke-Adb shell rm -rf $remoteTempRoot
    Invoke-Adb shell mkdir -p $remoteTempRoot
    Invoke-Adb push $libraryPath "$remoteTempRoot/libLiteRt.so"
    Invoke-Adb push $manifestPath "$remoteTempRoot/manifest.json"
    Invoke-Adb push $installRecordPath "$remoteTempRoot/install.json"
    Invoke-Adb shell chmod 755 "$remoteTempRoot/libLiteRt.so"
    Invoke-Adb shell run-as $Package rm -rf $remoteRuntimeRoot
    Invoke-Adb shell run-as $Package mkdir -p $remoteRuntimeRoot
    Invoke-Adb shell run-as $Package cp "$remoteTempRoot/libLiteRt.so" "$remoteRuntimeRoot/libLiteRt.so"
    Invoke-Adb shell run-as $Package cp "$remoteTempRoot/manifest.json" "$remoteRuntimeRoot/manifest.json"
    Invoke-Adb shell run-as $Package cp "$remoteTempRoot/install.json" "$remoteRuntimeRoot/install.json"
    Invoke-Adb shell run-as $Package chmod 755 "$remoteRuntimeRoot/libLiteRt.so"

    $installedHash = ((& $adb -s $Serial shell run-as $Package sha256sum "$remoteRuntimeRoot/libLiteRt.so") -join " ").Trim()
    if ($LASTEXITCODE -ne 0 -or $installedHash -notmatch '^([0-9a-fA-F]{64})\s+') {
        throw "Could not hash the installed LiteRT CPU library."
    }
    if ($Matches[1].ToLowerInvariant() -ne $manifestLibrary.sha256.ToLowerInvariant()) {
        throw "Installed LiteRT CPU library hash does not match the manifest."
    }
    Write-Host "Installed LiteRT CPU runtime $($manifest.runtimeArtifactVersion) for $ProcessAbi."
} finally {
    & $adb -s $Serial shell rm -rf $remoteTempRoot 2>$null | Out-Null
}
