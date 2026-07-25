param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,

    [Parameter(Mandatory = $true)]
    [ValidateSet("arm64-v8a", "armeabi-v7a", "x86_64", "x86")]
    [string]$ProcessAbi,

    [string]$ModelId = "uvr_mdxnet_3_9662",
    [string]$SecondaryModelId = "",

    [ValidateSet(
        "identity",
        "acquisition",
        "worker",
        "process-matrix",
        "process-switch-matrix",
        "process-fault-matrix",
        "process-cache-matrix",
        "process-cache-race-matrix",
        "lifecycle",
        "recreation",
        "playback",
        "switching",
        "background",
        "prefetch"
    )]
    [string]$Stage = "identity",

    [string]$SourcePath = "",
    [string]$CurrentSourcePath = "",

    [string]$FixtureId = "coast_town_full_mp3",
    [string]$CurrentFixtureId = "",
    [string]$RunId = "",
    [string]$CacheKey = "",
    [string]$OutputRoot = "",
    [string]$RunnerRevision = "phase7-runner-v14",
    [ValidateSet("cpu", "auto")]
    [string]$BackendMode = "cpu",
    [ValidateSet("in-process", "bound-remote")]
    [string]$ExecutionHostMode = "in-process",
    [ValidateSet("none", "setup", "probe", "invocation-after-ready")]
    [string]$AutoFailpoint = "none",
    [int]$ProcessorCount = 0,
    [int]$XnnPackFlags = -1,
    [ValidateSet("cold-session", "warm-session")]
    [string]$RunClass = "cold-session",
    [ValidateSet("pause-resume", "seek", "cancellation", "sequential")]
    [string]$LifecycleScenario = "sequential",
    [ValidateSet("all", "death", "cache-clear")]
    [string]$ProcessCacheScope = "all",
    [ValidateSet("single-use", "shared-reusable")]
    [string]$LifecycleSessionMode = "single-use",
    [bool]$WindowDecode = $true,
    [switch]$SkipBuild,
    [switch]$SkipInstall,
    [switch]$KeepAppData,
    [switch]$CleanInstallScenario,
    [switch]$PreserveMediaStoreSource,
    [switch]$ExportCacheAudio,
    [switch]$RebindAfterCompletion,
    [switch]$ScreenOffAfterReady,
    [switch]$X86ProcessValidation
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$package = "com.wluhwluh.booming.sourcesep.debug"
$runner = "$package.test/androidx.test.runner.AndroidJUnitRunner"
$sourceStages = @(
    "worker",
    "process-matrix",
    "process-switch-matrix",
    "process-fault-matrix",
    "process-cache-matrix",
    "process-cache-race-matrix",
    "lifecycle",
    "recreation",
    "playback",
    "switching",
    "background",
    "prefetch"
)
$testClass = if ($Stage -in $sourceStages) {
    "com.mardous.booming.separation.SourceSeparationPhase7WorkerDeviceTest"
} else {
    "com.mardous.booming.separation.SourceSeparationPhase7DeviceTest"
}
$testMethod = switch ($Stage) {
    "acquisition" { "validatePinnedAcquisition"; break }
    "worker" { "validateProductionWorker"; break }
    "process-matrix" { "validateProcessSessionMatrix"; break }
    "process-switch-matrix" { "validateProcessModelSwitchMatrix"; break }
    "process-fault-matrix" { "validateProcessFaultMatrix"; break }
    "process-cache-matrix" { "validateProcessCacheSafetyMatrix"; break }
    "process-cache-race-matrix" { "validateProcessCacheManagementRaces"; break }
    "lifecycle" { "validateWorkerLifecycle"; break }
    "recreation" { "validateCompletedCacheAfterProcessRestart"; break }
    "playback" { "validateMediaSessionPlayback"; break }
    "switching" { "validateActiveModelSwitch"; break }
    "background" { "validateBackgroundServiceContinuation"; break }
    "prefetch" { "validateNextSongPrefetch"; break }
    default { "validateDeviceEvidenceIdentity" }
}
$reportStage = $Stage
$adb = (Get-Command adb -ErrorAction Stop).Source
$deviceUserOutput = & $adb -s $Serial shell am get-current-user 2>$null
$deviceUserId = if ($null -eq $deviceUserOutput) {
    ""
} else {
    ([string]($deviceUserOutput | Select-Object -First 1)).Trim()
}
if ($LASTEXITCODE -ne 0 -or $deviceUserId -notmatch '^\d+$') {
    throw "Could not resolve the numeric current Android user for $Serial."
}
$catalogPath = Join-Path $repoRoot "app/src/main/assets/source-separation/model-catalog-v2.json"
$thresholdsPath = Join-Path $repoRoot "docs/validation/litert-phase7/thresholds-v2.json"
$fixturesPath = Join-Path $repoRoot "docs/validation/litert-phase7/fixtures-v2.json"

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
if ($SkipInstall -and $Stage -notin $sourceStages) {
    throw "SkipInstall applies only to an execution stage with an already installed APK."
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
if ($BackendMode -eq "auto" -and ($ProcessorCount -gt 0 -or $XnnPackFlags -ge 0)) {
    throw "ProcessorCount and XnnPackFlags are CPU-only diagnostics and cannot be combined with BackendMode=auto."
}
if ($AutoFailpoint -ne "none" -and ($BackendMode -ne "auto" -or $Stage -ne "worker")) {
    throw "AutoFailpoint requires BackendMode=auto and Stage=worker."
}
if ($ExecutionHostMode -eq "bound-remote" -and
        ($Stage -notin @("worker", "background", "process-matrix", "process-switch-matrix", "process-fault-matrix", "process-cache-matrix", "process-cache-race-matrix") -or
        $BackendMode -ne "auto" -or $AutoFailpoint -ne "none")) {
    throw "BoundRemote requires a supported process stage, BackendMode=auto, and AutoFailpoint=none."
}
if ($Stage -in @("process-matrix", "process-switch-matrix", "process-fault-matrix", "process-cache-matrix", "process-cache-race-matrix") -and
        (-not $X86ProcessValidation -or $ProcessAbi -ne "x86" -or
        $ExecutionHostMode -ne "bound-remote" -or $BackendMode -ne "auto")) {
    throw "$Stage requires pure x86, X86ProcessValidation, and BoundRemote Auto."
}
if ($X86ProcessValidation -and $ProcessAbi -ne "x86") {
    throw "X86ProcessValidation can only build and run the pure-x86 target."
}
if ($X86ProcessValidation -and $Stage -in $sourceStages -and
        $ExecutionHostMode -ne "bound-remote") {
    throw "X86ProcessValidation execution stages require BoundRemote."
}
if ($PreserveMediaStoreSource -and $Stage -notin @("worker", "switching")) {
    throw "PreserveMediaStoreSource applies only to worker and switching stages."
}
if ($ScreenOffAfterReady -and $Stage -ne "background") {
    throw "ScreenOffAfterReady applies only to the background stage."
}
if ($RebindAfterCompletion -and
        ($Stage -ne "worker" -or $ExecutionHostMode -ne "bound-remote")) {
    throw "RebindAfterCompletion requires Stage=worker and ExecutionHostMode=bound-remote."
}
if ($Stage -ne "lifecycle" -and
        ($LifecycleScenario -ne "sequential" -or $LifecycleSessionMode -ne "single-use")) {
    throw "LifecycleScenario and LifecycleSessionMode apply only to the lifecycle stage."
}
if ($BackendMode -eq "auto" -and $Stage -eq "lifecycle" -and
        $LifecycleSessionMode -ne "single-use") {
    throw "BackendMode=auto uses the production single-use session provider; shared-reusable is CPU-only."
}
if ($Stage -eq "playback" -and $CacheKey -notmatch '^[0-9a-f]{64}$') {
    throw "Playback stage requires a 64-character lowercase cache key."
}
if ($Stage -in @("switching", "process-switch-matrix", "process-cache-race-matrix") -and
        [string]::IsNullOrWhiteSpace($SecondaryModelId)) {
    throw "$Stage requires SecondaryModelId."
}
if (-not [string]::IsNullOrWhiteSpace($SecondaryModelId) -and
        $SecondaryModelId -eq $ModelId) {
    throw "SecondaryModelId must differ from ModelId."
}
if ($Stage -in @("background", "prefetch") -and $BackendMode -ne "auto") {
    throw "$Stage uses the production service graph and requires BackendMode=auto."
}
if ($Stage -in @("prefetch", "process-matrix", "process-cache-matrix", "process-cache-race-matrix") -and
        ([string]::IsNullOrWhiteSpace($CurrentSourcePath) -or
        [string]::IsNullOrWhiteSpace($CurrentFixtureId))) {
    throw "$Stage requires CurrentSourcePath and CurrentFixtureId."
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

function Get-InstalledApkSha256([string]$PackageName) {
    $packagePaths = @(
        & $adb -s $Serial shell pm path $PackageName |
            ForEach-Object {
                $line = ([string]$_).Trim()
                if ($line.StartsWith("package:", [System.StringComparison]::Ordinal)) {
                    $line.Substring("package:".Length)
                }
            } |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    )
    if ($LASTEXITCODE -ne 0 -or $packagePaths.Count -ne 1) {
        throw "Expected one installed base APK for $PackageName; found $($packagePaths.Count)."
    }
    $hashLine = (& $adb -s $Serial shell sha256sum $packagePaths[0]).Trim()
    if ($LASTEXITCODE -ne 0 -or $hashLine -notmatch '^([0-9a-fA-F]{64})\s+') {
        throw "Could not hash the installed base APK for $PackageName."
    }
    return $Matches[1].ToLowerInvariant()
}

function Assert-InstalledApk(
    [string]$PackageName,
    [string]$ExpectedSha256
) {
    $actualSha256 = Get-InstalledApkSha256 $PackageName
    if ($actualSha256 -ne $ExpectedSha256) {
        throw ("Installed APK hash mismatch for {0}: expected {1}, actual {2}." -f
            $PackageName, $ExpectedSha256, $actualSha256)
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

function Get-AbiApkFromMetadata([string]$Directory, [string]$Abi) {
    $metadataPath = Join-Path $Directory "output-metadata.json"
    if (-not (Test-Path -LiteralPath $metadataPath -PathType Leaf)) {
        throw "APK output metadata is missing: $metadataPath"
    }
    $metadata = Get-Content -LiteralPath $metadataPath -Raw | ConvertFrom-Json
    $element = @($metadata.elements) | Where-Object {
        @($_.filters) | Where-Object {
            $_.filterType -eq "ABI" -and $_.value -eq $Abi
        }
    } | Select-Object -First 1
    if ($null -eq $element) {
        throw "No $Abi app split was declared in $metadataPath."
    }
    $path = Join-Path $Directory ([string]$element.outputFile)
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "The declared $Abi app split is missing: $path"
    }
    return Get-Item -LiteralPath $path
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
$secondaryModel = if ([string]::IsNullOrWhiteSpace($SecondaryModelId)) {
    $null
} else {
    Get-CatalogModel $SecondaryModelId
}
$catalogSha256 = Get-Sha256 $catalogPath
$thresholds = Get-Content -LiteralPath $thresholdsPath -Raw | ConvertFrom-Json
$fixtures = Get-Content -LiteralPath $fixturesPath -Raw | ConvertFrom-Json
$catalogSourceRevision = "28d9a076c8a44980085a059e6224768ae77f9c8a"
$pipelineVersion = "$($model.Contract.pipelineCompatibility.pipelineId)-v$($model.Contract.pipelineCompatibility.minimumVersion)"
$artifact = $model.Artifact.tflite
$profileId = if ($BackendMode -eq "auto") {
    "gpu-auto-fp32-v1"
} elseif ($XnnPackFlags -ge 0) {
    "cpu-xnnpack-flags-$XnnPackFlags-fp32-v1"
} else {
    "cpu-default-fp32-v1"
}
$backendName = if ($BackendMode -eq "auto") { "LiteRtAuto" } else { "LiteRtCpu" }
$fixture = @($fixtures.fixtures) | Where-Object { $_.fixtureId -eq $FixtureId } | Select-Object -First 1
$currentFixture = if ($Stage -in @("prefetch", "process-matrix", "process-cache-matrix", "process-cache-race-matrix")) {
    @($fixtures.fixtures) |
        Where-Object { $_.fixtureId -eq $CurrentFixtureId } |
        Select-Object -First 1
} else {
    $null
}
if ($Stage -in $sourceStages) {
    if ($null -eq $fixture) { throw "Fixture is absent from fixtures-v2.json: $FixtureId" }
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
if ($Stage -in @("prefetch", "process-matrix", "process-cache-matrix", "process-cache-race-matrix")) {
    if ($null -eq $currentFixture) {
        throw "Fixture is absent from fixtures-v2.json: $CurrentFixtureId"
    }
    if (-not (Test-Path -LiteralPath $CurrentSourcePath -PathType Leaf)) {
        throw "Current source fixture file not found: $CurrentSourcePath"
    }
    $currentSourcePath = (Resolve-Path -LiteralPath $CurrentSourcePath).Path
    $currentSourceBytes = (Get-Item -LiteralPath $currentSourcePath).Length
    $currentSourceSha256 = Get-Sha256 $currentSourcePath
    if ($currentSourceBytes -ne [int64]$currentFixture.byteSize -or
            $currentSourceSha256 -ne $currentFixture.sha256) {
        throw "Current source fixture identity does not match $CurrentFixtureId."
    }
}
$sourceCommitAtInvocation = (& git -C $repoRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $sourceCommitAtInvocation -notmatch '^[0-9a-f]{40}$') {
    throw "Could not resolve the source commit for the Phase 7 build."
}
$appCommit = ""
$appApk = $null
$testApk = $null
$remoteSourcePath = ""
$remoteSourceRelativePath = ""
$remoteCurrentSourcePath = ""
$remoteCurrentSourceRelativePath = ""
$remoteArtifactDirectory = ""
$expectedRemoteArtifactDirectory = "files/phase7-validation-artifacts/$RunId"
$backgroundScreenTimeoutMs = 30 * 60 * 1000
$originalScreenOffTimeout = ""
$screenTimeoutChanged = $false

if ([string]::IsNullOrWhiteSpace($OutputRoot)) { throw "OutputRoot must not be empty." }
New-Item -ItemType Directory -Force -Path $OutputRoot | Out-Null

Push-Location $repoRoot
try {
    if (-not $SkipBuild) {
        $validationGradleArgument = if ($X86ProcessValidation) {
            @("-PboomingSs.x86ProcessValidation=true")
        } else {
            @()
        }
        $androidTestBuildArguments = @(
            ":app:assembleGithubDebugAndroidTest",
            "--console=plain"
        ) + $validationGradleArgument
        & .\gradlew.bat @androidTestBuildArguments
        if ($LASTEXITCODE -ne 0) { throw "Phase 7 AndroidTest assembly failed." }
        # AndroidTest configuration emits a universal app APK. Rebuild the app
        # last so output metadata and files describe the requested ABI splits.
        $appBuildArguments = @(
            ":app:assembleGithubDebug",
            "--console=plain"
        ) + $validationGradleArgument
        & .\gradlew.bat @appBuildArguments
        if ($LASTEXITCODE -ne 0) { throw "Phase 7 app assembly failed." }
    }

    $appApk = Get-AbiApkFromMetadata `
        -Directory "app\build\outputs\apk\github\debug" `
        -Abi $ProcessAbi
    $testApk = Get-ChildItem "app\build\outputs\apk\androidTest\github\debug" -Filter "*.apk" |
        Select-Object -First 1
    if ($null -eq $appApk) { throw "No $ProcessAbi app split was found." }
    if ($null -eq $testApk) { throw "No GitHub debug AndroidTest APK was found." }

    $appApkSha256 = Get-Sha256 $appApk.FullName
    $testApkSha256 = Get-Sha256 $testApk.FullName
    $buildIdentityPath = Join-Path `
        "app\build\outputs\apk\github\debug" `
        "phase7-build-identity-v1.json"
    if ($SkipBuild) {
        if (-not (Test-Path -LiteralPath $buildIdentityPath -PathType Leaf)) {
            throw "Phase 7 build identity is missing. Re-run without SkipBuild."
        }
        $buildIdentity = Get-Content -LiteralPath $buildIdentityPath -Raw | ConvertFrom-Json
        if ($buildIdentity.schemaVersion -ne "phase7-build-identity-v1" -or
                [string]$buildIdentity.appCommit -notmatch '^[0-9a-f]{40}$') {
            throw "Phase 7 build identity is invalid: $buildIdentityPath"
        }
        if ([bool]$buildIdentity.x86ProcessValidation -ne [bool]$X86ProcessValidation) {
            throw "The selected APK used a different x86 process-validation gate."
        }
        $recordedAppApk = @($buildIdentity.appApks) | Where-Object {
            $_.fileName -eq $appApk.Name
        } | Select-Object -First 1
        if ($null -eq $recordedAppApk -or
                $recordedAppApk.sha256 -ne $appApkSha256) {
            throw "The selected app APK does not match the Phase 7 build identity."
        }
        if ($buildIdentity.testApk.fileName -ne $testApk.Name -or
                $buildIdentity.testApk.sha256 -ne $testApkSha256) {
            throw "The AndroidTest APK does not match the Phase 7 build identity."
        }
        $appCommit = [string]$buildIdentity.appCommit
    } else {
        $appApkRecords = @(
            Get-ChildItem "app\build\outputs\apk\github\debug" -Filter "*.apk" |
                Sort-Object Name |
                ForEach-Object {
                    [ordered]@{
                        fileName = $_.Name
                        bytes = [int64]$_.Length
                        sha256 = Get-Sha256 $_.FullName
                    }
                }
        )
        $buildIdentity = [ordered]@{
            schemaVersion = "phase7-build-identity-v1"
            appCommit = $sourceCommitAtInvocation
            x86ProcessValidation = [bool]$X86ProcessValidation
            appApks = $appApkRecords
            testApk = [ordered]@{
                fileName = $testApk.Name
                bytes = [int64]$testApk.Length
                sha256 = $testApkSha256
            }
        }
        $buildIdentity | ConvertTo-Json -Depth 5 |
            Set-Content -LiteralPath $buildIdentityPath -Encoding utf8
        $appCommit = $sourceCommitAtInvocation
    }
    $litertSha256 = ""
    $runtimeAsset = Get-ChildItem "app\build\intermediates\merged_native_libs\githubDebug\out\lib\$ProcessAbi\libLiteRt.so" -ErrorAction SilentlyContinue
    if ($null -ne $runtimeAsset) { $litertSha256 = Get-Sha256 $runtimeAsset.FullName }

    if ($SkipInstall) {
        Assert-InstalledApk -PackageName $package -ExpectedSha256 $appApkSha256
        Assert-InstalledApk -PackageName ($package + ".test") -ExpectedSha256 $testApkSha256
    } else {
        Invoke-Adb install -r -t $appApk.FullName
        Invoke-Adb install -r -t $testApk.FullName
    }
    if (-not $KeepAppData) { Invoke-Adb shell pm clear $package }
    & $adb -s $Serial shell pm grant $package android.permission.READ_EXTERNAL_STORAGE 2>$null | Out-Null
    & $adb -s $Serial shell pm grant $package android.permission.WRITE_EXTERNAL_STORAGE 2>$null | Out-Null
    & $adb -s $Serial shell pm grant $package android.permission.READ_MEDIA_AUDIO 2>$null | Out-Null
    $appDataRoot = if ($Stage -in $sourceStages) {
        (& $adb -s $Serial shell run-as $package pwd).Trim()
    } else {
        ""
    }
    if ($Stage -in $sourceStages -and
            ($LASTEXITCODE -ne 0 -or $appDataRoot -notmatch '^/data/')) {
        throw "Could not resolve the app-private Phase 7 staging directory."
    }

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
        "-e", "profileId", $profileId,
        "-e", "backendMode", $BackendMode,
        "-e", "executionHostMode", $ExecutionHostMode,
        "-e", "autoFailpoint", $AutoFailpoint,
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
        "-e", "maximumCancellationLatencyMs",
        [string]$thresholds.lifecycle.maximumCancellationLatencyMs,
        "-e", "fixturesVersion", $fixtures.schemaVersion,
        "-e", "cleanInstallScenario", $((-not $KeepAppData).ToString().ToLowerInvariant()),
        "-e", "x86ProcessValidation", $X86ProcessValidation.ToString().ToLowerInvariant()
    )
    if ($null -ne $secondaryModel) {
        $instrumentArguments += @(
            "-e", "secondaryModelId", $SecondaryModelId,
            "-e", "secondaryArtifactSha256", $secondaryModel.Artifact.tflite.sha256,
            "-e", "secondaryContractId", $secondaryModel.Entry.contractId,
            "-e", "secondaryContractSchemaVersion", [string]$secondaryModel.Contract.contractSchemaVersion
        )
    }
    if ($Stage -in $sourceStages) {
        $sourceLeaf = Split-Path -Leaf $sourcePath
        if ($sourceLeaf -notmatch '^[A-Za-z0-9._-]+$') {
            throw "Source fixture filename contains unsupported characters: $sourceLeaf"
        }
        $sourceTempPath = "/data/local/tmp/booming-ss-phase7-$RunId-$sourceLeaf"
        $remoteSourceRelativePath = "files/phase7-validation-inputs/$RunId-$sourceLeaf"
        $remoteSourcePath = "$appDataRoot/$remoteSourceRelativePath"
        try {
            Invoke-Adb push $sourcePath $sourceTempPath
            Invoke-Adb shell chmod 644 $sourceTempPath
            Invoke-Adb shell run-as $package mkdir -p files/phase7-validation-inputs
            Invoke-Adb shell run-as $package cp $sourceTempPath $remoteSourceRelativePath
        } finally {
            & $adb -s $Serial shell rm -f $sourceTempPath 2>$null | Out-Null
        }
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
        $expectedDecode = $fixture.expectedDecode
        $expectedDecodeOverride = @($fixture.expectedDecodeOverrides) |
            Where-Object { $_.processAbi -eq $ProcessAbi } |
            Select-Object -First 1
        if ($null -ne $expectedDecodeOverride) {
            $expectedDecode = $expectedDecodeOverride.expectedDecode
        }
        if (-not $WindowDecode -and $null -ne $expectedDecode) {
            $expectedDecode = [pscustomobject]@{
                mode = "FullSong"
                profile = $null
                mimeType = [string]$expectedDecode.mimeType
                fallbackReason = "Window decoding is disabled in source separation settings."
            }
        }
        if ($null -ne $expectedDecode) {
            $expectedProfile = if ($null -eq $expectedDecode.profile) {
                "__none__"
            } else {
                [Convert]::ToBase64String(
                    [Text.Encoding]::UTF8.GetBytes([string]$expectedDecode.profile)
                )
            }
            $expectedFallbackReason = if ($null -eq $expectedDecode.fallbackReason) {
                "__none__"
            } else {
                [Convert]::ToBase64String(
                    [Text.Encoding]::UTF8.GetBytes([string]$expectedDecode.fallbackReason)
                )
            }
            $instrumentArguments += @(
                "-e", "fixtureExpectedDecodeMode", [string]$expectedDecode.mode,
                "-e", "fixtureExpectedDecodeProfileBase64", $expectedProfile,
                "-e", "fixtureExpectedDecodeMime", [string]$expectedDecode.mimeType,
                "-e", "fixtureExpectedFallbackReasonBase64", $expectedFallbackReason
            )
        }
        if ($null -ne $fixture.expectedOutputSampleRate) {
            $instrumentArguments += @(
                "-e", "fixtureExpectedOutputSampleRate",
                [string]$fixture.expectedOutputSampleRate
            )
        }
        if ($null -ne $fixture.expectedOutputFrameCount) {
            $instrumentArguments += @(
                "-e", "fixtureExpectedOutputFrames",
                [string]$fixture.expectedOutputFrameCount
            )
        }
        if ($Stage -in @("prefetch", "process-matrix", "process-cache-matrix", "process-cache-race-matrix")) {
            $currentSourceLeaf = Split-Path -Leaf $currentSourcePath
            if ($currentSourceLeaf -notmatch '^[A-Za-z0-9._-]+$') {
                throw "Current source fixture filename contains unsupported characters: $currentSourceLeaf"
            }
            $currentSourceTempPath = "/data/local/tmp/booming-ss-phase7-$RunId-current-$currentSourceLeaf"
            $remoteCurrentSourceRelativePath =
                "files/phase7-validation-inputs/$RunId-current-$currentSourceLeaf"
            $remoteCurrentSourcePath = "$appDataRoot/$remoteCurrentSourceRelativePath"
            try {
                Invoke-Adb push $currentSourcePath $currentSourceTempPath
                Invoke-Adb shell chmod 644 $currentSourceTempPath
                Invoke-Adb shell run-as $package cp $currentSourceTempPath `
                    $remoteCurrentSourceRelativePath
            } finally {
                & $adb -s $Serial shell rm -f $currentSourceTempPath 2>$null | Out-Null
            }
            $instrumentArguments += @(
                "-e", "currentSourcePath", $remoteCurrentSourcePath,
                "-e", "currentFixtureId", $currentFixture.fixtureId,
                "-e", "currentFixtureFileName", $currentFixture.fileName,
                "-e", "currentFixtureBytes", [string]$currentFixture.byteSize,
                "-e", "currentFixtureSha256", $currentFixture.sha256,
                "-e", "currentFixtureDurationUs", [string]$currentFixture.durationUs,
                "-e", "currentFixtureSampleRate", [string]$currentFixture.sampleRate,
                "-e", "currentFixtureChannels", [string]$currentFixture.channels,
                "-e", "currentFixtureCodec", $currentFixture.codec,
                "-e", "currentFixtureDecodeClass", $currentFixture.decodeClass
            )
        }
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
        if ($Stage -in @("worker", "switching") -and $PreserveMediaStoreSource) {
            $instrumentArguments += @(
                "-e", "preserveMediaStoreSource",
                "true"
            )
        }
        if ($Stage -eq "worker" -and $ExportCacheAudio) {
            $instrumentArguments += @("-e", "exportCacheAudio", "true")
        }
        if ($Stage -eq "process-cache-matrix") {
            $instrumentArguments += @(
                "-e", "processCacheScope", $ProcessCacheScope
            )
        }
        if ($Stage -eq "worker" -and $RebindAfterCompletion) {
            $instrumentArguments += @("-e", "rebindAfterCompletion", "true")
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

        if ($Stage -eq "background") {
            $instrumentArguments += @(
                "-e", "screenOffAfterReady",
                $ScreenOffAfterReady.ToString().ToLowerInvariant()
            )
        }
        if ($Stage -eq "background") {
            $originalScreenOffTimeout = (
            & $adb -s $Serial shell settings get system screen_off_timeout
        ).Trim()
        if ($LASTEXITCODE -ne 0 -or $originalScreenOffTimeout -notmatch '^\d+$') {
            throw "Could not read the device screen-off timeout."
        }
        Invoke-Adb shell settings put system screen_off_timeout $backgroundScreenTimeoutMs
        $screenTimeoutChanged = $true
        Invoke-Adb shell input keyevent KEYCODE_WAKEUP
        Invoke-Adb shell wm dismiss-keyguard
    }

    $instrumentAttempt = 0
    do {
        $instrumentAttempt += 1
        Invoke-Adb shell am force-stop --user $deviceUserId $package
        $instrumentOutput = & $adb -s $Serial shell am instrument --user $deviceUserId `
            -w -r @instrumentArguments $runner
        $instrumentExit = $LASTEXITCODE
        $instrumentText = $instrumentOutput -join "`n"
        if ($instrumentText -notmatch 'Invalid userId' -or $instrumentAttempt -ge 2) {
            break
        }
        Start-Sleep -Milliseconds 750
    } while ($true)
    Write-Host $instrumentText
    $remoteReport = "files/phase7-validation-reports/$RunId-$reportStage.json"
    $reportText = Read-RemoteFile $remoteReport
    if ($reportText.TrimStart() -notmatch '^\{') {
        throw "Phase 7 instrumentation did not create a JSON report for $RunId."
    }

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
        secondaryModel = if ($null -ne $secondaryModel) {
            [ordered]@{
                modelId = $SecondaryModelId
                releaseTag = $secondaryModel.Artifact.tflite.releaseAsset.tag
                fileName = $secondaryModel.Artifact.tflite.fileName
                sha256 = $secondaryModel.Artifact.tflite.sha256
                contractId = $secondaryModel.Entry.contractId
                contractSchemaVersion = $secondaryModel.Contract.contractSchemaVersion
            }
        } else { $null }
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
                installSkipped = $SkipInstall
                backendMode = $BackendMode
                executionHostMode = $ExecutionHostMode
                screenOffAfterReady = [bool]$ScreenOffAfterReady
                backend = $backendName
                autoFailpoint = $AutoFailpoint
                screenTimeoutOverrideMs = if ($Stage -eq "background") {
                    $backgroundScreenTimeoutMs
                } else { $null }
                lifecycleScenario = if ($Stage -eq "lifecycle") { $LifecycleScenario } else { $null }
                lifecycleSessionMode = if ($Stage -eq "lifecycle") { $LifecycleSessionMode } else { $null }
                currentFixture = if ($null -ne $currentFixture) {
                    [ordered]@{
                        fixtureId = $currentFixture.fixtureId
                        fileName = $currentFixture.fileName
                        byteSize = [int64]$currentFixture.byteSize
                        sha256 = $currentFixture.sha256
                        durationUs = [int64]$currentFixture.durationUs
                        sampleRate = [int]$currentFixture.sampleRate
                        channels = [int]$currentFixture.channels
                        codec = $currentFixture.codec
                        decodeClass = $currentFixture.decodeClass
                    }
                } else { $null }
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
    if ($screenTimeoutChanged) {
        & $adb -s $Serial shell settings put system screen_off_timeout `
            $originalScreenOffTimeout 2>$null | Out-Null
    }
    if (-not [string]::IsNullOrWhiteSpace($remoteSourceRelativePath)) {
        & $adb -s $Serial shell run-as $package rm -f -- `
            $remoteSourceRelativePath 2>$null | Out-Null
    }
    if (-not [string]::IsNullOrWhiteSpace($remoteCurrentSourceRelativePath)) {
        & $adb -s $Serial shell run-as $package rm -f -- `
            $remoteCurrentSourceRelativePath 2>$null | Out-Null
    }
    if ($remoteArtifactDirectory -eq $expectedRemoteArtifactDirectory) {
        & $adb -s $Serial shell run-as $package rm -rf -- `
            $remoteArtifactDirectory 2>$null | Out-Null
    }
    Pop-Location
}
