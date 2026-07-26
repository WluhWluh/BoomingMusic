param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,

    [Parameter(Mandatory = $true)]
    [ValidateSet("arm64-v8a", "armeabi-v7a", "x86_64")]
    [string]$ProcessAbi,

    [string]$ModelId = "uvr_mdxnet_3_9662",
    [string]$FixtureId = "coast_town_full_wav",
    [string]$SourceRepository = "",
    [string]$OutputRoot = "",
    [string]$RunPrefix = "phase5-host-pairs-v1",
    [ValidateSet("cpu", "auto")]
    [string]$BackendMode = "auto",
    [int]$PairCount = 3,
    [int[]]$PairsToRun = @(),
    [string]$SeedSummary = "",
    [switch]$ValidateSeedOnly,
    [int]$CooldownSeconds = 15,
    [int]$ThermalWaitTimeoutSeconds = 600,
    [switch]$SkipBuild,
    [switch]$SkipAcquisition
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$runner = Join-Path $PSScriptRoot "run_phase7_validation.ps1"
$fixturesPath = Join-Path $repoRoot "docs\validation\litert-phase7\fixtures-v2.json"
$thresholdsPath = Join-Path $repoRoot `
    "docs\validation\litert-inference-process\phase5\paired-host-thresholds-v1.json"
$package = "com.wluhwluh.booming.sourcesep.debug"
$adb = (Get-Command adb -ErrorAction Stop).Source
$requiredRemoteIdleSettleMs = 2000

if ([string]::IsNullOrWhiteSpace($SourceRepository)) {
    $SourceRepository = Join-Path $repoRoot "..\..\MusicSourceSeparation"
}
$sourceRoot = (Resolve-Path -LiteralPath $SourceRepository).Path
if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $OutputRoot = Join-Path $repoRoot "build\phase5-host-pairs"
}
New-Item -ItemType Directory -Force -Path $OutputRoot | Out-Null
$OutputRoot = (Resolve-Path -LiteralPath $OutputRoot).Path

$thresholds = Get-Content -LiteralPath $thresholdsPath -Raw | ConvertFrom-Json
if ($thresholds.schemaVersion -ne "phase5-paired-host-thresholds-v1" -or
        -not [bool]$thresholds.frozenBeforeResults) {
    throw "Phase 5 paired-host thresholds are invalid or not frozen."
}
$minimumPairCount = [int]$thresholds.measurement.minimumPairCount
if ($PairCount -lt $minimumPairCount) {
    throw "PairCount must be at least $minimumPairCount."
}
if ($CooldownSeconds -lt 0 -or $ThermalWaitTimeoutSeconds -lt 1) {
    throw "Cooldown and thermal timeout values are invalid."
}

$fixtures = Get-Content -LiteralPath $fixturesPath -Raw | ConvertFrom-Json
$fixture = @($fixtures.fixtures) |
    Where-Object { $_.fixtureId -eq $FixtureId } |
    Select-Object -First 1
if ($null -eq $fixture) { throw "Fixture is absent from fixtures-v2.json: $FixtureId" }
$sourcePath = Join-Path $sourceRoot ($fixture.relativeSourcePath -replace '/', '\')
if (-not (Test-Path -LiteralPath $sourcePath -PathType Leaf)) {
    throw "Fixture file is missing: $sourcePath"
}

$safeSerial = $Serial -replace '[^A-Za-z0-9._-]', '_'
$deviceDirectory = Join-Path $OutputRoot $safeSerial
New-Item -ItemType Directory -Force -Path $deviceDirectory | Out-Null
$runRecords = [System.Collections.Generic.List[object]]::new()
$failures = [System.Collections.Generic.List[string]]::new()
$buildNeeded = -not $SkipBuild
$selectedPairs = if ($ValidateSeedOnly) {
    @()
} elseif ($PairsToRun.Count -eq 0) {
    @(1..$PairCount)
} else {
    @($PairsToRun | Select-Object -Unique | Sort-Object)
}
if ($ValidateSeedOnly -and $PairsToRun.Count -gt 0) {
    throw "ValidateSeedOnly cannot be combined with PairsToRun."
}
if ($ValidateSeedOnly -and [string]::IsNullOrWhiteSpace($SeedSummary)) {
    throw "ValidateSeedOnly requires SeedSummary."
}
if (($PairsToRun.Count -gt 0 -and $selectedPairs.Count -ne $PairsToRun.Count) -or
        @($selectedPairs | Where-Object { $_ -lt 1 -or $_ -gt $PairCount }).Count -gt 0) {
    throw "PairsToRun contains duplicate or out-of-range pair numbers."
}
if ([string]::IsNullOrWhiteSpace($SeedSummary) -and
        $selectedPairs.Count -ne $PairCount) {
    throw "A partial paired-host run requires SeedSummary."
}
$seedSummaryReportPath = $null
if (-not [string]::IsNullOrWhiteSpace($SeedSummary)) {
    $seedPath = (Resolve-Path -LiteralPath $SeedSummary).Path
    $seedSummaryReportPath = $seedPath.Substring($OutputRoot.Length).TrimStart('\', '/').Replace('\', '/')
    $seed = Get-Content -LiteralPath $seedPath -Raw | ConvertFrom-Json
    if ($seed.schemaVersion -ne "phase5-paired-host-report-v2" -or
            $seed.modelId -ne $ModelId -or $seed.fixtureId -ne $FixtureId -or
            $seed.backendMode -ne $BackendMode -or [int]$seed.pairCount -ne $PairCount -or
            $seed.device.serial -ne $Serial -or $seed.device.processAbi -ne $ProcessAbi) {
        throw "SeedSummary does not match this paired-host matrix identity."
    }
    @($seed.runs) |
        Where-Object { [int]$_.pair -notin $selectedPairs } |
        ForEach-Object { $runRecords.Add($_) }
    if ($runRecords.Count -ne ($PairCount - $selectedPairs.Count) * 2) {
        throw "SeedSummary does not contain every retained pair exactly once."
    }
}

function Invoke-Adb {
    & $adb -s $Serial @args
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed with exit code ${LASTEXITCODE}: $args"
    }
}

function Get-DeviceProperty([string]$Name) {
    $value = & $adb -s $Serial shell getprop $Name 2>$null
    if ($LASTEXITCODE -ne 0 -or $null -eq $value) { return $null }
    $text = ([string]($value | Select-Object -First 1)).Trim()
    if ([string]::IsNullOrWhiteSpace($text)) { return $null }
    return $text
}

function Get-AppProcessIds {
    $ids = [System.Collections.Generic.HashSet[int]]::new()
    foreach ($name in @($package, "${package}:source_separation", "$package.test")) {
        $output = & $adb -s $Serial shell pidof $name 2>$null
        if ($LASTEXITCODE -ne 0) { continue }
        foreach ($token in (($output -join " ") -split '\s+')) {
            $pidValue = 0
            if ([int]::TryParse($token, [ref]$pidValue) -and $pidValue -gt 0) {
                [void]$ids.Add($pidValue)
            }
        }
    }
    return @($ids | Sort-Object)
}

function Stop-AppProcesses {
    $before = @(Get-AppProcessIds)
    $watch = [Diagnostics.Stopwatch]::StartNew()
    Invoke-Adb shell am force-stop $package
    $deadline = [DateTime]::UtcNow.AddMilliseconds(
        [int]$thresholds.resource.maximumProcessExitMs
    )
    do {
        $remaining = @(Get-AppProcessIds)
        if ($remaining.Count -eq 0) { break }
        Start-Sleep -Milliseconds 50
    } while ([DateTime]::UtcNow -lt $deadline)
    $watch.Stop()
    return [ordered]@{
        priorPids = $before
        exitElapsedMs = [int64]$watch.ElapsedMilliseconds
        allExited = $remaining.Count -eq 0
        remainingPids = $remaining
    }
}

function Get-ThermalStatus {
    $output = & $adb -s $Serial shell dumpsys thermalservice 2>$null
    if ($LASTEXITCODE -ne 0) { return $null }
    $match = [regex]::Match(($output -join "`n"), '(?m)^Thermal Status:\s+(\d+)\s*$')
    if (-not $match.Success) { return $null }
    return [int]$match.Groups[1].Value
}

function Wait-ForThermalAdmission {
    $maximum = [int]$thresholds.environment.maximumStartingThermalStatus
    $deadline = [DateTime]::UtcNow.AddSeconds($ThermalWaitTimeoutSeconds)
    do {
        $status = Get-ThermalStatus
        if ($null -eq $status -or $status -le $maximum) { return $status }
        Start-Sleep -Seconds 5
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Thermal status remained above $maximum for $ThermalWaitTimeoutSeconds seconds."
}

function Get-BatteryState {
    $text = (& $adb -s $Serial shell dumpsys battery 2>$null) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw "Unable to read battery state." }
    function Match-Value([string]$Name) {
        $match = [regex]::Match($text, "(?m)^\s*$([regex]::Escape($Name)):\s*(.+?)\s*$")
        if ($match.Success) { return $match.Groups[1].Value }
        return $null
    }
    return [ordered]@{
        acPowered = (Match-Value "AC powered") -eq "true"
        usbPowered = (Match-Value "USB powered") -eq "true"
        wirelessPowered = (Match-Value "Wireless powered") -eq "true"
        status = [int](Match-Value "status")
        level = [int](Match-Value "level")
        temperatureTenthsC = [int](Match-Value "temperature")
    }
}

function Get-GpuDriverDiagnostics {
    $surfaceFlinger = (& $adb -s $Serial shell dumpsys SurfaceFlinger 2>$null) -join "`n"
    $gles = [regex]::Match($surfaceFlinger, '(?m)^GLES:\s*(.+?)\s*$')
    return [ordered]@{
        boardPlatform = Get-DeviceProperty "ro.board.platform"
        hardware = Get-DeviceProperty "ro.hardware"
        hardwareEgl = Get-DeviceProperty "ro.hardware.egl"
        graphicsDriver0 = Get-DeviceProperty "ro.gfx.driver.0"
        graphicsDriver1 = Get-DeviceProperty "ro.gfx.driver.1"
        gles = if ($gles.Success) { $gles.Groups[1].Value } else { $null }
    }
}

function Get-PowerSourceKey($Battery) {
    return "ac=$($Battery.acPowered);usb=$($Battery.usbPowered);wireless=$($Battery.wirelessPowered)"
}

function Invoke-Phase7([hashtable]$Arguments) {
    & $runner @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Phase 7 runner exited with code $LASTEXITCODE."
    }
}

function Get-Median([double[]]$Values) {
    if ($Values.Count -eq 0) { return $null }
    $sorted = @($Values | Sort-Object)
    $middle = [int][Math]::Floor($sorted.Count / 2)
    if ($sorted.Count % 2 -eq 1) { return [double]$sorted[$middle] }
    return ([double]$sorted[$middle - 1] + [double]$sorted[$middle]) / 2.0
}

function Get-StemSignature($Report) {
    return @($Report.cache.stems | Sort-Object semantic | ForEach-Object {
        [ordered]@{
            semantic = [string]$_.semantic
            wavSha256 = [string]$_.wavSha256
            flacSha256 = [string]$_.promotedSha256
        }
    })
}

function Require-Equal($Expected, $Actual, [string]$Label) {
    if ([string]$Expected -cne [string]$Actual) {
        throw "$Label mismatch: expected '$Expected', actual '$Actual'."
    }
}

function Get-ObjectField($Object, [string]$Name) {
    if ($Object -is [System.Collections.IDictionary]) {
        return $Object[$Name]
    }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) { return $null }
    return $property.Value
}

function Add-WorkerReport(
    [string]$RunId,
    [int]$Pair,
    [int]$Ordinal,
    [string]$HostMode,
    $BatteryStart,
    $ThermalStart,
    $PostRunExit
) {
    $reportPath = Join-Path $deviceDirectory "$RunId-worker.json"
    if (-not (Test-Path -LiteralPath $reportPath -PathType Leaf)) {
        throw "Worker report was not created: $reportPath"
    }
    $report = Get-Content -LiteralPath $reportPath -Raw | ConvertFrom-Json
    if ($report.status -ne "passed") { throw "Worker report failed: $reportPath" }
    $runtime = @($report.cache.runtimeRecords) | Select-Object -Last 1
    $thermalSamples = @($report.thermal.samples)
    $record = [ordered]@{
        runId = $RunId
        pair = $Pair
        ordinal = $Ordinal
        hostMode = $HostMode
        reportFile = $reportPath.Substring($OutputRoot.Length).TrimStart('\', '/').Replace('\', '/')
        reportSha256 = (Get-FileHash -LiteralPath $reportPath -Algorithm SHA256).Hash.ToLowerInvariant()
        identity = [ordered]@{
            appCommit = [string]$report.identity.appCommit
            appApkSha256 = [string]$report.identity.appApkSha256
            testApkSha256 = [string]$report.identity.testApkSha256
            catalogSha256 = [string]$report.identity.catalogSha256
            artifactSha256 = [string]$report.identity.artifactSha256
            contractId = [string]$report.identity.contractId
            fixtureSha256 = [string]$report.fixture.sha256
            cacheKey = [string]$report.cache.cacheKey
        }
        environment = [ordered]@{
            batteryStart = $BatteryStart
            powerSourceKey = Get-PowerSourceKey $BatteryStart
            thermalBeforeInstrumentation = $ThermalStart
            thermalFirstSample = if ($thermalSamples.Count) {
                [int]$thermalSamples[0].status
            } else { $null }
            thermalPeak = $report.thermal.peakStatus
        }
        execution = [ordered]@{
            backendRequested = [string]$report.run.backendRequested
            backendUsed = [string]$report.run.backendUsed
            runtimeProfileId = [string]$runtime.runtimeProfileId
            runtimeName = [string]$report.executionHost.runtime.runtimeName
            runtimeDetail = [string]$report.executionHost.runtime.detail
            decodeMode = [string]$report.audio.decodeDiagnostics.mode
            decodeProfile = [string]$report.audio.decodeDiagnostics.profile
            cpuThreads = [int]$report.run.cpuThreads
            coldProcessBoundary = [bool]$report.run.coldProcessBoundary
            preRunProcessExitMs = [int64]$report.run.preRunProcessExitMs
            postRunProcessExit = $PostRunExit
            sessionStateAfter = if ($null -ne $report.executionHost.session) {
                [string]$report.executionHost.session.state
            } else { $null }
            sessionBackendPolicy = if ($null -ne $report.executionHost.session) {
                [string]$report.executionHost.session.backendPolicy
            } else { $null }
            nativeSessionCreationCount = if ($null -ne $report.executionHost.session) {
                [int]$report.executionHost.session.nativeSessionCreationCount
            } else { 0 }
            instrumentationSharesMainProcess =
                [bool]$report.processRoles.instrumentationSharesMainProcess
            mainMappedNativeLibraries = @(
                $report.processResources.mainAfter.mappedNativeLibraries
            )
            remoteMappedNativeLibraries = if ($null -ne $report.processResources.remoteAfter) {
                @($report.processResources.remoteAfter.mappedNativeLibraries)
            } else { @() }
        }
        timing = [ordered]@{
            firstReadyMs = [int64]$report.timing.firstReadyMs
            fullSongMs = [int64]$report.timing.fullSongMs
            mainProcessCpuMs = [int64]$report.processResources.mainProcessCpuMs
            remoteProcessCpuMs = [int64]$report.processResources.remoteProcessCpuMs
        }
        memory = [ordered]@{
            mainPeakPssBytes = [int64]$report.memory.peakPssBytes
            remoteStartupPssBytes = if ($null -ne $report.memory.idleRemotePssBytes) {
                [int64]$report.memory.idleRemotePssBytes
            } else { 0L }
            remoteIdlePssBytes = if ($null -ne $report.memory.settledIdleRemotePssBytes) {
                [int64]$report.memory.settledIdleRemotePssBytes
            } else { 0L }
            remoteIdleSettleMs = [int64]$report.memory.remoteIdleSettleMs
            remotePeakPssBytes = [int64]$report.memory.peakRemotePssBytes
            summedPeakPssBytes = [int64]$report.memory.peakSummedPssBytes
            mainPeakUssBytes = [int64]$report.memory.peakUssBytes
            remotePeakUssBytes = [int64]$report.memory.peakRemoteUssBytes
            summedPeakUssBytes = [int64]$report.memory.peakSummedUssBytes
            mainPeakRssBytes = [int64]$report.memory.peakRssBytes
            remotePeakRssBytes = [int64]$report.memory.peakRemoteRssBytes
            summedPeakRssBytes = [int64]$report.memory.peakSummedRssBytes
            mainPeakJavaPssBytes = [int64]$report.memory.peakJavaBytes
            mainPeakNativePssBytes = [int64]$report.memory.peakNativeBytes
            mainPeakGraphicsPssBytes = [int64]$report.memory.peakGraphicsBytes
            remotePeakJavaPssBytes = [int64]$report.memory.peakRemoteJavaBytes
            remotePeakNativePssBytes = [int64]$report.memory.peakRemoteNativeBytes
            remotePeakGraphicsPssBytes = [int64]$report.memory.peakRemoteGraphicsBytes
            minimumLargestFreeAddressGapBytes = $report.memory.minimumLargestFreeAddressGapBytes
            mainRuntimeMaxMemoryBytes = [int64]$report.processResources.mainBefore.runtimeMaxMemoryBytes
            remoteRuntimeMaxMemoryBytes = if ($null -ne $report.processResources.remoteBefore) {
                [int64]$report.processResources.remoteBefore.runtimeMaxMemoryBytes
            } else { $null }
            mainOomScoreAdjBefore = $report.processResources.mainBefore.oomScoreAdj
            mainOomScoreAdjAfter = $report.processResources.mainAfter.oomScoreAdj
            remoteOomScoreAdjBefore = if ($null -ne $report.processResources.remoteBefore) {
                $report.processResources.remoteBefore.oomScoreAdj
            } else { $null }
            remoteOomScoreAdjAfter = if ($null -ne $report.processResources.remoteAfter) {
                $report.processResources.remoteAfter.oomScoreAdj
            } else { $null }
        }
        output = [ordered]@{
            frameCount = [int64]$report.audio.outputFrameCount
            finite = [bool]$report.audio.finite
            completedPlayable = [bool]$report.cache.completedPlayable
            stems = Get-StemSignature $report
        }
        playback = [ordered]@{
            startupAttempt = [int]$report.originalPlayback.startupAttempt
            startupFailureCount = @($report.originalPlayback.startupFailures).Count
            snapshotCount = [int]$report.originalPlayback.snapshotCount
            maximumPositionDriftMs = [int64]$report.originalPlayback.maximumPositionDriftMs
            unexpectedEventCount = [int]$report.originalPlayback.unexpectedEventCount
        }
    }
    $runRecords.Add($record)
}

function Test-Gates {
    if ($runRecords.Count -ne $PairCount * 2) {
        throw "Expected $($PairCount * 2) passing runs, got $($runRecords.Count)."
    }
    $reference = $runRecords[0]
    foreach ($record in $runRecords) {
        $expectedBackendRequest = if ($BackendMode -eq "cpu") {
            "LiteRtCpu"
        } else {
            "LiteRtAuto"
        }
        Require-Equal $expectedBackendRequest $record.execution.backendRequested `
            "$($record.runId) backend request"
        if ($BackendMode -eq "cpu") {
            Require-Equal "LiteRtCpu" $record.execution.backendUsed `
                "$($record.runId) concrete CPU backend"
        } else {
            Require-Equal "LiteRtGpu" $record.execution.backendUsed `
                "$($record.runId) concrete GPU backend"
            if ($record.execution.runtimeDetail -notmatch 'eligibility=Eligible') {
                throw "$($record.runId) did not retain eligible GPU runtime diagnostics."
            }
            if ($record.execution.runtimeDetail -notmatch
                    'accelerator=libLiteRtClGlAccelerator\.so') {
                throw "$($record.runId) did not resolve the packaged GPU accelerator."
            }
            $acceleratorLibraries = if ($record.hostMode -eq "bound-remote") {
                @($record.execution.remoteMappedNativeLibraries)
            } else {
                @($record.execution.mainMappedNativeLibraries)
            }
            if ($acceleratorLibraries -notcontains "libOpenCL.so" -and
                    $acceleratorLibraries -notcontains "libGLESv2.so") {
                throw "$($record.runId) did not map an OpenCL or OpenGL GPU driver in its host process."
            }
        }
        foreach ($field in @(
            "appCommit", "appApkSha256", "testApkSha256", "catalogSha256",
            "artifactSha256", "contractId", "fixtureSha256", "cacheKey"
        )) {
            Require-Equal (Get-ObjectField $reference.identity $field) `
                (Get-ObjectField $record.identity $field) "Identity $field"
        }
        Require-Equal $reference.execution.decodeMode $record.execution.decodeMode "Decode mode"
        Require-Equal $reference.execution.decodeProfile $record.execution.decodeProfile `
            "Decode profile"
        Require-Equal $reference.output.frameCount $record.output.frameCount "Output frame count"
        Require-Equal ($reference.output.stems | ConvertTo-Json -Compress) `
            ($record.output.stems | ConvertTo-Json -Compress) "Stem integrity"
        if (-not $record.output.finite -or -not $record.output.completedPlayable) {
            throw "$($record.runId) did not produce finite playable output."
        }
        if (-not $record.execution.coldProcessBoundary -or
                -not $record.execution.postRunProcessExit.allExited) {
            throw "$($record.runId) did not preserve the cold process boundary."
        }
        if (-not $record.execution.instrumentationSharesMainProcess) {
            throw "$($record.runId) did not record the instrumentation/main PID co-location."
        }
        if ($record.execution.postRunProcessExit.exitElapsedMs -gt
                [int64]$thresholds.resource.maximumProcessExitMs) {
            throw "$($record.runId) exceeded the process-exit gate."
        }
        if ($record.memory.mainRuntimeMaxMemoryBytes -lt
                [int64]$thresholds.resource.minimumRuntimeMaxMemoryBytes) {
            throw "$($record.runId) failed the main Java heap admission gate."
        }
        if ($record.hostMode -eq "bound-remote") {
            Require-Equal $BackendMode $record.execution.sessionBackendPolicy `
                "$($record.runId) remote descriptor backend policy"
            if ($record.execution.sessionStateAfter -ne "Empty" -or
                    $record.execution.nativeSessionCreationCount -ne 1) {
                throw "$($record.runId) did not use the frozen non-x86 SingleUse policy."
            }
            if ($record.memory.remoteRuntimeMaxMemoryBytes -lt
                    [int64]$thresholds.resource.minimumRuntimeMaxMemoryBytes) {
                throw "$($record.runId) failed the remote Java heap admission gate."
            }
            if ($record.memory.remoteIdlePssBytes -le 0 -or
                    $record.memory.remoteIdlePssBytes -gt
                    [int64]$thresholds.resource.maximumIdleRemotePssBytes) {
                throw "$($record.runId) exceeded the settled idle remote PSS gate: " +
                    "$($record.memory.remoteIdlePssBytes) bytes."
            }
            if ($record.memory.remoteIdleSettleMs -ne $requiredRemoteIdleSettleMs) {
                throw "$($record.runId) used an invalid remote idle settle interval: " +
                    "$($record.memory.remoteIdleSettleMs) ms."
            }
            if ($null -ne $record.memory.minimumLargestFreeAddressGapBytes -and
                    $record.memory.minimumLargestFreeAddressGapBytes -lt
                    [int64]$thresholds.resource.minimumLargestFreeAddressGapBytes) {
                throw "$($record.runId) crossed the free-address-gap floor."
            }
        }
        if ($record.playback.maximumPositionDriftMs -gt
                [int64]$thresholds.playback.maximumPositionDriftMs -or
                $record.playback.unexpectedEventCount -gt
                [int]$thresholds.playback.maximumUnexpectedEventCount) {
            throw "$($record.runId) failed original playback continuity."
        }
    }

    foreach ($pair in 1..$PairCount) {
        $members = @($runRecords | Where-Object pair -eq $pair | Sort-Object ordinal)
        if ($members.Count -ne 2) { throw "Pair $pair is incomplete." }
        Require-Equal $members[0].execution.backendUsed $members[1].execution.backendUsed `
            "Pair $pair concrete backend"
        if ([bool]$thresholds.environment.requireMatchingPowerSourceWithinPair) {
            Require-Equal $members[0].environment.powerSourceKey `
                $members[1].environment.powerSourceKey "Pair $pair power source"
        }
        $thermalValues = @($members.environment.thermalFirstSample | Where-Object { $null -ne $_ })
        if ($thermalValues.Count -eq 2 -and
                [Math]::Abs([int]$thermalValues[0] - [int]$thermalValues[1]) -gt
                [int]$thresholds.environment.maximumPairStartingThermalStatusDelta) {
            throw "Pair $pair starting thermal statuses differ too much."
        }
    }

    $inProcess = @($runRecords | Where-Object hostMode -eq "in-process")
    $boundRemote = @($runRecords | Where-Object hostMode -eq "bound-remote")
    $medians = [ordered]@{
        inProcess = [ordered]@{
            firstReadyMs = Get-Median @($inProcess.timing.firstReadyMs)
            fullSongMs = Get-Median @($inProcess.timing.fullSongMs)
            summedPeakPssBytes = Get-Median @($inProcess.memory.summedPeakPssBytes)
            summedPeakNativePssBytes = Get-Median @($inProcess | ForEach-Object {
                $_.memory.mainPeakNativePssBytes + $_.memory.remotePeakNativePssBytes
            })
            summedPeakGraphicsPssBytes = Get-Median @($inProcess | ForEach-Object {
                $_.memory.mainPeakGraphicsPssBytes + $_.memory.remotePeakGraphicsPssBytes
            })
        }
        boundRemote = [ordered]@{
            firstReadyMs = Get-Median @($boundRemote.timing.firstReadyMs)
            fullSongMs = Get-Median @($boundRemote.timing.fullSongMs)
            summedPeakPssBytes = Get-Median @($boundRemote.memory.summedPeakPssBytes)
            summedPeakNativePssBytes = Get-Median @($boundRemote | ForEach-Object {
                $_.memory.mainPeakNativePssBytes + $_.memory.remotePeakNativePssBytes
            })
            summedPeakGraphicsPssBytes = Get-Median @($boundRemote | ForEach-Object {
                $_.memory.mainPeakGraphicsPssBytes + $_.memory.remotePeakGraphicsPssBytes
            })
        }
    }
    $firstReadyRatio = $medians.boundRemote.firstReadyMs / $medians.inProcess.firstReadyMs
    $fullSongRatio = $medians.boundRemote.fullSongMs / $medians.inProcess.fullSongMs
    $firstReadyRegression = $medians.boundRemote.firstReadyMs - $medians.inProcess.firstReadyMs
    $fullSongRegression = $medians.boundRemote.fullSongMs - $medians.inProcess.fullSongMs
    $pssRegression = $medians.boundRemote.summedPeakPssBytes -
        $medians.inProcess.summedPeakPssBytes
    if ($firstReadyRatio -gt [double]$thresholds.performance.maximumMedianFirstReadyRatio -and
            $firstReadyRegression -gt
            [int64]$thresholds.performance.maximumMedianFirstReadyRegressionMs) {
        throw "BoundRemote exceeded the first-ready median gate."
    }
    if ($fullSongRatio -gt [double]$thresholds.performance.maximumMedianFullSongRatio -and
            $fullSongRegression -gt
            [int64]$thresholds.performance.maximumMedianFullSongRegressionMs) {
        throw "BoundRemote exceeded the full-song median gate."
    }
    if ($pssRegression -gt
            [int64]$thresholds.resource.maximumMedianSummedPssRegressionBytes) {
        throw "BoundRemote exceeded the summed-PSS median gate."
    }
    return [ordered]@{
        medians = $medians
        boundToInProcess = [ordered]@{
            firstReadyRatio = $firstReadyRatio
            firstReadyRegressionMs = $firstReadyRegression
            fullSongRatio = $fullSongRatio
            fullSongRegressionMs = $fullSongRegression
            summedPeakPssRegressionBytes = $pssRegression
        }
    }
}

$comparison = $null
try {
    if (-not $SkipAcquisition -and [string]::IsNullOrWhiteSpace($SeedSummary)) {
        $acquisitionId = "$RunPrefix-$safeSerial-$ProcessAbi-$BackendMode-acquisition"
        $arguments = @{
            Serial = $Serial
            ProcessAbi = $ProcessAbi
            ModelId = $ModelId
            Stage = "acquisition"
            BackendMode = $BackendMode
            RunId = $acquisitionId
            OutputRoot = $OutputRoot
        }
        if (-not $buildNeeded) { $arguments.SkipBuild = $true }
        Invoke-Phase7 $arguments
        $buildNeeded = $false
    }

    for ($pair = 1; $pair -le $PairCount; $pair++) {
        if ($pair -notin $selectedPairs) { continue }
        $frozenOrders = @($thresholds.measurement.pairOrder)
        $order = if ($pair -le $frozenOrders.Count) {
            @($frozenOrders[$pair - 1])
        } elseif ($pair % 2 -eq 1) {
            @("in-process", "bound-remote")
        } else {
            @("bound-remote", "in-process")
        }
        if ($order.Count -ne 2) { throw "Pair $pair has an invalid frozen host order." }
        for ($ordinal = 1; $ordinal -le 2; $ordinal++) {
            if ($CooldownSeconds -gt 0 -and $runRecords.Count -gt 0) {
                Start-Sleep -Seconds $CooldownSeconds
            }
            $thermalStart = Wait-ForThermalAdmission
            $batteryStart = Get-BatteryState
            $hostMode = $order[$ordinal - 1]
            $hostLabel = $hostMode -replace '-', ''
            $runId = "$RunPrefix-$safeSerial-$ProcessAbi-$BackendMode-pair$pair-$ordinal-$hostLabel"
            Write-Host "Running $runId with $hostMode..."
            $arguments = @{
                Serial = $Serial
                ProcessAbi = $ProcessAbi
                ModelId = $ModelId
                Stage = "worker"
                BackendMode = $BackendMode
                ExecutionHostMode = $hostMode
                KeepAppData = $true
                SourcePath = $sourcePath
                FixtureId = $FixtureId
                RunId = $runId
                OutputRoot = $OutputRoot
                RunClass = "cold-session"
                SkipInstall = $true
                ForceStopBeforeRun = $true
                ProbeOriginalPlayback = $true
            }
            if (-not $buildNeeded) { $arguments.SkipBuild = $true }
            try {
                Invoke-Phase7 $arguments
                $postRunExit = Stop-AppProcesses
                Add-WorkerReport -RunId $runId -Pair $pair -Ordinal $ordinal `
                    -HostMode $hostMode -BatteryStart $batteryStart `
                    -ThermalStart $thermalStart -PostRunExit $postRunExit
            } catch {
                $failures.Add("${runId}: $($_.Exception.Message)")
                throw
            } finally {
                $buildNeeded = $false
            }
        }
    }
    try {
        $comparison = Test-Gates
    } catch {
        $failures.Add("gate: $($_.Exception.Message)")
        throw
    }
} finally {
    $summary = [ordered]@{
        schemaVersion = "phase5-paired-host-report-v2"
        status = if ($null -ne $comparison -and $failures.Count -eq 0) { "passed" } else { "failed" }
        generatedAtUtc = [DateTime]::UtcNow.ToString("o")
        thresholdsVersion = [string]$thresholds.schemaVersion
        modelId = $ModelId
        fixtureId = $FixtureId
        backendMode = $BackendMode
        pairCount = $PairCount
        pairsRun = $selectedPairs
        seedSummary = $seedSummaryReportPath
        validationOnly = [bool]$ValidateSeedOnly
        device = [ordered]@{
            serial = $Serial
            manufacturer = Get-DeviceProperty "ro.product.manufacturer"
            model = Get-DeviceProperty "ro.product.model"
            androidApi = [int](Get-DeviceProperty "ro.build.version.sdk")
            processAbi = $ProcessAbi
            abiList = Get-DeviceProperty "ro.product.cpu.abilist"
            heapGrowthLimit = Get-DeviceProperty "dalvik.vm.heapgrowthlimit"
            gpuDriver = Get-GpuDriverDiagnostics
        }
        fixture = [ordered]@{
            fileName = $fixture.fileName
            byteSize = [int64]$fixture.byteSize
            sha256 = $fixture.sha256
            durationUs = [int64]$fixture.durationUs
        }
        failures = @($failures)
        comparison = $comparison
        runs = @($runRecords)
    }
    $summaryPath = Join-Path $deviceDirectory `
        "$RunPrefix-$safeSerial-$ProcessAbi-$BackendMode-summary.json"
    $summary | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $summaryPath -Encoding utf8
    Write-Host "Saved paired-host summary to $summaryPath"
}

if ($failures.Count -gt 0 -or $null -eq $comparison) {
    throw "Phase 5 paired-host matrix failed."
}
Write-Host "Phase 5 paired-host matrix passed with $($runRecords.Count) runs."
