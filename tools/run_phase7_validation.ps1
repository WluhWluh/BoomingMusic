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
        "ownership-handoff",
        "pause-cleanup",
        "cancel-cleanup",
        "task-removal",
        "playback-owner-stop",
        "force-stop",
        "reattachment",
        "process-matrix",
        "process-switch-matrix",
        "process-fault-matrix",
        "process-cache-matrix",
        "process-cache-race-matrix",
        "process-main-death",
        "independent-main-death",
        "independent-remote-death",
        "lifecycle",
        "recreation",
        "playback",
        "backend-switching",
        "switching",
        "cache-management",
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
    [string]$RunnerRevision = "phase7-runner-v62",
    [ValidateSet("cpu", "auto")]
    [string]$BackendMode = "cpu",
    [ValidateSet(
        "",
        "gpu-opencl-bounded-fp32-v1",
        "gpu-auto-fp32-v1",
        "gpu-opencl-fp32-v1",
        "gpu-opencl-low-fp32-v1",
        "gpu-opengl-fp32-v1"
    )]
    [string]$GpuRuntimeProfileId = "",
    [ValidateSet("in-process", "bound-remote", "independent-foreground")]
    [string]$ExecutionHostMode = "in-process",
    [ValidateSet("none", "setup", "probe", "invocation-after-ready")]
    [string]$AutoFailpoint = "none",

    [ValidateSet("none", "setup", "probe", "invocation", "output-read", "non-finite", "cleanup")]
    [string]$RemoteAutoFailpoint = "none",

    [int]$AutoFailInvocationCount = 6,

    [string]$RemoteFaultToken = "",
    [int]$ProcessorCount = 0,
    [int]$XnnPackFlags = -1,
    [ValidateSet("cold-session", "warm-session")]
    [string]$RunClass = "cold-session",
    [ValidateSet("pause-resume", "seek", "cancellation", "sequential")]
    [string]$LifecycleScenario = "sequential",
    [ValidateSet("all", "death", "cache-clear", "representative")]
    [string]$ProcessCacheScope = "all",
    [ValidateSet("single-use", "shared-reusable")]
    [string]$LifecycleSessionMode = "single-use",
    [ValidateSet("true", "false")]
    [string]$ModelSwitchAutoStart = "false",
    [ValidateSet("true", "false")]
    [string]$ModelSwitchPlaying = "false",
    [ValidateSet("ready", "preparation")]
    [string]$ModelSwitchBoundary = "ready",
    [bool]$WindowDecode = $true,
    [switch]$SkipBuild,
    [switch]$SkipInstall,
    [switch]$KeepAppData,
    [switch]$CleanInstallScenario,
    [switch]$PreserveMediaStoreSource,
    [switch]$ExportCacheAudio,
    [switch]$CleanupCacheAfterRun,
    [switch]$KaraGpuRequalification,
    [switch]$RebindAfterCompletion,
    [switch]$ProbeOriginalPlayback,
    [switch]$ForceStopBeforeRun,
    [ValidateSet("true", "false")]
    [string]$StopWhenClosedFromRecents = "false",
    [ValidateSet("playback-demand", "next-song-prefetch")]
    [string]$PlaybackOwnedRunClass = "playback-demand",
    [ValidateSet(
        "segment-running",
        "after-first-committed-segment",
        "terminal-commit"
    )]
    [string]$MainDeathBoundary = "segment-running",
    [ValidateSet(
        "resume",
        "clear-cache",
        "switch-model",
        "artifact-mismatch",
        "model-loss",
        "latched-fallback"
    )]
    [string]$RemoteDeathRecoveryAction = "resume",
    [ValidateRange(5, 300)]
    [int]$SilentObservationSeconds = 30,
    [ValidateRange(0, 120)]
    [int]$PlaybackSoakMinutes = 0,
    [ValidateRange(0, 1000)]
    [int]$PlaybackSoakSeekCount = 0,
    [switch]$ScreenOffAfterReady,
    [switch]$X86ProcessValidation,
    [switch]$Arm32ResidentProcessValidation
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$package = "com.wluhwluh.booming.sourcesep.debug"
$runner = "$package.test/androidx.test.runner.AndroidJUnitRunner"
$sourceStages = @(
    "worker",
    "ownership-handoff",
    "pause-cleanup",
    "cancel-cleanup",
    "task-removal",
    "playback-owner-stop",
    "force-stop",
    "reattachment",
    "process-matrix",
    "process-switch-matrix",
    "process-fault-matrix",
    "process-cache-matrix",
    "process-cache-race-matrix",
    "process-main-death",
    "independent-main-death",
    "independent-remote-death",
    "lifecycle",
    "recreation",
    "playback",
    "backend-switching",
    "switching",
    "cache-management",
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
    "ownership-handoff" { "validateProductOwnershipHandoff"; break }
    "pause-cleanup" { "validateProductPauseCleanup"; break }
    "cancel-cleanup" { "validateProductCancelCleanup"; break }
    "task-removal" { "validateTaskRemovalLifecycle"; break }
    "playback-owner-stop" { "validatePlaybackServiceStopPausesOwnedWork"; break }
    "force-stop" { "validateDeviceEvidenceIdentity"; break }
    "reattachment" { "validateIndependentRunReattachment"; break }
    "process-matrix" { "validateProcessSessionMatrix"; break }
    "process-switch-matrix" { "validateProcessModelSwitchMatrix"; break }
    "process-fault-matrix" { "validateProcessFaultMatrix"; break }
    "process-cache-matrix" { "validateProcessCacheSafetyMatrix"; break }
    "process-cache-race-matrix" { "validateProcessCacheManagementRaces"; break }
    "process-main-death" { "validateProcessMainDeathRecovery"; break }
    "independent-main-death" { "validateIndependentMainDeathReattachment"; break }
    "independent-remote-death" { "validateDeviceEvidenceIdentity"; break }
    "lifecycle" { "validateWorkerLifecycle"; break }
    "recreation" { "validateCompletedCacheAfterProcessRestart"; break }
    "playback" { "validateMediaSessionPlayback"; break }
    "backend-switching" { "validateBackendPolicyPreparationRecycle"; break }
    "switching" { "validateActiveModelSwitch"; break }
    "cache-management" { "validateProductCacheManagement"; break }
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
if (-not [string]::IsNullOrWhiteSpace($GpuRuntimeProfileId) -and
        ($BackendMode -ne "auto" -or $Stage -ne "worker" -or
        $ExecutionHostMode -ne "in-process" -or $AutoFailpoint -ne "none" -or
        $RemoteAutoFailpoint -ne "none")) {
    throw "GpuRuntimeProfileId requires an in-process Auto worker without fault injection."
}
if ($AutoFailpoint -ne "none" -and ($BackendMode -ne "auto" -or $Stage -ne "worker")) {
    throw "AutoFailpoint requires BackendMode=auto and Stage=worker."
}
if ($RemoteAutoFailpoint -ne "none" -and
        ($BackendMode -ne "auto" -or $Stage -ne "worker" -or
        $ExecutionHostMode -ne "bound-remote" -or $AutoFailpoint -ne "none")) {
    throw "RemoteAutoFailpoint requires BoundRemote Auto worker execution without AutoFailpoint."
}
if ($AutoFailInvocationCount -lt 2) {
    throw "AutoFailInvocationCount must preserve the first finite-output probe."
}
if ([string]::IsNullOrWhiteSpace($GpuRuntimeProfileId) -and
        $BackendMode -eq "auto" -and $Stage -eq "worker" -and
        $ExecutionHostMode -eq "in-process" -and $AutoFailpoint -eq "none" -and
        $RemoteAutoFailpoint -eq "none") {
    $GpuRuntimeProfileId = "gpu-opencl-bounded-fp32-v1"
}
if ($KaraGpuRequalification -and
        ($Stage -ne "worker" -or
        $ModelId -ne "uvr_mdxnet_kara" -or
        $ProcessAbi -ne "arm64-v8a" -or
        $BackendMode -ne "auto" -or
        $GpuRuntimeProfileId -ne "gpu-opencl-bounded-fp32-v1" -or
        $ExecutionHostMode -ne "in-process" -or
        $AutoFailpoint -ne "none" -or
        $RemoteAutoFailpoint -ne "none")) {
    throw "KaraGpuRequalification requires the in-process arm64 KARA worker with bounded FP32 GPU and no fault injection."
}
$boundRemoteBackendSupported = $BackendMode -eq "auto" -or
    ($Stage -eq "worker" -and $BackendMode -eq "cpu" -and -not $X86ProcessValidation)
if ($ExecutionHostMode -eq "bound-remote" -and
        ($Stage -notin @("worker", "background", "process-matrix", "process-switch-matrix", "process-fault-matrix", "process-cache-matrix", "process-cache-race-matrix", "process-main-death") -or
        -not $boundRemoteBackendSupported -or $AutoFailpoint -ne "none")) {
    throw "BoundRemote requires a supported process stage/backend and AutoFailpoint=none."
}
if ($ExecutionHostMode -eq "independent-foreground" -and
        ($Stage -notin @("worker", "ownership-handoff", "pause-cleanup", "cancel-cleanup", "task-removal", "force-stop", "reattachment", "independent-main-death", "independent-remote-death", "switching", "cache-management", "prefetch") -or
        $ProcessAbi -ne "arm64-v8a" -or
        $ProcessorCount -gt 0 -or $XnnPackFlags -ge 0 -or
        $AutoFailpoint -ne "none" -or
        $RemoteAutoFailpoint -ne "none" -or $RebindAfterCompletion)) {
    throw "IndependentForeground requires an arm64 CPU or Auto worker with default runtime settings and no fault injection or rebind."
}
if ($Stage -eq "ownership-handoff" -and
        ($ExecutionHostMode -ne "independent-foreground" -or
        $ProcessAbi -ne "arm64-v8a" -or
        $AutoFailpoint -ne "none" -or $RemoteAutoFailpoint -ne "none")) {
    throw "ownership-handoff requires the production arm64 independent foreground route without fault injection."
}
if ($Stage -in @("pause-cleanup", "cancel-cleanup") -and
        ($ExecutionHostMode -ne "independent-foreground" -or
        $ProcessAbi -ne "arm64-v8a" -or
        $AutoFailpoint -ne "none" -or $RemoteAutoFailpoint -ne "none")) {
    throw "$Stage requires the production arm64 independent foreground route without fault injection."
}
if ($Stage -eq "task-removal" -and
        ($ExecutionHostMode -ne "independent-foreground" -or
        $ProcessAbi -ne "arm64-v8a" -or
        $AutoFailpoint -ne "none" -or $RemoteAutoFailpoint -ne "none")) {
    throw "task-removal requires the production arm64 independent foreground route without fault injection."
}
if ($Stage -eq "playback-owner-stop" -and
        ($ExecutionHostMode -ne "in-process" -or
        $ProcessAbi -ne "arm64-v8a" -or
        $AutoFailpoint -ne "none" -or $RemoteAutoFailpoint -ne "none")) {
    throw "playback-owner-stop requires the production arm64 in-process playback route without fault injection."
}
if ($Stage -eq "force-stop" -and
        ($ExecutionHostMode -ne "independent-foreground" -or
        $ProcessAbi -ne "arm64-v8a" -or
        $AutoFailpoint -ne "none" -or $RemoteAutoFailpoint -ne "none")) {
    throw "force-stop requires the production arm64 independent foreground route without fault injection."
}
if ($Stage -ne "independent-main-death" -and
        $MainDeathBoundary -ne "segment-running") {
    throw "MainDeathBoundary applies only to independent-main-death."
}
if ($Stage -ne "independent-remote-death" -and
        $RemoteDeathRecoveryAction -ne "resume") {
    throw "RemoteDeathRecoveryAction applies only to independent-remote-death."
}
if ($Stage -eq "independent-remote-death" -and
        ($ExecutionHostMode -ne "independent-foreground" -or
        $ProcessAbi -ne "arm64-v8a" -or
        $AutoFailpoint -ne "none" -or $RemoteAutoFailpoint -ne "none")) {
    throw "independent-remote-death requires the production arm64 independent foreground route without runtime fault injection."
}
if ($Stage -eq "independent-remote-death" -and
        ($ModelId -ne "uvr_mdxnet_3_9662" -or
        $FixtureId -ne "coast_town_full_mp3")) {
    throw "independent-remote-death is pinned to the 9662 coast_town MP3 full-song fixture."
}
if ($RemoteDeathRecoveryAction -in @("artifact-mismatch", "latched-fallback") -and
        $BackendMode -ne "auto") {
    throw "$RemoteDeathRecoveryAction recovery requires BackendMode=auto."
}
if ($Stage -in @("process-matrix", "process-switch-matrix")) {
    $validX86Resident = $ProcessAbi -eq "x86" -and $X86ProcessValidation
    $validArm32Resident = $ProcessAbi -eq "armeabi-v7a" -and
        $Arm32ResidentProcessValidation
    if ((-not $validX86Resident -and -not $validArm32Resident) -or
            $ExecutionHostMode -ne "bound-remote" -or $BackendMode -ne "auto") {
        throw "$Stage requires its resident ABI validation gate and BoundRemote Auto."
    }
}
if ($Stage -in @("process-fault-matrix", "process-cache-race-matrix", "process-main-death") -and
        (-not $X86ProcessValidation -or $ProcessAbi -ne "x86" -or
        $ExecutionHostMode -ne "bound-remote" -or $BackendMode -ne "auto")) {
    throw "$Stage requires pure x86, X86ProcessValidation, and BoundRemote Auto."
}
if ($Stage -eq "process-cache-matrix") {
    $validX86 = $ProcessAbi -eq "x86" -and $X86ProcessValidation
    $validArm64 = $ProcessAbi -eq "arm64-v8a" -and -not $X86ProcessValidation
    if ((-not $validX86 -and -not $validArm64) -or
            $ExecutionHostMode -ne "bound-remote" -or $BackendMode -ne "auto") {
        throw "process-cache-matrix requires x86 validation or regular arm64, and BoundRemote Auto."
    }
}
if ($X86ProcessValidation -and $ProcessAbi -ne "x86") {
    throw "X86ProcessValidation can only build and run the pure-x86 target."
}
if ($Arm32ResidentProcessValidation -and $ProcessAbi -ne "armeabi-v7a") {
    throw "Arm32ResidentProcessValidation can only build and run armeabi-v7a."
}
if ($X86ProcessValidation -and $Arm32ResidentProcessValidation) {
    throw "Only one resident process-validation gate can be enabled."
}
if ($X86ProcessValidation -and $Stage -in $sourceStages -and
        $ExecutionHostMode -ne "bound-remote") {
    throw "X86ProcessValidation execution stages require BoundRemote."
}
if ($Arm32ResidentProcessValidation -and $Stage -in $sourceStages -and
        $Stage -notin @("process-matrix", "process-switch-matrix")) {
    throw "Arm32ResidentProcessValidation is limited to resident process matrices."
}
if ($PreserveMediaStoreSource -and $Stage -notin @("worker", "switching")) {
    throw "PreserveMediaStoreSource applies only to worker and switching stages."
}
if ($ScreenOffAfterReady -and
        ($Stage -notin @("background", "worker") -or
        ($Stage -eq "worker" -and
            $ExecutionHostMode -ne "independent-foreground"))) {
    throw "ScreenOffAfterReady requires background stage or the independent foreground worker."
}
if ($RebindAfterCompletion -and
        ($Stage -ne "worker" -or $ExecutionHostMode -ne "bound-remote")) {
    throw "RebindAfterCompletion requires Stage=worker and ExecutionHostMode=bound-remote."
}
if ($ProbeOriginalPlayback -and $Stage -ne "worker") {
    throw "ProbeOriginalPlayback applies only to the worker stage."
}
if ($ForceStopBeforeRun -and ($Stage -notin $sourceStages -or -not $KeepAppData)) {
    throw "ForceStopBeforeRun requires an execution stage with KeepAppData."
}
if ($Stage -ne "lifecycle" -and
        ($LifecycleScenario -ne "sequential" -or $LifecycleSessionMode -ne "single-use")) {
    throw "LifecycleScenario and LifecycleSessionMode apply only to the lifecycle stage."
}
if ($Stage -ne "switching" -and
        ($ModelSwitchAutoStart -ne "false" -or $ModelSwitchPlaying -ne "false" -or
        $ModelSwitchBoundary -ne "ready")) {
    throw "Model-switch controls apply only to the switching stage."
}
if ($BackendMode -eq "auto" -and $Stage -eq "lifecycle" -and
        $LifecycleSessionMode -ne "single-use") {
    throw "BackendMode=auto uses the production single-use session provider; shared-reusable is CPU-only."
}
if ($Stage -eq "playback" -and $CacheKey -notmatch '^[0-9a-f]{64}$') {
    throw "Playback stage requires a 64-character lowercase cache key."
}
if ($Stage -ne "playback" -and
        ($PlaybackSoakMinutes -ne 0 -or $PlaybackSoakSeekCount -ne 0)) {
    throw "PlaybackSoakMinutes and PlaybackSoakSeekCount apply only to playback."
}
if (($PlaybackSoakMinutes -eq 0) -ne ($PlaybackSoakSeekCount -eq 0)) {
    throw "PlaybackSoakMinutes and PlaybackSoakSeekCount must both be zero or positive."
}
$requiresSecondaryModel = $Stage -in @(
    "switching",
    "cache-management",
    "process-switch-matrix",
    "process-cache-race-matrix"
) -or ($Stage -eq "independent-remote-death" -and
    $RemoteDeathRecoveryAction -eq "switch-model")
if ($requiresSecondaryModel -and
        [string]::IsNullOrWhiteSpace($SecondaryModelId)) {
    throw "$Stage/$RemoteDeathRecoveryAction requires SecondaryModelId."
}
if (-not [string]::IsNullOrWhiteSpace($SecondaryModelId) -and
        $SecondaryModelId -eq $ModelId) {
    throw "SecondaryModelId must differ from ModelId."
}
if ($Stage -eq "background" -and $BackendMode -ne "auto") {
    throw "background uses the production service graph and requires BackendMode=auto."
}
if ($CleanupCacheAfterRun -and $Stage -ne "worker") {
    throw "CleanupCacheAfterRun applies only to the worker stage."
}
$requiresCurrentFixture = $Stage -in @(
    "prefetch",
    "process-matrix",
    "process-cache-matrix",
    "process-cache-race-matrix"
) -or ($Stage -eq "playback-owner-stop" -and
    $PlaybackOwnedRunClass -eq "next-song-prefetch")
if ($requiresCurrentFixture -and
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

function Get-AppProcessIds {
    $ids = [System.Collections.Generic.HashSet[int]]::new()
    foreach ($processName in @($package, "${package}:source_separation", "$package.test")) {
        $output = & $adb -s $Serial shell pidof $processName 2>$null
        if ($LASTEXITCODE -ne 0) { continue }
        foreach ($token in (($output -join " ") -split '\s+')) {
            $value = 0
            if ([int]::TryParse($token, [ref]$value) -and $value -gt 0) {
                [void]$ids.Add($value)
            }
        }
    }
    return @($ids | Sort-Object)
}

function Stop-AppProcesses {
    $before = @(Get-AppProcessIds)
    $started = [Diagnostics.Stopwatch]::StartNew()
    Invoke-Adb shell am force-stop --user $deviceUserId $package
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    do {
        $remaining = @(Get-AppProcessIds)
        if ($remaining.Count -eq 0) { break }
        Start-Sleep -Milliseconds 50
    } while ([DateTime]::UtcNow -lt $deadline)
    $started.Stop()
    if ($remaining.Count -ne 0) {
        throw "App processes did not exit after force-stop: $($remaining -join ',')."
    }
    return [ordered]@{
        requested = $true
        priorPids = $before
        exitElapsedMs = [int64]$started.ElapsedMilliseconds
        allExited = $true
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

function Try-ReadRemoteFile([string]$RelativePath) {
    $result = & $adb -s $Serial exec-out run-as $package cat $RelativePath 2>$null
    if ($LASTEXITCODE -ne 0) { return $null }
    return ($result -join "`n")
}

function Wait-RemoteJsonFile(
    [string]$RelativePath,
    [int]$TimeoutSeconds,
    [string]$FailurePath = ""
) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $text = Try-ReadRemoteFile $RelativePath
        if (-not [string]::IsNullOrWhiteSpace($text) -and
                $text.TrimStart().StartsWith("{", [System.StringComparison]::Ordinal)) {
            return $text
        }
        if (-not [string]::IsNullOrWhiteSpace($FailurePath)) {
            $failure = Try-ReadRemoteFile $FailurePath
            if (-not [string]::IsNullOrWhiteSpace($failure) -and
                    $failure.TrimStart().StartsWith("{", [System.StringComparison]::Ordinal)) {
                $failureObject = $failure | ConvertFrom-Json
                if ([string]$failureObject.status -eq "failed") {
                    throw "Debug process-death command failed: $failure"
                }
            }
        }
        Start-Sleep -Milliseconds 100
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for remote JSON file: $RelativePath"
}

function Get-NamedProcessIds([string]$ProcessName) {
    $output = & $adb -s $Serial shell pidof $ProcessName 2>$null
    if ($LASTEXITCODE -ne 0) { return @() }
    return @(
        (($output -join " ") -split '\s+') |
            Where-Object { $_ -match '^\d+$' } |
            ForEach-Object { [int]$_ }
    )
}

function Get-RemoteFileSha256([string]$Path) {
    $output = ((& $adb -s $Serial shell run-as $package sha256sum $Path 2>$null) -join " ").Trim()
    if ($LASTEXITCODE -ne 0 -or $output -notmatch '^([0-9a-fA-F]{64})\s+') {
        throw "Could not hash remote file: $Path"
    }
    return $Matches[1].ToLowerInvariant()
}

function Get-RemoteJournalSnapshot([string]$Path) {
    $text = Read-RemoteFile $Path
    $journal = $text | ConvertFrom-Json
    $transitions = @($journal.transitions)
    if ($transitions.Count -eq 0) {
        throw "Remote journal has no transitions: $Path"
    }
    return [ordered]@{
        sha256 = Get-RemoteFileSha256 $Path
        sequence = [int64]$transitions[-1].sequence
        lifecycle = [string]$journal.lifecycle
        runId = [string]$journal.request.runId
        processGeneration = [int64]$journal.request.processGeneration
    }
}

function Get-RemoteEntrySnapshot(
    [string]$Path,
    [string[]]$ExcludedRelativePaths = @()
) {
    $fileOutput = & $adb -s $Serial shell run-as $package find $Path -type f -print 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw "Could not enumerate remote cache entry: $Path"
    }
    $records = @(
        $fileOutput |
            ForEach-Object { ([string]$_).Trim() } |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
            Sort-Object |
            ForEach-Object {
                $filePath = $_
                if (-not $filePath.StartsWith("$Path/", [System.StringComparison]::Ordinal) -or
                        $filePath.Contains("..", [System.StringComparison]::Ordinal)) {
                    throw "Remote cache entry contains an unsafe file path: $filePath"
                }
                $relativePath = $filePath.Substring($Path.Length + 1)
                if ($relativePath -notin $ExcludedRelativePaths) {
                    $sizeText = ((& $adb -s $Serial shell run-as $package stat -c '%s' $filePath `
                        2>$null) -join "").Trim()
                    if ($LASTEXITCODE -ne 0 -or $sizeText -notmatch '^\d+$') {
                        throw "Could not measure remote cache file: $filePath"
                    }
                    [ordered]@{
                        path = $relativePath
                        bytes = [int64]$sizeText
                        sha256 = Get-RemoteFileSha256 $filePath
                    }
                }
            }
    )
    if ($records.Count -eq 0) {
        throw "Remote cache entry contains no files: $Path"
    }
    $canonical = ($records | ForEach-Object {
        "$($_.path)`t$($_.bytes)`t$($_.sha256)"
    }) -join "`n"
    $digestBytes = [System.Security.Cryptography.SHA256]::HashData(
        [System.Text.Encoding]::UTF8.GetBytes($canonical)
    )
    return [ordered]@{
        sha256 = [Convert]::ToHexString($digestBytes).ToLowerInvariant()
        fileCount = $records.Count
        totalBytes = [int64](
            ($records | ForEach-Object { [int64]$_.bytes } | Measure-Object -Sum).Sum
        )
    }
}

function Get-PackageStoppedState {
    $packageOutput = $null
    $packageExitCode = 1
    foreach ($attempt in 1..3) {
        $packageOutput = & $adb -s $Serial shell dumpsys package $package 2>$null
        $packageExitCode = $LASTEXITCODE
        if ($packageExitCode -eq 0) { break }
        Start-Sleep -Milliseconds 250
    }
    if ($packageExitCode -ne 0) {
        throw "Could not inspect the package stopped state."
    }
    $pattern = "^\s*User ${deviceUserId}: .*\bstopped=(true|false)\b"
    $line = $packageOutput | Where-Object { [string]$_ -match $pattern } | Select-Object -First 1
    if ($null -eq $line -or [string]$line -notmatch $pattern) {
        throw "Package diagnostics do not expose the current user's stopped state."
    }
    return $Matches[1] -eq "true"
}

function Get-TaskLifecycleObservation {
    $serviceOutput = & $adb -s $Serial shell dumpsys activity services $package 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw "Could not inspect app services."
    }
    $notificationOutput = & $adb -s $Serial shell cmd notification list 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw "Could not inspect active notifications."
    }
    $powerOutput = & $adb -s $Serial shell dumpsys power 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw "Could not inspect active wake locks."
    }
    $powerText = $powerOutput -join "`n"
    $wakeLockStart = $powerText.IndexOf("Wake Locks: size=", [System.StringComparison]::Ordinal)
    $wakeLockEnd = $powerText.IndexOf(
        "Suspend Blockers: size=",
        [Math]::Max(0, $wakeLockStart),
        [System.StringComparison]::Ordinal
    )
    if ($wakeLockStart -lt 0 -or $wakeLockEnd -le $wakeLockStart) {
        throw "Power diagnostics do not contain the active wake-lock section."
    }
    $activeWakeLocks = $powerText.Substring($wakeLockStart, $wakeLockEnd - $wakeLockStart)
    $notificationPattern = "^[^|]*\|$([Regex]::Escape($package))\|21331\|"
    return [ordered]@{
        processIds = @(Get-AppProcessIds)
        processingService = (($serviceOutput -join "`n").Contains(
            "SourceSeparationExecutionService",
            [System.StringComparison]::Ordinal
        ))
        processingNotification = @(
            $notificationOutput | Where-Object { [string]$_ -match $notificationPattern }
        ).Count -gt 0
        inferenceWakeLock = $activeWakeLocks.Contains(
            "${package}:SourceSeparationInference",
            [System.StringComparison]::Ordinal
        )
    }
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
        $element = @($metadata.elements) | Where-Object {
            @($_.filters).Count -eq 0
        } | Select-Object -First 1
    }
    if ($null -eq $element) {
        throw "No $Abi app split or universal APK was declared in $metadataPath."
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
$currentFixture = if ($requiresCurrentFixture) {
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
if ($requiresCurrentFixture) {
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
$preRunProcessBoundary = [ordered]@{
    requested = $false
    priorPids = @()
    exitElapsedMs = 0L
    allExited = $true
}

if ([string]::IsNullOrWhiteSpace($OutputRoot)) { throw "OutputRoot must not be empty." }
New-Item -ItemType Directory -Force -Path $OutputRoot | Out-Null

Push-Location $repoRoot
try {
    if (-not $SkipBuild) {
        $validationGradleArgument = @()
        if ($X86ProcessValidation) {
            $validationGradleArgument += "-PboomingSs.x86ProcessValidation=true"
        }
        if ($Arm32ResidentProcessValidation) {
            $validationGradleArgument +=
                "-PboomingSs.arm32ResidentProcessValidation=true"
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
        if ([bool]$buildIdentity.arm32ResidentProcessValidation -ne
                [bool]$Arm32ResidentProcessValidation) {
            throw "The selected APK used a different arm32 resident-validation gate."
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
            arm32ResidentProcessValidation = [bool]$Arm32ResidentProcessValidation
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
    if ($ForceStopBeforeRun) {
        $preRunProcessBoundary = Stop-AppProcesses
    }
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
        "-e", "remoteAutoFailpoint", $RemoteAutoFailpoint,
        "-e", "autoFailInvocationCount", [string]$AutoFailInvocationCount,
        "-e", "remoteFaultToken", $(if ([string]::IsNullOrWhiteSpace($RemoteFaultToken)) {
            "$RunId-remote-gpu"
        } else { $RemoteFaultToken }),
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
        "-e", "x86ProcessValidation", $X86ProcessValidation.ToString().ToLowerInvariant(),
        "-e", "arm32ResidentProcessValidation",
        $Arm32ResidentProcessValidation.ToString().ToLowerInvariant(),
        "-e", "probeOriginalPlayback", $ProbeOriginalPlayback.ToString().ToLowerInvariant(),
        "-e", "karaGpuRequalification", $KaraGpuRequalification.ToString().ToLowerInvariant(),
        "-e", "coldProcessBoundary", $ForceStopBeforeRun.ToString().ToLowerInvariant(),
        "-e", "preRunProcessExitMs", [string]$preRunProcessBoundary.exitElapsedMs
    )
    if (-not [string]::IsNullOrWhiteSpace($GpuRuntimeProfileId)) {
        $instrumentArguments += @(
            "-e", "gpuRuntimeProfileId", $GpuRuntimeProfileId
        )
    }
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
        if ($requiresCurrentFixture) {
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
            $instrumentArguments += @(
                "-e", "cacheKey", $CacheKey,
                "-e", "playbackSoakMinutes", [string]$PlaybackSoakMinutes,
                "-e", "playbackSoakSeekCount", [string]$PlaybackSoakSeekCount
            )
        }
        if ($Stage -eq "lifecycle") {
            $instrumentArguments += @(
                "-e", "lifecycleScenario", $LifecycleScenario,
                "-e", "lifecycleSessionMode", $LifecycleSessionMode
            )
        }
        if ($Stage -eq "switching") {
            $instrumentArguments += @(
                "-e", "modelSwitchAutoStart", $ModelSwitchAutoStart,
                "-e", "modelSwitchPlaying", $ModelSwitchPlaying,
                "-e", "modelSwitchBoundary", $ModelSwitchBoundary
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
        if ($Stage -eq "worker" -and $CleanupCacheAfterRun) {
            $instrumentArguments += @("-e", "cleanupCacheAfterRun", "true")
        }
        if ($Stage -eq "process-cache-matrix") {
            $instrumentArguments += @(
                "-e", "processCacheScope", $ProcessCacheScope
            )
        }
        if ($Stage -eq "task-removal") {
            $instrumentArguments += @(
                "-e", "stopWhenClosedFromRecents",
                $StopWhenClosedFromRecents
            )
        }
        if ($Stage -eq "playback-owner-stop") {
            $instrumentArguments += @(
                "-e", "playbackOwnedRunClass",
                $PlaybackOwnedRunClass
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

        if ($Stage -eq "background" -or
                ($Stage -eq "worker" -and $ScreenOffAfterReady)) {
            $instrumentArguments += @(
                "-e", "screenOffAfterReady",
                $ScreenOffAfterReady.ToString().ToLowerInvariant()
            )
        }
        if ($Stage -eq "background" -or
                ($Stage -eq "worker" -and $ScreenOffAfterReady)) {
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

    if ($Stage -in @(
            "process-matrix",
            "process-switch-matrix",
            "process-cache-matrix",
            "process-cache-race-matrix"
        )) {
        Invoke-Adb shell input keyevent KEYCODE_WAKEUP
        Invoke-Adb shell wm dismiss-keyguard
    }

    if ($Stage -eq "force-stop") {
        Invoke-Adb shell am force-stop --user $deviceUserId $package
        $debugDirectory = "files/phase7-debug-main-death"
        $scenarioRelativePath = "$debugDirectory/$RunId-scenario.json"
        $debugReportRelativePath = "$debugDirectory/$RunId-report.json"
        & $adb -s $Serial shell run-as $package rm -f -- `
            $scenarioRelativePath $debugReportRelativePath 2>$null | Out-Null

        Invoke-Adb shell am start -W --user $deviceUserId -n `
            "$package/com.mardous.booming.activities.MainActivity"
        $debugReceiver =
            "$package/com.mardous.booming.debug.SourceSeparationDebugReceiver"
        $debugAction = "com.mardous.booming.debug.SOURCE_SEPARATION"
        $debugArguments = @(
            "shell", "am", "broadcast", "--user", $deviceUserId,
            "-a", $debugAction,
            "-n", $debugReceiver,
            "--es", "runId", $RunId,
            "--es", "sourcePath", $remoteSourcePath,
            "--es", "modelId", $ModelId,
            "--es", "artifactSha256", $artifact.sha256,
            "--es", "backendMode", $BackendMode,
            "--es", "killBoundary", $MainDeathBoundary
        )
        Invoke-Adb @debugArguments --es command beginIndependentForceStop
        $scenarioText = Wait-RemoteJsonFile `
            -RelativePath $scenarioRelativePath `
            -TimeoutSeconds 300 `
            -FailurePath $debugReportRelativePath
        $scenario = $scenarioText | ConvertFrom-Json
        $oldMainPid = [int]$scenario.mainPid
        $oldRemotePid = [int]$scenario.remotePid
        $cacheRootPath = [string]$scenario.cacheRootPath
        $journalPath = [string]$scenario.journalPath
        if ($oldMainPid -le 0 -or $oldRemotePid -le 0 -or
                $oldMainPid -eq $oldRemotePid) {
            throw "The force-stop scenario contains invalid process identities."
        }
        $allowedInternalCacheRoot = "$appDataRoot/cache"
        $allowedExternalCacheRoot =
            "/storage/emulated/$deviceUserId/Android/data/$package/cache"
        $cacheRootAllowed = $cacheRootPath.Equals(
            $allowedInternalCacheRoot,
            [System.StringComparison]::Ordinal
        ) -or $cacheRootPath.StartsWith(
            "$allowedInternalCacheRoot/",
            [System.StringComparison]::Ordinal
        ) -or $cacheRootPath.Equals(
            $allowedExternalCacheRoot,
            [System.StringComparison]::Ordinal
        ) -or $cacheRootPath.StartsWith(
            "$allowedExternalCacheRoot/",
            [System.StringComparison]::Ordinal
        )
        $expectedJournalPath =
            "$cacheRootPath/entries/$($scenario.cacheKey)/run-journal.json"
        if (-not $cacheRootAllowed -or
                -not $journalPath.Equals(
                    $expectedJournalPath,
                    [System.StringComparison]::Ordinal
                ) -or
                $journalPath.Contains("..", [System.StringComparison]::Ordinal)) {
            throw "The force-stop scenario contains an unsafe journal path."
        }
        $beforeStop = Get-TaskLifecycleObservation
        if ($oldMainPid -notin @($beforeStop.processIds) -or
                $oldRemotePid -notin @($beforeStop.processIds)) {
            throw "The force-stop scenario lost an authoritative process before force-stop."
        }
        if (-not $beforeStop.processingService -or
                -not $beforeStop.processingNotification -or
                -not $beforeStop.inferenceWakeLock) {
            throw "The force-stop scenario was not protected by its service, notification, and wake lock."
        }

        $forceStopBoundary = Stop-AppProcesses
        $stoppedAfterStop = Get-PackageStoppedState
        $afterStopJournal = Get-RemoteJournalSnapshot $journalPath
        $journalSeparator = $journalPath.LastIndexOf('/')
        if ($journalSeparator -le 0) {
            throw "The force-stop journal has no device-side parent path."
        }
        $entryPath = $journalPath.Substring(0, $journalSeparator)
        $afterStopEntry = Get-RemoteEntrySnapshot `
            -Path $entryPath `
            -ExcludedRelativePaths @("run-journal.json")
        $afterStop = Get-TaskLifecycleObservation
        $silentStarted = [Diagnostics.Stopwatch]::StartNew()
        $silentProcessSampleCount = 0
        $unexpectedRelaunchCount = 0
        do {
            $silentProcessSampleCount += 1
            if (@(Get-AppProcessIds).Count -ne 0) {
                $unexpectedRelaunchCount += 1
            }
            Start-Sleep -Milliseconds 250
        } while ($silentStarted.Elapsed.TotalSeconds -lt $SilentObservationSeconds)
        $silentStarted.Stop()
        $afterSilenceJournal = Get-RemoteJournalSnapshot $journalPath
        $afterSilenceEntry = Get-RemoteEntrySnapshot `
            -Path $entryPath `
            -ExcludedRelativePaths @("run-journal.json")
        $afterSilence = Get-TaskLifecycleObservation
        $stoppedAfterSilence = Get-PackageStoppedState

        Invoke-Adb shell am start -W --user $deviceUserId -n `
            "$package/com.mardous.booming.activities.MainActivity"
        Start-Sleep -Seconds 5
        $afterRestartJournal = Get-RemoteJournalSnapshot $journalPath
        $afterRestartEntry = Get-RemoteEntrySnapshot `
            -Path $entryPath `
            -ExcludedRelativePaths @("run-journal.json")
        $afterRestart = Get-TaskLifecycleObservation

        $validationArguments = @($debugArguments) + @(
            "--es", "command", "validateIndependentForceStop",
            "--es", "journalSha256AfterStop", $afterStopJournal.sha256,
            "--es", "journalSha256AfterSilence", $afterSilenceJournal.sha256,
            "--es", "journalSha256AfterRestart", $afterRestartJournal.sha256,
            "--es", "entrySha256AfterStop", $afterStopEntry.sha256,
            "--es", "entrySha256AfterSilence", $afterSilenceEntry.sha256,
            "--es", "entrySha256AfterRestart", $afterRestartEntry.sha256,
            "--el", "journalSequenceAfterStop", [string]$afterStopJournal.sequence,
            "--el", "journalSequenceAfterSilence", [string]$afterSilenceJournal.sequence,
            "--el", "journalSequenceAfterRestart", [string]$afterRestartJournal.sequence,
            "--el", "forceStopExitElapsedMs", [string]$forceStopBoundary.exitElapsedMs,
            "--el", "silentObservationMs", [string]$silentStarted.ElapsedMilliseconds,
            "--el", "silentProcessSampleCount", [string]$silentProcessSampleCount,
            "--el", "unexpectedRelaunchCount", [string]$unexpectedRelaunchCount,
            "--el", "entryFileCountAfterStop", [string]$afterStopEntry.fileCount,
            "--el", "entryFileCountAfterSilence", [string]$afterSilenceEntry.fileCount,
            "--el", "entryFileCountAfterRestart", [string]$afterRestartEntry.fileCount,
            "--el", "entryBytesAfterStop", [string]$afterStopEntry.totalBytes,
            "--el", "entryBytesAfterSilence", [string]$afterSilenceEntry.totalBytes,
            "--el", "entryBytesAfterRestart", [string]$afterRestartEntry.totalBytes,
            "--ez", "packageStoppedAfterStop",
            ($stoppedAfterStop.ToString().ToLowerInvariant()),
            "--ez", "packageStoppedAfterSilence",
            ($stoppedAfterSilence.ToString().ToLowerInvariant()),
            "--ez", "allProcessesExitedAfterStop",
            (($forceStopBoundary.allExited -and @($afterStop.processIds).Count -eq 0).ToString().ToLowerInvariant()),
            "--ez", "allProcessesExitedAfterSilence",
            ((@($afterSilence.processIds).Count -eq 0).ToString().ToLowerInvariant()),
            "--ez", "notificationAfterStop",
            ($afterStop.processingNotification.ToString().ToLowerInvariant()),
            "--ez", "notificationAfterSilence",
            ($afterSilence.processingNotification.ToString().ToLowerInvariant()),
            "--ez", "notificationAfterRestart",
            ($afterRestart.processingNotification.ToString().ToLowerInvariant()),
            "--ez", "processingServiceAfterStop",
            ($afterStop.processingService.ToString().ToLowerInvariant()),
            "--ez", "processingServiceAfterSilence",
            ($afterSilence.processingService.ToString().ToLowerInvariant()),
            "--ez", "processingServiceAfterRestart",
            ($afterRestart.processingService.ToString().ToLowerInvariant()),
            "--ez", "wakeLockAfterStop",
            ($afterStop.inferenceWakeLock.ToString().ToLowerInvariant()),
            "--ez", "wakeLockAfterSilence",
            ($afterSilence.inferenceWakeLock.ToString().ToLowerInvariant()),
            "--ez", "wakeLockAfterRestart",
            ($afterRestart.inferenceWakeLock.ToString().ToLowerInvariant())
        )
        Invoke-Adb @validationArguments
        $reportText = Wait-RemoteJsonFile `
            -RelativePath $debugReportRelativePath `
            -TimeoutSeconds 120
        $debugReport = $reportText | ConvertFrom-Json
        $instrumentExit = if ([string]$debugReport.status -eq "passed") { 0 } else { 1 }
        $instrumentText = if ($instrumentExit -eq 0) {
            "OK (1 test)"
        } else {
            "Debug force-stop validation failed: $reportText"
        }
    } elseif ($Stage -eq "independent-remote-death") {
        Invoke-Adb shell am force-stop --user $deviceUserId $package
        Invoke-Adb shell run-as $package rm -rf -- `
            cache/source-separation `
            "/storage/emulated/$deviceUserId/Android/data/$package/cache/source-separation"
        $debugDirectory = "files/phase7-debug-main-death"
        $scenarioRelativePath = "$debugDirectory/$RunId-scenario.json"
        $debugReportRelativePath = "$debugDirectory/$RunId-report.json"
        & $adb -s $Serial shell run-as $package rm -f -- `
            $scenarioRelativePath $debugReportRelativePath 2>$null | Out-Null

        Invoke-Adb shell am start -W --user $deviceUserId -n `
            "$package/com.mardous.booming.activities.MainActivity"
        $debugReceiver =
            "$package/com.mardous.booming.debug.SourceSeparationDebugReceiver"
        $debugAction = "com.mardous.booming.debug.SOURCE_SEPARATION"
        $debugArguments = @(
            "shell", "am", "broadcast", "--user", $deviceUserId,
            "-a", $debugAction,
            "-n", $debugReceiver,
            "--es", "runId", $RunId,
            "--es", "sourcePath", $remoteSourcePath,
            "--es", "modelId", $ModelId,
            "--es", "artifactSha256", $artifact.sha256,
            "--es", "backendMode", $BackendMode,
            "--es", "killBoundary", "after-first-committed-segment",
            "--es", "remoteDeathRecoveryAction", $RemoteDeathRecoveryAction
        )
        if ($null -ne $secondaryModel) {
            $debugArguments += @(
                "--es", "secondaryModelId", $SecondaryModelId,
                "--es", "secondaryArtifactSha256",
                $secondaryModel.Artifact.tflite.sha256
            )
        }
        Invoke-Adb @debugArguments --es command beginIndependentRemoteDeath
        $scenarioText = Wait-RemoteJsonFile `
            -RelativePath $scenarioRelativePath `
            -TimeoutSeconds 300 `
            -FailurePath $debugReportRelativePath
        $scenario = $scenarioText | ConvertFrom-Json
        $oldMainPid = [int]$scenario.mainPid
        $oldRemotePid = [int]$scenario.remotePid
        $cacheRootPath = [string]$scenario.cacheRootPath
        $journalPath = [string]$scenario.journalPath
        if ($oldMainPid -le 0 -or $oldRemotePid -le 0 -or
                $oldMainPid -eq $oldRemotePid -or
                [int]$scenario.faultHitPid -ne $oldRemotePid -or
                [int]$scenario.committedSegments -le 0) {
            throw "The independent remote-death scenario contains invalid frozen evidence."
        }
        $allowedInternalCacheRoot = "$appDataRoot/cache"
        $allowedExternalCacheRoot =
            "/storage/emulated/$deviceUserId/Android/data/$package/cache"
        $cacheRootAllowed = $cacheRootPath.Equals(
            $allowedInternalCacheRoot,
            [System.StringComparison]::Ordinal
        ) -or $cacheRootPath.StartsWith(
            "$allowedInternalCacheRoot/",
            [System.StringComparison]::Ordinal
        ) -or $cacheRootPath.Equals(
            $allowedExternalCacheRoot,
            [System.StringComparison]::Ordinal
        ) -or $cacheRootPath.StartsWith(
            "$allowedExternalCacheRoot/",
            [System.StringComparison]::Ordinal
        )
        $expectedJournalPath =
            "$cacheRootPath/entries/$($scenario.cacheKey)/run-journal.json"
        if (-not $cacheRootAllowed -or
                -not $journalPath.Equals(
                    $expectedJournalPath,
                    [System.StringComparison]::Ordinal
                ) -or
                $journalPath.Contains("..", [System.StringComparison]::Ordinal)) {
            throw "The independent remote-death scenario contains an unsafe journal path."
        }
        $journalSeparator = $journalPath.LastIndexOf('/')
        if ($journalSeparator -le 0) {
            throw "The independent remote-death journal has no device-side parent path."
        }
        $entryPath = $journalPath.Substring(0, $journalSeparator)
        $beforeKillJournal = Get-RemoteJournalSnapshot $journalPath
        $beforeKillEntry = Get-RemoteEntrySnapshot $entryPath
        $beforeKill = Get-TaskLifecycleObservation
        $packageStoppedBeforeKill = Get-PackageStoppedState
        $mainPidsBeforeKill = @(Get-NamedProcessIds $package)
        $remotePidsBeforeKill = @(Get-NamedProcessIds "${package}:source_separation")
        if ($mainPidsBeforeKill.Count -ne 1 -or
                $mainPidsBeforeKill[0] -ne $oldMainPid -or
                $remotePidsBeforeKill.Count -ne 1 -or
                $remotePidsBeforeKill[0] -ne $oldRemotePid) {
            throw "The independent remote-death scenario lost its authoritative processes before kill."
        }
        if (-not $beforeKill.processingService -or
                -not $beforeKill.processingNotification -or
                -not $beforeKill.inferenceWakeLock) {
            throw "The remote-death run was not protected by its service, notification, and wake lock."
        }
        if ($packageStoppedBeforeKill) {
            throw "The remote-death run unexpectedly had a stopped package state before kill."
        }

        $deathRequester = "adb-run-as-kill-9"
        & $adb -s $Serial shell run-as $package kill -9 $oldRemotePid `
            2>$null | Out-Null
        $runAsKillExitCode = $LASTEXITCODE
        if ($runAsKillExitCode -ne 0) {
            $remotePidsBeforeFallback = @(
                Get-NamedProcessIds "${package}:source_separation"
            )
            if ($oldRemotePid -notin $remotePidsBeforeFallback) {
                throw "The inference process disappeared before a successful external death request."
            }
            Invoke-Adb shell am crash --user $deviceUserId $oldRemotePid
            $deathRequester = "adb-am-crash-pid"
        }
        $deathStarted = [Diagnostics.Stopwatch]::StartNew()
        $deathDeadline = [DateTime]::UtcNow.AddSeconds(30)
        $unexpectedRemotePids = [System.Collections.Generic.HashSet[int]]::new()
        $activeRemoteRelaunchPids = [System.Collections.Generic.HashSet[int]]::new()
        $activeRemoteRelaunchPresenceSampleCount = 0
        $remoteProcessSampleCount = 0
        $mainProcessSampleCount = 0
        $mainDisappearanceCount = 0
        do {
            $mainPidsAfterKill = @(Get-NamedProcessIds $package)
            $remotePidsAfterKill = @(
                Get-NamedProcessIds "${package}:source_separation"
            )
            $mainProcessSampleCount += 1
            $remoteProcessSampleCount += 1
            if ($mainPidsAfterKill.Count -ne 1 -or
                    $mainPidsAfterKill[0] -ne $oldMainPid) {
                $mainDisappearanceCount += 1
            }
            foreach ($remotePidSample in $remotePidsAfterKill) {
                if ($remotePidSample -ne $oldRemotePid) {
                    [void]$unexpectedRemotePids.Add($remotePidSample)
                }
            }
            if ($mainDisappearanceCount -gt 0) {
                throw "The main process did not survive inference-process death."
            }
            if ($oldRemotePid -notin $remotePidsAfterKill) { break }
            Start-Sleep -Milliseconds 50
        } while ([DateTime]::UtcNow -lt $deathDeadline)
        if ($oldRemotePid -in $remotePidsAfterKill) {
            throw "The killed inference process did not exit within 30 seconds."
        }
        $deathStarted.Stop()
        $killExitElapsedMs = [int64]$deathStarted.ElapsedMilliseconds

        $cleanupDeadline = [DateTime]::UtcNow.AddSeconds(30)
        do {
            $afterDeath = Get-TaskLifecycleObservation
            $mainProcessSampleCount += 1
            $remoteProcessSampleCount += 1
            $cleanupMainPids = @(Get-NamedProcessIds $package)
            if ($cleanupMainPids.Count -ne 1 -or
                    $cleanupMainPids[0] -ne $oldMainPid) {
                $mainDisappearanceCount += 1
                throw "The main process changed while remote-death resources were releasing."
            }
            $cleanupRemotePids = @(
                Get-NamedProcessIds "${package}:source_separation"
            )
            foreach ($remotePidSample in $cleanupRemotePids) {
                if ($remotePidSample -ne $oldRemotePid) {
                    [void]$unexpectedRemotePids.Add($remotePidSample)
                }
            }
            if ($cleanupRemotePids | Where-Object { $_ -ne $oldRemotePid }) {
                if ($afterDeath.processingService -or
                        $afterDeath.processingNotification -or
                        $afterDeath.inferenceWakeLock) {
                    foreach ($remotePidSample in $cleanupRemotePids) {
                        if ($remotePidSample -ne $oldRemotePid) {
                            [void]$activeRemoteRelaunchPids.Add($remotePidSample)
                        }
                    }
                    $activeRemoteRelaunchPresenceSampleCount += 1
                    throw "An active inference process reappeared after remote death."
                }
            }
            if (-not $afterDeath.processingService -and
                    -not $afterDeath.processingNotification -and
                    -not $afterDeath.inferenceWakeLock) {
                break
            }
            Start-Sleep -Milliseconds 100
        } while ([DateTime]::UtcNow -lt $cleanupDeadline)
        if ($afterDeath.processingService -or
                $afterDeath.processingNotification -or
                $afterDeath.inferenceWakeLock) {
            throw "Remote-death processing resources did not release within 30 seconds: " +
                "service=$($afterDeath.processingService) " +
                "notification=$($afterDeath.processingNotification) " +
                "wakeLock=$($afterDeath.inferenceWakeLock)."
        }
        $packageStoppedAfterDeath = Get-PackageStoppedState
        if ($packageStoppedAfterDeath) {
            throw "The package entered stopped state after remote process death."
        }
        $afterDeathJournal = Get-RemoteJournalSnapshot $journalPath
        $afterDeathEntry = Get-RemoteEntrySnapshot $entryPath

        $silentStarted = [Diagnostics.Stopwatch]::StartNew()
        $silentProcessSampleCount = 0
        do {
            $silentProcessSampleCount += 1
            $currentMainPids = @(Get-NamedProcessIds $package)
            $mainProcessSampleCount += 1
            $remoteProcessSampleCount += 1
            if ($currentMainPids.Count -ne 1 -or
                    $currentMainPids[0] -ne $oldMainPid) {
                throw "The main process changed during remote-death silence."
            }
            $currentRemotePids = @(Get-NamedProcessIds "${package}:source_separation")
            foreach ($remotePidSample in $currentRemotePids) {
                if ($remotePidSample -ne $oldRemotePid) {
                    [void]$unexpectedRemotePids.Add($remotePidSample)
                }
            }
            if ($currentRemotePids | Where-Object { $_ -ne $oldRemotePid }) {
                $sample = Get-TaskLifecycleObservation
                if ($sample.processingService -or
                        $sample.processingNotification -or
                        $sample.inferenceWakeLock) {
                    foreach ($remotePidSample in $currentRemotePids) {
                        if ($remotePidSample -ne $oldRemotePid) {
                            [void]$activeRemoteRelaunchPids.Add($remotePidSample)
                        }
                    }
                    $activeRemoteRelaunchPresenceSampleCount += 1
                    throw "An active inference process reappeared during remote-death silence."
                }
            }
            Start-Sleep -Milliseconds 250
        } while ($silentStarted.Elapsed.TotalSeconds -lt $SilentObservationSeconds)
        $silentStarted.Stop()
        $afterSilenceJournal = Get-RemoteJournalSnapshot $journalPath
        $afterSilenceEntry = Get-RemoteEntrySnapshot $entryPath
        $afterSilence = Get-TaskLifecycleObservation
        $packageStoppedAfterSilence = Get-PackageStoppedState

        $validationArguments = @($debugArguments) + @(
            "--es", "command", "validateIndependentRemoteDeath",
            "--es", "journalSha256BeforeKill", $beforeKillJournal.sha256,
            "--es", "journalSha256AfterDeath", $afterDeathJournal.sha256,
            "--es", "journalSha256AfterSilence", $afterSilenceJournal.sha256,
            "--es", "entrySha256BeforeKill", $beforeKillEntry.sha256,
            "--es", "entrySha256AfterDeath", $afterDeathEntry.sha256,
            "--es", "entrySha256AfterSilence", $afterSilenceEntry.sha256,
            "--el", "journalSequenceBeforeKill", [string]$beforeKillJournal.sequence,
            "--el", "journalSequenceAfterDeath", [string]$afterDeathJournal.sequence,
            "--el", "journalSequenceAfterSilence", [string]$afterSilenceJournal.sequence,
            "--el", "silentObservationMs", [string]$silentStarted.ElapsedMilliseconds,
            "--el", "silentProcessSampleCount", [string]$silentProcessSampleCount,
            "--el", "unexpectedRemoteRelaunchCount",
            [string]$activeRemoteRelaunchPids.Count,
            "--el", "unexpectedRemotePresenceSampleCount",
            [string]$activeRemoteRelaunchPresenceSampleCount,
            "--el", "remoteProcessSampleCount", [string]$remoteProcessSampleCount,
            "--el", "mainProcessSampleCount", [string]$mainProcessSampleCount,
            "--el", "mainDisappearanceCount", [string]$mainDisappearanceCount,
            "--el", "killExitElapsedMs", [string]$killExitElapsedMs,
            "--es", "deathRequester", $deathRequester,
            "--el", "entryFileCountBeforeKill", [string]$beforeKillEntry.fileCount,
            "--el", "entryFileCountAfterDeath", [string]$afterDeathEntry.fileCount,
            "--el", "entryFileCountAfterSilence", [string]$afterSilenceEntry.fileCount,
            "--el", "entryBytesBeforeKill", [string]$beforeKillEntry.totalBytes,
            "--el", "entryBytesAfterDeath", [string]$afterDeathEntry.totalBytes,
            "--el", "entryBytesAfterSilence", [string]$afterSilenceEntry.totalBytes,
            "--ez", "mainProcessSurvived", "true",
            "--ez", "packageStoppedBeforeKill",
            ($packageStoppedBeforeKill.ToString().ToLowerInvariant()),
            "--ez", "packageStoppedAfterDeath",
            ($packageStoppedAfterDeath.ToString().ToLowerInvariant()),
            "--ez", "packageStoppedAfterSilence",
            ($packageStoppedAfterSilence.ToString().ToLowerInvariant()),
            "--ez", "processingServiceBeforeKill",
            ($beforeKill.processingService.ToString().ToLowerInvariant()),
            "--ez", "processingServiceAfterDeath",
            ($afterDeath.processingService.ToString().ToLowerInvariant()),
            "--ez", "processingServiceAfterSilence",
            ($afterSilence.processingService.ToString().ToLowerInvariant()),
            "--ez", "notificationBeforeKill",
            ($beforeKill.processingNotification.ToString().ToLowerInvariant()),
            "--ez", "notificationAfterDeath",
            ($afterDeath.processingNotification.ToString().ToLowerInvariant()),
            "--ez", "notificationAfterSilence",
            ($afterSilence.processingNotification.ToString().ToLowerInvariant()),
            "--ez", "wakeLockBeforeKill",
            ($beforeKill.inferenceWakeLock.ToString().ToLowerInvariant()),
            "--ez", "wakeLockAfterDeath",
            ($afterDeath.inferenceWakeLock.ToString().ToLowerInvariant()),
            "--ez", "wakeLockAfterSilence",
            ($afterSilence.inferenceWakeLock.ToString().ToLowerInvariant())
        )
        Invoke-Adb @validationArguments
        $reportText = Wait-RemoteJsonFile `
            -RelativePath $debugReportRelativePath `
            -TimeoutSeconds 1800
        $debugReport = $reportText | ConvertFrom-Json
        if ([string]$debugReport.status -eq "passed") {
            $debugReport | Add-Member -NotePropertyName residentRemoteProcessPids `
                -NotePropertyValue @($unexpectedRemotePids | Sort-Object) -Force
            $terminalDeadline = [DateTime]::UtcNow.AddSeconds(30)
            do {
                $terminalObservation = Get-TaskLifecycleObservation
                if (-not $terminalObservation.processingService -and
                        -not $terminalObservation.processingNotification -and
                        -not $terminalObservation.inferenceWakeLock) {
                    break
                }
                Start-Sleep -Milliseconds 100
            } while ([DateTime]::UtcNow -lt $terminalDeadline)
            $debugReport | Add-Member -NotePropertyName terminalExternalResources `
                -NotePropertyValue ([pscustomobject]@{
                    processingService = [bool]$terminalObservation.processingService
                    processingNotification = [bool]$terminalObservation.processingNotification
                    inferenceWakeLock = [bool]$terminalObservation.inferenceWakeLock
                }) -Force
            if ($terminalObservation.processingService -or
                    $terminalObservation.processingNotification -or
                    $terminalObservation.inferenceWakeLock) {
                $debugReport.status = "failed"
                $debugReport | Add-Member -NotePropertyName error `
                    -NotePropertyValue "Terminal processing resources survived explicit-resume completion." `
                    -Force
            }
            $reportText = $debugReport | ConvertTo-Json -Depth 20
            $instrumentExit = if ($terminalObservation.processingService -or
                    $terminalObservation.processingNotification -or
                    $terminalObservation.inferenceWakeLock) { 1 } else { 0 }
        } else {
            $instrumentExit = 1
        }
        $instrumentText = if ($instrumentExit -eq 0) {
            "OK (1 test)"
        } else {
            "Debug independent remote-death validation failed: $reportText"
        }
    } elseif ($Stage -eq "independent-main-death") {
        Invoke-Adb shell am force-stop --user $deviceUserId $package
        $debugDirectory = "files/phase7-debug-main-death"
        $scenarioRelativePath = "$debugDirectory/$RunId-scenario.json"
        $debugReportRelativePath = "$debugDirectory/$RunId-report.json"
        & $adb -s $Serial shell run-as $package rm -f -- `
            $scenarioRelativePath $debugReportRelativePath 2>$null | Out-Null

        Invoke-Adb shell am start -W --user $deviceUserId -n `
            "$package/com.mardous.booming.activities.MainActivity"
        $debugReceiver =
            "$package/com.mardous.booming.debug.SourceSeparationDebugReceiver"
        $debugAction = "com.mardous.booming.debug.SOURCE_SEPARATION"
        $debugArguments = @(
            "shell", "am", "broadcast", "--user", $deviceUserId,
            "-a", $debugAction,
            "-n", $debugReceiver,
            "--es", "runId", $RunId,
            "--es", "sourcePath", $remoteSourcePath,
            "--es", "modelId", $ModelId,
            "--es", "artifactSha256", $artifact.sha256,
            "--es", "backendMode", $BackendMode,
            "--es", "killBoundary", $MainDeathBoundary
        )
        Invoke-Adb @debugArguments --es command beginIndependentMainDeath
        $mainDeathSetupTimeoutSeconds = if ($MainDeathBoundary -eq "terminal-commit") {
            1800
        } else {
            300
        }
        $scenarioText = Wait-RemoteJsonFile `
            -RelativePath $scenarioRelativePath `
            -TimeoutSeconds $mainDeathSetupTimeoutSeconds `
            -FailurePath $debugReportRelativePath
        $scenario = $scenarioText | ConvertFrom-Json
        $oldMainPid = [int]$scenario.mainPid
        $remotePid = [int]$scenario.remotePid
        if ($oldMainPid -le 0 -or $remotePid -le 0 -or $oldMainPid -eq $remotePid) {
            throw "The debug process-death scenario contains invalid process identities."
        }
        if ($remotePid -notin @(Get-NamedProcessIds "${package}:source_separation")) {
            throw "The debug scenario remote PID is no longer authoritative."
        }

        $deathDeadline = [DateTime]::UtcNow.AddSeconds(30)
        do {
            $oldMainAlive = $oldMainPid -in @(Get-NamedProcessIds $package)
            $remoteAlive = $remotePid -in @(
                Get-NamedProcessIds "${package}:source_separation"
            )
            if (-not $oldMainAlive -and $remoteAlive) { break }
            Start-Sleep -Milliseconds 50
        } while ([DateTime]::UtcNow -lt $deathDeadline)
        if ($oldMainAlive) {
            throw "The debug main process survived SIGKILL."
        }
        if (-not $remoteAlive) {
            throw "The authoritative inference process died with the main process."
        }

        if ($MainDeathBoundary -eq "terminal-commit") {
            $faultToken = [string]$scenario.faultToken
            $cacheRootPath = [string]$scenario.cacheRootPath
            $allowedInternalCacheRoot = "$appDataRoot/cache/source-separation"
            $allowedExternalCacheRoot =
                "/storage/emulated/$deviceUserId/Android/data/$package/cache/source-separation"
            if ($faultToken -notmatch '^[A-Za-z0-9._-]{1,120}$' -or
                    ($cacheRootPath -ne $allowedInternalCacheRoot -and
                    $cacheRootPath -ne $allowedExternalCacheRoot)) {
                throw "The terminal main-death scenario contains an unsafe fault barrier."
            }
            $releasePath = "$cacheRootPath/phase4-fault-injection/release"
            $releaseStagingPath = "/data/local/tmp/bss-phase7-$RunId-release"
            $localReleasePath = Join-Path ([IO.Path]::GetTempPath()) (
                "bss-phase7-{0}-release-{1}" -f $RunId, [Guid]::NewGuid().ToString("N")
            )
            try {
                [IO.File]::WriteAllText(
                    $localReleasePath,
                    $faultToken,
                    [Text.UTF8Encoding]::new($false)
                )
                Invoke-Adb push $localReleasePath $releaseStagingPath
                Invoke-Adb shell run-as $package cp -- $releaseStagingPath $releasePath
                $writtenToken = (
                    & $adb -s $Serial shell run-as $package cat $releasePath
                ) -join "`n"
                if ($writtenToken -cne $faultToken) {
                    throw "The terminal main-death release token was not written exactly."
                }
            } finally {
                Remove-Item -LiteralPath $localReleasePath -Force -ErrorAction SilentlyContinue
                & $adb -s $Serial shell rm -f -- $releaseStagingPath 2>$null | Out-Null
            }
        }

        Invoke-Adb shell am start -W --user $deviceUserId -n `
            "$package/com.mardous.booming.activities.MainActivity"
        $restartDeadline = [DateTime]::UtcNow.AddSeconds(30)
        do {
            $newMainPids = @(Get-NamedProcessIds $package) |
                Where-Object { $_ -ne $oldMainPid }
            if ($newMainPids.Count -eq 1) { break }
            Start-Sleep -Milliseconds 50
        } while ([DateTime]::UtcNow -lt $restartDeadline)
        if ($newMainPids.Count -ne 1) {
            throw "The debug main process did not restart with one fresh PID."
        }
        Start-Sleep -Seconds 1
        Invoke-Adb @debugArguments --es command validateIndependentMainDeath
        $reportText = Wait-RemoteJsonFile `
            -RelativePath $debugReportRelativePath `
            -TimeoutSeconds 1800
        $debugReport = $reportText | ConvertFrom-Json
        $instrumentExit = if ([string]$debugReport.status -eq "passed") { 0 } else { 1 }
        $instrumentText = if ($instrumentExit -eq 0) {
            "OK (1 test)"
        } else {
            "Debug process-death validation failed: $reportText"
        }
    } elseif ($Stage -eq "process-main-death") {
        Invoke-Adb shell am force-stop --user $deviceUserId $package
        $prepareArguments = @($instrumentArguments)
        $classArgumentIndex = [Array]::IndexOf($prepareArguments, "class")
        if ($classArgumentIndex -lt 0 -or $classArgumentIndex + 1 -ge $prepareArguments.Count) {
            throw "Could not locate the instrumentation class argument."
        }
        $prepareArguments[$classArgumentIndex + 1] =
            "$testClass#beginProcessMainDeathScenario"
        $prepareOutput = & $adb -s $Serial shell am instrument --user $deviceUserId `
            -w -r @prepareArguments $runner
        $prepareText = $prepareOutput -join "`n"
        Write-Host $prepareText
        if ($prepareText -match 'OK \(1 test\)') {
            throw "The process-main-death setup returned without killing its main process."
        }
        $scenarioRelativePath = "files/phase4-main-death/$RunId.json"
        $scenarioText = Read-RemoteFile $scenarioRelativePath
        if ($scenarioText.TrimStart() -notmatch '^\{') {
            throw "The process main-death setup did not persist its scenario envelope."
        }
        Start-Sleep -Milliseconds 250
        $instrumentOutput = & $adb -s $Serial shell am instrument --user $deviceUserId `
            -w -r @instrumentArguments $runner
        $instrumentExit = $LASTEXITCODE
        $instrumentText = $instrumentOutput -join "`n"
    } else {
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
    }
    Write-Host $instrumentText
    if ($Stage -notin @(
            "force-stop",
            "independent-main-death",
            "independent-remote-death"
        )) {
        $remoteReport = "files/phase7-validation-reports/$RunId-$reportStage.json"
        $reportText = Read-RemoteFile $remoteReport
        if ($reportText.TrimStart() -notmatch '^\{') {
            throw "Phase 7 instrumentation did not create a JSON report for $RunId."
        }
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
                karaGpuRequalification = [bool]$KaraGpuRequalification
                probeOriginalPlayback = [bool]$ProbeOriginalPlayback
                coldProcessBoundary = [bool]$ForceStopBeforeRun
                preRunProcessBoundary = $preRunProcessBoundary
                stopWhenClosedFromRecents = if ($Stage -eq "task-removal") {
                    $StopWhenClosedFromRecents -eq "true"
                } else { $null }
                playbackOwnedRunClass = if ($Stage -eq "playback-owner-stop") {
                    $PlaybackOwnedRunClass
                } else { $null }
                mainDeathBoundary = if ($Stage -eq "independent-main-death") {
                    $MainDeathBoundary
                } else { $null }
                remoteDeathRecoveryAction = if ($Stage -eq "independent-remote-death") {
                    $RemoteDeathRecoveryAction
                } else { $null }
                silentObservationSeconds = if ($Stage -in @(
                        "force-stop",
                        "independent-remote-death"
                    )) {
                    $SilentObservationSeconds
                } else { $null }
                arm32ResidentProcessValidation =
                    [bool]$Arm32ResidentProcessValidation
                screenOffAfterReady = [bool]$ScreenOffAfterReady
                backend = $backendName
                autoFailpoint = $AutoFailpoint
                remoteAutoFailpoint = $RemoteAutoFailpoint
                autoFailInvocationCount = $AutoFailInvocationCount
                screenTimeoutOverrideMs = if ($Stage -eq "background" -or
                        ($Stage -eq "worker" -and $ScreenOffAfterReady)) {
                    $backgroundScreenTimeoutMs
                } else { $null }
                lifecycleScenario = if ($Stage -eq "lifecycle") { $LifecycleScenario } else { $null }
                lifecycleSessionMode = if ($Stage -eq "lifecycle") { $LifecycleSessionMode } else { $null }
                modelSwitchAutoStart = if ($Stage -eq "switching") {
                    $ModelSwitchAutoStart -eq "true"
                } else { $null }
                modelSwitchPlaying = if ($Stage -eq "switching") {
                    $ModelSwitchPlaying -eq "true"
                } else { $null }
                modelSwitchBoundary = if ($Stage -eq "switching") {
                    $ModelSwitchBoundary
                } else { $null }
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
    if ($ScreenOffAfterReady) {
        & $adb -s $Serial shell input keyevent KEYCODE_WAKEUP 2>$null | Out-Null
        & $adb -s $Serial shell wm dismiss-keyguard 2>$null | Out-Null
    }
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
    if ($Stage -in @(
            "force-stop",
            "independent-main-death",
            "independent-remote-death"
        )) {
        & $adb -s $Serial shell am force-stop --user $deviceUserId $package `
            2>$null | Out-Null
    }
    Pop-Location
}
