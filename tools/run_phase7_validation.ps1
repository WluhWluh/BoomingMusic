param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,

    [Parameter(Mandatory = $true)]
    [ValidateSet("arm64-v8a", "armeabi-v7a", "x86_64", "x86")]
    [string]$ProcessAbi,

    [string]$ModelId = "uvr_mdxnet_3_9662",

    [ValidateSet("identity", "acquisition", "worker", "lifecycle", "recreation", "playback")]
    [string]$Stage = "identity",

    [string]$SourcePath = "",

    [string]$FixtureId = "coast_town_full_mp3",
    [string]$RunId = "",
    [string]$CacheKey = "",
    [string]$OutputRoot = "",
    [string]$RunnerRevision = "phase7-runner-v4",
    [int]$ProcessorCount = 0,
    [int]$XnnPackFlags = -1,
    [ValidateSet("cold-session", "warm-session")]
    [string]$RunClass = "cold-session",
    [ValidateSet("pause-resume", "seek", "cancellation", "sequential")]
    [string]$LifecycleScenario = "sequential",
    [ValidateSet("single-use", "shared-reusable")]
    [string]$LifecycleSessionMode = "single-use",
    [bool]$WindowDecode = $true,
    [switch]$SkipBuild,
    [switch]$KeepAppData,
    [switch]$CleanInstallScenario,
    [switch]$PreserveMediaStoreSource,
    [switch]$ExportCacheAudio
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$package = "com.wluhwluh.booming.sourcesep.debug"
$runner = "$package.test/androidx.test.runner.AndroidJUnitRunner"
$sourceStages = @("worker", "lifecycle", "recreation", "playback")
$testClass = if ($Stage -in $sourceStages) {
    "com.mardous.booming.separation.SourceSeparationPhase7WorkerDeviceTest"
} else {
    "com.mardous.booming.separation.SourceSeparationPhase7DeviceTest"
}
$testMethod = switch ($Stage) {
    "acquisition" { "validatePinnedAcquisition"; break }
    "worker" { "validateProductionWorkerCpu"; break }
    "lifecycle" { "validateWorkerLifecycle"; break }
    "recreation" { "validateCompletedCacheAfterProcessRestart"; break }
    "playback" { "validateMediaSessionPlayback"; break }
    default { "validateDeviceEvidenceIdentity" }
}
$reportStage = $Stage
$adb = (Get-Command adb -ErrorAction Stop).Source
$catalogPath = Join-Path $repoRoot "app/src/main/assets/source-separation/model-catalog-v2.json"
$thresholdsPath = Join-Path $repoRoot "docs/validation/litert-phase7/thresholds-v1.json"
$fixturesPath = Join-Path $repoRoot "docs/validation/litert-phase7/fixtures-v1.json"

if ([string]::IsNullOrWhiteSpace($RunId)) {
    $safeSerial = $Serial -replace '[^A-Za-z0-9._-]', '_'
    $RunId = "{0}-{1}-{2}-{3:yyyyMMdd-HHmmss}" -f $safeSerial, $ProcessAbi, $Stage, (Get-Date)
}
if ($RunId -notmatch '^[A-Za-z0-9._-]{1,120}$') {
    throw "RunId contains unsupported characters: $RunId"
}
if ($Stage -eq "acquisition" -and $KeepAppData) {
    throw "The pinned acquisition stage requires a clean app-data scenario."
}
if ($Stage -in $sourceStages -and -not $KeepAppData) {
    throw "The $Stage stage expects a previously acquired model. Use -KeepAppData."
}
if ($Stage -in $sourceStages -and [string]::IsNullOrWhiteSpace($SourcePath)) {
    throw "SourcePath is required for the $Stage stage."
}
if ($ProcessorCount -lt 0) {
    throw "ProcessorCount must be zero (device default) or a positive integer."
}
if ($XnnPackFlags -lt -1) {
    throw "XnnPackFlags must be -1 (runtime default) or a non-negative bitfield."
}
if ($Stage -notin $sourceStages -and ($ProcessorCount -gt 0 -or $XnnPackFlags -ge 0 -or
        $ExportCacheAudio -or
        $RunClass -ne "cold-session" -or $CleanInstallScenario -or
        -not $WindowDecode)) {
    throw "Runtime overrides apply only to worker, lifecycle, recreation, and playback stages."
}
if ($PreserveMediaStoreSource -and $Stage -ne "worker") {
    throw "PreserveMediaStoreSource applies only to the worker stage."
}
if ($Stage -ne "lifecycle" -and
        ($LifecycleScenario -ne "sequential" -or $LifecycleSessionMode -ne "single-use")) {
    throw "LifecycleScenario and LifecycleSessionMode apply only to the lifecycle stage."
}
if ($Stage -eq "playback" -and $CacheKey -notmatch '^[0-9a-f]{64}$') {
    throw "Playback stage requires a 64-character lowercase cache key."
}
if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $OutputRoot = Join-Path $repoRoot "build\phase7-validation"
}

function Invoke-Adb {
    & $adb -s $Serial @args
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed with exit code ${LASTEXITCODE}: $args"
    }
}

function Read-RemoteFile([string]$RelativePath) {
    $result = & $adb -s $Serial exec-out run-as $package cat $RelativePath
    if ($LASTEXITCODE -ne 0) {
        throw "Could not read remote file: $RelativePath"
    }
    return ($result -join "`n")
}

function Get-Sha256([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Assert-ExportedFile(
    [string]$Path,
    [int64]$ExpectedBytes,
    [string]$ExpectedSha256
) {
    if ($ExpectedBytes -le 0 -or $ExpectedSha256 -notmatch '^[0-9a-f]{64}$') {
        throw "The device report contains an invalid artifact identity: $Path"
    }
    $actualBytes = (Get-Item -LiteralPath $Path).Length
    $actualSha256 = Get-Sha256 $Path
    if ($actualBytes -ne $ExpectedBytes -or $actualSha256 -ne $ExpectedSha256) {
        throw "Exported artifact identity mismatch: $Path"
    }
}

function Assert-RemoteArtifactPath([string]$Path, [string]$Directory) {
    if ([string]::IsNullOrWhiteSpace($Path) -or
            -not $Path.StartsWith("$Directory/", [System.StringComparison]::Ordinal) -or
            $Path.Contains("..", [System.StringComparison]::Ordinal)) {
        throw "The device report contains an unsafe artifact path: $Path"
    }
}

function Export-RemoteFile([string]$RemotePath, [string]$LocalPath) {
    $parent = Split-Path -Parent $LocalPath
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $adb
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    foreach ($argument in @("-s", $Serial, "exec-out", "run-as", $package, "cat", $RemotePath)) {
        [void]$startInfo.ArgumentList.Add($argument)
    }
    $process = [System.Diagnostics.Process]::Start($startInfo)
    $errorTask = $process.StandardError.ReadToEndAsync()
    try {
        $output = [System.IO.File]::Create($LocalPath)
        try {
            $process.StandardOutput.BaseStream.CopyTo($output)
        } finally {
            $output.Dispose()
        }
        $process.WaitForExit()
        $errorText = $errorTask.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) {
            Remove-Item -LiteralPath $LocalPath -Force -ErrorAction SilentlyContinue
            throw "Could not export remote file '$RemotePath': $errorText"
        }
    } finally {
        $process.Dispose()
    }
}

function Get-CatalogModel([string]$Id) {
    $catalog = Get-Content -LiteralPath $catalogPath -Raw | ConvertFrom-Json
    $entry = @($catalog.entries) | Where-Object { $_.modelId -eq $Id } | Select-Object -First 1
    if ($null -eq $entry) { throw "Model is absent from the bundled catalog: $Id" }
    $artifact = @($catalog.artifacts) | Where-Object { $_.artifactId -eq $entry.artifactId } | Select-Object -First 1
    if ($null -eq $artifact -or $null -eq $artifact.tflite) { throw "Model has no TFLite artifact: $Id" }
    $contract = @($catalog.contracts) | Where-Object { $_.contractId -eq $entry.contractId } | Select-Object -First 1
    if ($null -eq $contract) { throw "Model contract is absent from the bundled catalog: $Id" }
    return @{ Catalog = $catalog; Entry = $entry; Contract = $contract; Artifact = $artifact }
}

$model = Get-CatalogModel $ModelId
$catalogSha256 = Get-Sha256 $catalogPath
$thresholds = Get-Content -LiteralPath $thresholdsPath -Raw | ConvertFrom-Json
$fixtures = Get-Content -LiteralPath $fixturesPath -Raw | ConvertFrom-Json
$catalogSourceRevision = "746bee43db9ece9ec8214c1c74269e21547aed58"
$pipelineVersion = "$($model.Contract.pipelineCompatibility.pipelineId)-v$($model.Contract.pipelineCompatibility.minimumVersion)"
$artifact = $model.Artifact.tflite
$cpuProfileId = if ($XnnPackFlags -ge 0) {
    "cpu-xnnpack-flags-$XnnPackFlags-fp32-v1"
} else {
    "cpu-default-fp32-v1"
}
$fixture = @($fixtures.fixtures) | Where-Object { $_.fixtureId -eq $FixtureId } | Select-Object -First 1
if ($Stage -in $sourceStages) {
    if ($null -eq $fixture) { throw "Fixture is absent from fixtures-v1.json: $FixtureId" }
    if (-not (Test-Path -LiteralPath $SourcePath -PathType Leaf)) {
        throw "Source fixture file not found: $SourcePath"
    }
    $sourcePath = (Resolve-Path -LiteralPath $SourcePath).Path
    $sourceBytes = (Get-Item -LiteralPath $sourcePath).Length
    $sourceSha256 = Get-Sha256 $sourcePath
    if ($sourceBytes -ne [int64]$fixture.byteSize -or $sourceSha256 -ne $fixture.sha256) {
        throw "Source fixture identity does not match $FixtureId."
    }
}
$appCommit = (& git -C $repoRoot rev-parse HEAD).Trim()
$appApk = $null
$testApk = $null
$remoteSourcePath = ""
$remoteArtifactDirectory = ""
$expectedRemoteArtifactDirectory = "files/phase7-validation-artifacts/$RunId"

if ([string]::IsNullOrWhiteSpace($OutputRoot)) { throw "OutputRoot must not be empty." }
New-Item -ItemType Directory -Force -Path $OutputRoot | Out-Null

Push-Location $repoRoot
try {
    if (-not $SkipBuild) {
        & .\gradlew.bat :app:assembleGithubDebug --console=plain
        if ($LASTEXITCODE -ne 0) { throw "Phase 7 app assembly failed." }
        & .\gradlew.bat :app:assembleGithubDebugAndroidTest --console=plain
        if ($LASTEXITCODE -ne 0) { throw "Phase 7 AndroidTest assembly failed." }
    }

    $appApk = Get-ChildItem "app\build\outputs\apk\github\debug" -Filter "*-$ProcessAbi.apk" |
        Select-Object -First 1
    $testApk = Get-ChildItem "app\build\outputs\apk\androidTest\github\debug" -Filter "*.apk" |
        Select-Object -First 1
    if ($null -eq $appApk) { throw "No $ProcessAbi app split was found." }
    if ($null -eq $testApk) { throw "No GitHub debug AndroidTest APK was found." }

    $appApkSha256 = Get-Sha256 $appApk.FullName
    $testApkSha256 = Get-Sha256 $testApk.FullName
    $litertSha256 = ""
    $runtimeAsset = Get-ChildItem "app\build\intermediates\merged_native_libs\githubDebug\out\lib\$ProcessAbi\libLiteRt.so" -ErrorAction SilentlyContinue
    if ($null -ne $runtimeAsset) { $litertSha256 = Get-Sha256 $runtimeAsset.FullName }

    Invoke-Adb install -r -t $appApk.FullName
    Invoke-Adb install -r -t $testApk.FullName
    if (-not $KeepAppData) { Invoke-Adb shell pm clear $package }
    & $adb -s $Serial shell pm grant $package android.permission.READ_EXTERNAL_STORAGE 2>$null | Out-Null
    & $adb -s $Serial shell pm grant $package android.permission.WRITE_EXTERNAL_STORAGE 2>$null | Out-Null
    & $adb -s $Serial shell pm grant $package android.permission.READ_MEDIA_AUDIO 2>$null | Out-Null

    $instrumentArguments = @(
        "-e", "class", "$testClass#$testMethod",
        "-e", "runId", $RunId,
        "-e", "serial", $Serial,
        "-e", "processAbi", $ProcessAbi,
        "-e", "modelId", $ModelId,
        "-e", "artifactSha256", $artifact.sha256,
        "-e", "artifactFileName", $artifact.fileName,
        "-e", "contractId", $model.Entry.contractId,
        "-e", "contractSchemaVersion", [string]$model.Contract.contractSchemaVersion,
        "-e", "profileId", $cpuProfileId,
        "-e", "appCommit", $appCommit,
        "-e", "appApkSha256", $appApkSha256,
        "-e", "testApkSha256", $testApkSha256,
        "-e", "catalogSha256", $catalogSha256,
        "-e", "catalogSourceRevision", $catalogSourceRevision,
        "-e", "modelReleaseTag", $artifact.releaseAsset.tag,
        "-e", "pipelineCompatibilityVersion", $pipelineVersion,
        "-e", "litertVersion", "2.1.5",
        "-e", "runnerRevision", $RunnerRevision,
        "-e", "thresholdsVersion", $thresholds.schemaVersion,
        "-e", "fixturesVersion", $fixtures.schemaVersion,
        "-e", "cleanInstallScenario", $((-not $KeepAppData).ToString().ToLowerInvariant())
    )
    if ($Stage -in $sourceStages) {
        $sourceLeaf = Split-Path -Leaf $sourcePath
        if ($sourceLeaf -notmatch '^[A-Za-z0-9._-]+$') {
            throw "Source fixture filename contains unsupported characters: $sourceLeaf"
        }
        $remoteSourcePath = "/storage/emulated/0/Music/booming-ss-phase7-$RunId-$sourceLeaf"
        & $adb -s $Serial shell mkdir -p /storage/emulated/0/Music
        if ($LASTEXITCODE -ne 0) { throw "Could not create the shared Music directory." }
        Invoke-Adb push $sourcePath $remoteSourcePath
        Invoke-Adb shell chmod 644 $remoteSourcePath
        $instrumentArguments += @(
            "-e", "sourcePath", $remoteSourcePath,
            "-e", "fixtureId", $fixture.fixtureId,
            "-e", "fixtureFileName", $fixture.fileName,
            "-e", "fixtureBytes", [string]$fixture.byteSize,
            "-e", "fixtureSha256", $fixture.sha256,
            "-e", "fixtureDurationUs", [string]$fixture.durationUs,
            "-e", "fixtureSampleRate", [string]$fixture.sampleRate,
            "-e", "fixtureChannels", [string]$fixture.channels,
            "-e", "fixtureCodec", $fixture.codec,
            "-e", "fixtureDecodeClass", $fixture.decodeClass
        )
        $instrumentArguments += @(
            "-e", "runClass", $RunClass,
            "-e", "cleanInstallScenario", $CleanInstallScenario.ToString().ToLowerInvariant(),
            "-e", "windowDecodeEnabled", $WindowDecode.ToString().ToLowerInvariant()
        )
        if ($Stage -eq "playback") {
            $instrumentArguments += @("-e", "cacheKey", $CacheKey)
        }
        if ($Stage -eq "lifecycle") {
            $instrumentArguments += @(
                "-e", "lifecycleScenario", $LifecycleScenario,
                "-e", "lifecycleSessionMode", $LifecycleSessionMode
            )
        }
        if ($Stage -eq "worker" -and $PreserveMediaStoreSource) {
            $instrumentArguments += @(
                "-e", "preserveMediaStoreSource",
                "true"
            )
        }
        if ($Stage -eq "worker" -and $ExportCacheAudio) {
            $instrumentArguments += @("-e", "exportCacheAudio", "true")
        }
        if ($ProcessorCount -gt 0) {
            $instrumentArguments += @("-e", "processorCount", [string]$ProcessorCount)
        }
        if ($XnnPackFlags -ge 0) {
            $instrumentArguments += @("-e", "xnnPackFlags", [string]$XnnPackFlags)
        }
    }
    if (-not [string]::IsNullOrWhiteSpace($litertSha256)) {
        $instrumentArguments += @("-e", "litertSha256", $litertSha256)
    }

    Invoke-Adb shell am force-stop $package
    $instrumentOutput = & $adb -s $Serial shell am instrument -w -r @instrumentArguments $runner
    $instrumentExit = $LASTEXITCODE
    $instrumentText = $instrumentOutput -join "`n"
    Write-Host $instrumentText
    $remoteReport = "files/phase7-validation-reports/$RunId-$reportStage.json"
    $reportText = Read-RemoteFile $remoteReport

    $deviceDirectory = Join-Path $OutputRoot ($Serial -replace '[^A-Za-z0-9._-]', '_')
    New-Item -ItemType Directory -Force -Path $deviceDirectory | Out-Null
    $reportPath = Join-Path $deviceDirectory "$RunId-$reportStage.json"
    $reportText | Set-Content -LiteralPath $reportPath -Encoding utf8

    if ($Stage -eq "worker" -and $ExportCacheAudio) {
        $reportObject = $reportText | ConvertFrom-Json
        if ($reportObject.status -ne "passed") {
            throw "Cannot export cache audio from a failed worker report: $reportPath"
        }
        $artifactDirectory = Join-Path $deviceDirectory "$RunId-artifacts"
        $exportedArtifacts = @()
        $artifactExport = $reportObject.cache.artifactExport
        $remoteArtifactDirectory = [string]$artifactExport.directoryPathRelative
        if ($remoteArtifactDirectory -ne $expectedRemoteArtifactDirectory) {
            throw "The worker report provided an unexpected artifact directory."
        }
        $cacheManifestRemotePath = [string]$artifactExport.manifestPathRelative
        Assert-RemoteArtifactPath `
            -Path $cacheManifestRemotePath `
            -Directory $remoteArtifactDirectory
        $cacheManifestLocalPath = Join-Path $artifactDirectory "cache-manifest.json"
        Export-RemoteFile -RemotePath $cacheManifestRemotePath -LocalPath $cacheManifestLocalPath
        Assert-ExportedFile `
            -Path $cacheManifestLocalPath `
            -ExpectedBytes ([int64]$artifactExport.manifestByteSize) `
            -ExpectedSha256 ([string]$artifactExport.manifestSha256)
        foreach ($stem in @($reportObject.cache.stems)) {
            $semantic = ([string]$stem.semantic).ToLowerInvariant() -replace '[^a-z0-9._-]', '-'
            foreach ($format in @(
                @{
                    Name = "wav"
                    RemotePath = [string]$stem.exportWavPathRelative
                    ExpectedBytes = [int64]$stem.wavByteSize
                    ExpectedSha256 = [string]$stem.wavSha256
                },
                @{
                    Name = "flac"
                    RemotePath = [string]$stem.exportPromotedPathRelative
                    ExpectedBytes = [int64]$stem.promotedByteSize
                    ExpectedSha256 = [string]$stem.promotedSha256
                }
            )) {
                if ([string]::IsNullOrWhiteSpace($format.RemotePath)) { continue }
                Assert-RemoteArtifactPath `
                    -Path $format.RemotePath `
                    -Directory $remoteArtifactDirectory
                $localPath = Join-Path $artifactDirectory "$semantic.$($format.Name)"
                Export-RemoteFile -RemotePath $format.RemotePath -LocalPath $localPath
                Assert-ExportedFile `
                    -Path $localPath `
                    -ExpectedBytes $format.ExpectedBytes `
                    -ExpectedSha256 $format.ExpectedSha256
                $exportedArtifacts += [ordered]@{
                    semantic = [string]$stem.semantic
                    format = $format.Name
                    fileName = Split-Path -Leaf $localPath
                    bytes = (Get-Item -LiteralPath $localPath).Length
                    sha256 = Get-Sha256 $localPath
                }
            }
        }
        $artifactManifest = [ordered]@{
            schemaVersion = "phase7-artifacts-v1"
            runId = $RunId
            reportFileName = Split-Path -Leaf $reportPath
            cacheKey = [string]$reportObject.cache.cacheKey
            fixtureSha256 = [string]$reportObject.fixture.sha256
            modelArtifactSha256 = [string]$reportObject.identity.artifactSha256
            cacheManifestFileName = Split-Path -Leaf $cacheManifestLocalPath
            cacheManifestSha256 = Get-Sha256 $cacheManifestLocalPath
            artifacts = $exportedArtifacts
        }
        $artifactManifestPath = Join-Path $artifactDirectory "manifest.json"
        $artifactManifest | ConvertTo-Json -Depth 8 |
            Set-Content -LiteralPath $artifactManifestPath -Encoding utf8
        Write-Host "Exported Phase 7 cache audio to $artifactDirectory"
    }

    foreach ($diagnostic in @(
        @{ Name = "meminfo"; Arguments = @("shell", "dumpsys", "meminfo", $package) },
        @{ Name = "battery"; Arguments = @("shell", "dumpsys", "battery") },
        @{ Name = "thermal"; Arguments = @("shell", "dumpsys", "thermalservice") }
    )) {
        $diagnosticText = & $adb -s $Serial @($diagnostic.Arguments) 2>$null
        ($diagnosticText -join "`n") | Set-Content -LiteralPath `
            (Join-Path $deviceDirectory "$RunId-$($diagnostic.Name).txt") -Encoding utf8
    }

    $envelope = [ordered]@{
        schemaVersion = "phase7-inputs-v1"
        runId = $RunId
        appCommit = $appCommit
        appApk = [ordered]@{ path = $appApk.Name; sha256 = $appApkSha256; bytes = $appApk.Length }
        testApk = [ordered]@{ path = $testApk.Name; sha256 = $testApkSha256; bytes = $testApk.Length }
        catalog = [ordered]@{ path = "app/src/main/assets/source-separation/model-catalog-v2.json"; sha256 = $catalogSha256; sourceRevision = $catalogSourceRevision }
        model = [ordered]@{ modelId = $ModelId; releaseTag = $artifact.releaseAsset.tag; fileName = $artifact.fileName; sha256 = $artifact.sha256; contractId = $model.Entry.contractId; contractSchemaVersion = $model.Contract.contractSchemaVersion; pipelineCompatibilityVersion = $pipelineVersion }
        runtime = [ordered]@{ id = "litert"; version = "2.1.5"; abi = $ProcessAbi; nativeLibrarySha256 = $litertSha256 }
        thresholdsVersion = $thresholds.schemaVersion
        fixturesVersion = $fixtures.schemaVersion
        runnerRevision = $RunnerRevision
        run = if ($Stage -in $sourceStages) {
            [ordered]@{
                class = $RunClass
                cleanInstallScenario = [bool]$CleanInstallScenario
                windowDecodeEnabled = $WindowDecode
                preserveMediaStoreSource = [bool]$PreserveMediaStoreSource
                processorCountOverride = if ($ProcessorCount -gt 0) { $ProcessorCount } else { $null }
                xnnPackFlags = if ($XnnPackFlags -ge 0) { $XnnPackFlags } else { $null }
                lifecycleScenario = if ($Stage -eq "lifecycle") { $LifecycleScenario } else { $null }
                lifecycleSessionMode = if ($Stage -eq "lifecycle") { $LifecycleSessionMode } else { $null }
            }
        } else { $null }
    }
    $envelopePath = Join-Path $deviceDirectory "$RunId-inputs.json"
    $envelope | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $envelopePath -Encoding utf8

    if ($instrumentExit -ne 0 -or $instrumentText -notmatch 'OK \(1 test\)') {
        throw "Phase 7 $reportStage instrumentation failed. Report: $reportPath"
    }
    Write-Host "Saved Phase 7 $reportStage report to $reportPath"
    Write-Host "Saved Phase 7 input envelope to $envelopePath"
} finally {
    if (-not [string]::IsNullOrWhiteSpace($remoteSourcePath)) {
        & $adb -s $Serial shell rm -f $remoteSourcePath 2>$null | Out-Null
    }
    if ($remoteArtifactDirectory -eq $expectedRemoteArtifactDirectory) {
        & $adb -s $Serial shell run-as $package rm -rf -- `
            $remoteArtifactDirectory 2>$null | Out-Null
    }
    Pop-Location
}
