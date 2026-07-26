param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,

    [ValidateSet("arm64-v8a")]
    [string]$ProcessAbi = "arm64-v8a",

    [string]$ModelId = "uvr_mdxnet_3_9662",
    [string]$FixtureId = "coast_town_short_wav",
    [string]$SourceRepository = "",
    [string]$OutputRoot = "",
    [string]$RunPrefix = "phase5-gpu-faults-v1",
    [int]$FailureInvocationCount = 4,
    [string[]]$Failpoints = @(
        "setup",
        "invocation",
        "output-read",
        "non-finite",
        "cleanup"
    ),
    [switch]$SkipBuild,
    [switch]$SkipAcquisition
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$runner = Join-Path $PSScriptRoot "run_phase7_validation.ps1"
$fixturesPath = Join-Path $repoRoot "docs\validation\litert-phase7\fixtures-v2.json"
$validFailpoints = @("setup", "invocation", "output-read", "non-finite", "cleanup")

if ($FailureInvocationCount -lt 4) {
    throw "FailureInvocationCount must leave two successful windows after the GPU probe."
}
if ($Failpoints.Count -eq 0 -or @($Failpoints | Where-Object {
    $_ -notin $validFailpoints
}).Count -gt 0) {
    throw "Failpoints must be selected from: $($validFailpoints -join ', ')."
}
if (@($Failpoints | Select-Object -Unique).Count -ne $Failpoints.Count) {
    throw "Failpoints must not contain duplicates."
}

if ([string]::IsNullOrWhiteSpace($SourceRepository)) {
    $SourceRepository = Join-Path $repoRoot "..\..\MusicSourceSeparation"
}
$sourceRoot = (Resolve-Path -LiteralPath $SourceRepository).Path
if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $OutputRoot = Join-Path $repoRoot "build\phase5-gpu-faults"
}
New-Item -ItemType Directory -Force -Path $OutputRoot | Out-Null
$OutputRoot = (Resolve-Path -LiteralPath $OutputRoot).Path

$fixtures = Get-Content -LiteralPath $fixturesPath -Raw | ConvertFrom-Json
$fixture = @($fixtures.fixtures) |
    Where-Object fixtureId -eq $FixtureId |
    Select-Object -First 1
if ($null -eq $fixture) { throw "Unknown Phase 5 GPU fault fixture: $FixtureId" }
$sourcePath = Join-Path $sourceRoot ($fixture.relativeSourcePath -replace '/', '\')
if (-not (Test-Path -LiteralPath $sourcePath -PathType Leaf)) {
    throw "Phase 5 GPU fault fixture is missing: $sourcePath"
}

$safeSerial = $Serial -replace '[^A-Za-z0-9._-]', '_'
$deviceDirectory = Join-Path $OutputRoot $safeSerial
New-Item -ItemType Directory -Force -Path $deviceDirectory | Out-Null
$records = [System.Collections.Generic.List[object]]::new()
$failures = [System.Collections.Generic.List[string]]::new()
$buildNeeded = -not $SkipBuild
$installedByMatrix = $false

function Invoke-Validation([hashtable]$Parameters) {
    & $runner @Parameters
    if ($LASTEXITCODE -ne 0) {
        throw "Phase 7 runner exited with code $LASTEXITCODE."
    }
}

function Require-Equal($Expected, $Actual, [string]$Label) {
    if ([string]$Expected -cne [string]$Actual) {
        throw "$Label mismatch: expected '$Expected', actual '$Actual'."
    }
}

try {
    if (-not $SkipAcquisition) {
        $acquisition = @{
            Serial = $Serial
            ProcessAbi = $ProcessAbi
            ModelId = $ModelId
            Stage = "acquisition"
            BackendMode = "auto"
            RunId = "$RunPrefix-$safeSerial-acquisition"
            OutputRoot = $OutputRoot
        }
        if (-not $buildNeeded) { $acquisition.SkipBuild = $true }
        Invoke-Validation $acquisition
        $buildNeeded = $false
        $installedByMatrix = $true
    }

    foreach ($failpoint in $Failpoints) {
        $runId = "$RunPrefix-$safeSerial-$failpoint"
        $parameters = @{
            Serial = $Serial
            ProcessAbi = $ProcessAbi
            ModelId = $ModelId
            Stage = "worker"
            BackendMode = "auto"
            ExecutionHostMode = "bound-remote"
            RemoteAutoFailpoint = $failpoint
            AutoFailInvocationCount = $FailureInvocationCount
            RemoteFaultToken = "$safeSerial-$failpoint"
            FixtureId = $FixtureId
            SourcePath = $sourcePath
            RunId = $runId
            OutputRoot = $OutputRoot
            KeepAppData = $true
            ForceStopBeforeRun = $true
            ProbeOriginalPlayback = $true
        }
        if (-not $buildNeeded) {
            $parameters.SkipBuild = $true
            $parameters.SkipInstall = $true
        }
        try {
            Invoke-Validation $parameters
            $buildNeeded = $false
            $installedByMatrix = $true
            $reportPath = Join-Path $deviceDirectory "$runId-worker.json"
            if (-not (Test-Path -LiteralPath $reportPath -PathType Leaf)) {
                throw "Worker report was not created: $reportPath"
            }
            $report = Get-Content -LiteralPath $reportPath -Raw | ConvertFrom-Json
            Require-Equal "passed" $report.status "$runId status"
            Require-Equal $failpoint $report.remoteAutoFaultInjection.failpoint `
                "$runId failpoint"
            if (-not [bool]$report.diagnosticOnly) {
                throw "$runId was not marked diagnostic-only."
            }
            if ([int]$report.originalPlayback.unexpectedEventCount -ne 0 -or
                    [int64]$report.originalPlayback.maximumPositionDriftMs -gt 1000L) {
                throw "$runId interrupted original playback."
            }
            $expectedStage = switch ($failpoint) {
                "setup" { "GpuSetup" }
                "invocation" { "GpuInvocation" }
                "output-read" { "GpuOutputRead" }
                "non-finite" { "GpuOutputValidation" }
                "cleanup" { "GpuCleanup" }
            }
            Require-Equal $expectedStage $report.run.fallbackStage "$runId fallback stage"
            if ($failpoint -eq "cleanup") {
                Require-Equal "terminal-before-cpu" $report.run.backendUsed `
                    "$runId terminal backend"
                Require-Equal "Poisoned" $report.executionHost.failedSessionState `
                    "$runId poisoned session"
                if ([int]$report.remoteAutoFaultInjection.cpuCreateCount -ne 0 -or
                        -not [bool]$report.executionHost.expectedBinderDeath -or
                        [string]$report.executionHost.oldProcessGeneration -ceq
                        [string]$report.executionHost.newProcessGeneration) {
                    throw "$runId did not recycle before CPU allocation."
                }
            } else {
                Require-Equal "LiteRtCpu" $report.run.backendUsed "$runId CPU fallback"
                if (-not [bool]$report.cache.completedPlayable -or
                        [int]$report.remoteAutoFaultInjection.cpuCreateCount -ne 1) {
                    throw "$runId did not produce one playable CPU fallback."
                }
            }
            $relativeReportPath = $reportPath.Substring($OutputRoot.Length).TrimStart('\', '/').Replace('\', '/')
            $reportSha256 = ((Get-FileHash -LiteralPath $reportPath -Algorithm SHA256).Hash).ToLowerInvariant()
            $records.Add([ordered]@{
                runId = $runId
                failpoint = $failpoint
                reportFile = $relativeReportPath
                reportSha256 = $reportSha256
                appCommit = [string]$report.identity.appCommit
                appApkSha256 = [string]$report.identity.appApkSha256
                testApkSha256 = [string]$report.identity.testApkSha256
                artifactSha256 = [string]$report.identity.artifactSha256
                fallbackStage = [string]$report.run.fallbackStage
                backendUsed = [string]$report.run.backendUsed
                firstReadyMs = [int64]$report.timing.firstReadyMs
                fullSongMs = [int64]$report.timing.fullSongMs
                maximumPlaybackDriftMs =
                    [int64]$report.originalPlayback.maximumPositionDriftMs
                gpuInvocationCount =
                    [int]$report.remoteAutoFaultInjection.gpuInvocationCount
                cpuCreateCount = [int]$report.remoteAutoFaultInjection.cpuCreateCount
                processRecycled = $failpoint -eq "cleanup"
            })
        } catch {
            $failures.Add("${runId}: $($_.Exception.Message)")
            throw
        } finally {
            $buildNeeded = $false
        }
    }

    $reference = $records[0]
    foreach ($record in $records) {
        foreach ($field in @("appCommit", "appApkSha256", "testApkSha256", "artifactSha256")) {
            Require-Equal $reference[$field] $record[$field] "Matrix identity $field"
        }
    }
} finally {
    $summary = [ordered]@{
        schemaVersion = "phase5-gpu-fault-matrix-v1"
        status = if ($failures.Count -eq 0 -and
            $records.Count -eq $Failpoints.Count) { "passed" } else { "failed" }
        generatedAtUtc = [DateTime]::UtcNow.ToString("o")
        serial = $Serial
        processAbi = $ProcessAbi
        modelId = $ModelId
        fixtureId = $FixtureId
        failureInvocationCount = $FailureInvocationCount
        acquisitionSkipped = [bool]$SkipAcquisition
        installedByMatrix = $installedByMatrix
        failures = @($failures)
        runs = @($records)
    }
    $summaryPath = Join-Path $deviceDirectory "$RunPrefix-$safeSerial-summary.json"
    $summary | ConvertTo-Json -Depth 20 |
        Set-Content -LiteralPath $summaryPath -Encoding utf8
    Write-Host "Saved Phase 5 GPU fault summary to $summaryPath"
}

if ($failures.Count -gt 0 -or $records.Count -ne $Failpoints.Count) {
    throw "Phase 5 GPU fault matrix failed."
}
Write-Host "Phase 5 GPU fault matrix passed with $($records.Count) cases."
