param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,

    [Parameter(Mandatory = $true)]
    [ValidateSet("arm64-v8a", "armeabi-v7a", "x86_64")]
    [string]$ProcessAbi,

    [string]$ModelId = "uvr_mdxnet_3_9662",

    [ValidateSet("cpu", "auto")]
    [string]$BackendMode = "cpu",

    [string]$SourceRepository = "",
    [string]$FixtureId = "coast_town_full_wav",
    [string]$OutputRoot = "",
    [string]$RunPrefix = "phase7-resource-v1",
    [int[]]$ProcessorCounts = @(0),
    [int]$WarmRepetitions = 3,
    [switch]$SkipBuild,
    [switch]$SkipAcquisition
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$runner = Join-Path $PSScriptRoot "run_phase7_validation.ps1"
$fixturesPath = Join-Path $repoRoot "docs\validation\litert-phase7\fixtures-v2.json"

if ([string]::IsNullOrWhiteSpace($SourceRepository)) {
    $SourceRepository = Join-Path $repoRoot "..\..\MusicSourceSeparation"
}
$sourceRoot = (Resolve-Path -LiteralPath $SourceRepository).Path

if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $OutputRoot = Join-Path $repoRoot "build\phase7-resource-matrix"
}
New-Item -ItemType Directory -Force -Path $OutputRoot | Out-Null
$OutputRoot = (Resolve-Path -LiteralPath $OutputRoot).Path

if ($WarmRepetitions -lt 1 -or $WarmRepetitions -gt 20) {
    throw "WarmRepetitions must be between 1 and 20."
}
if ($ProcessorCounts.Count -eq 0) {
    throw "ProcessorCounts must contain at least one value; use 0 for the device default."
}
if ($ProcessorCounts | Where-Object { $_ -lt 0 }) {
    throw "ProcessorCounts cannot contain negative values."
}
if ($BackendMode -eq "auto" -and $ProcessAbi -ne "arm64-v8a") {
    throw "BackendMode=auto is currently qualified only for arm64-v8a."
}
if ($BackendMode -eq "auto" -and ($ProcessorCounts | Where-Object { $_ -ne 0 })) {
    throw "BackendMode=auto cannot be combined with a processor-count override."
}

$fixtures = Get-Content -LiteralPath $fixturesPath -Raw | ConvertFrom-Json
$fixture = @($fixtures.fixtures) |
    Where-Object { $_.fixtureId -eq $FixtureId } |
    Select-Object -First 1
if ($null -eq $fixture) {
    throw "Fixture is absent from fixtures-v2.json: $FixtureId"
}
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

function Invoke-Phase7([hashtable]$Arguments) {
    & $runner @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Phase 7 runner exited with code $LASTEXITCODE."
    }
}

function Get-ReportPath([string]$RunId) {
    return Join-Path $deviceDirectory "$RunId-worker.json"
}

function Add-Report([string]$RunId, [string]$RunClass, [int]$ProcessorCount) {
    $reportPath = Get-ReportPath $RunId
    if (-not (Test-Path -LiteralPath $reportPath -PathType Leaf)) {
        throw "The worker report was not created: $reportPath"
    }
    $report = Get-Content -LiteralPath $reportPath -Raw | ConvertFrom-Json
    if ($report.status -ne "passed") {
        throw "Worker report did not pass: $reportPath"
    }
    $run = $report.run
    $timing = $report.timing
    $memory = $report.memory
    $thermal = $report.thermal
    $relativeReport = $reportPath.Substring($OutputRoot.Length).TrimStart('\', '/')
    $runRecords.Add([ordered]@{
        runId = $RunId
        reportFile = $relativeReport.Replace('\', '/')
        runClass = $RunClass
        processorCountOverride = if ($ProcessorCount -gt 0) { $ProcessorCount } else { $null }
        cpuThreads = [int]$run.cpuThreads
        backendRequested = [string]$run.backendRequested
        backendUsed = [string]$run.backendUsed
        fallbackStage = $run.fallbackStage
        fallbackReason = $run.fallbackReason
        firstReadyMs = [int64]$timing.firstReadyMs
        fullSongMs = [int64]$timing.fullSongMs
        processCpuMs = [int64]$timing.processCpuMs
        idlePssBytes = [int64]$memory.idlePssBytes
        peakPssBytes = [int64]$memory.peakPssBytes
        peakPssDeltaBytes = [int64]$memory.peakPssDeltaBytes
        peakJavaBytes = [int64]$memory.peakJavaBytes
        peakNativeBytes = [int64]$memory.peakNativeBytes
        peakGraphicsBytes = [int64]$memory.peakGraphicsBytes
        thermalAvailable = [bool]$thermal.available
        thermalPeakStatus = $thermal.peakStatus
        thermalSampleCount = @($thermal.samples).Count
        thermalSamples = @($thermal.samples)
        sourceReport = $report
    })
}

try {
    if (-not $SkipAcquisition) {
        $acquisitionId = "$RunPrefix-$safeSerial-$ProcessAbi-acquisition"
        Write-Host "Acquiring and selecting $ModelId on $Serial..."
        $acquisitionArguments = @{
            Serial = $Serial
            ProcessAbi = $ProcessAbi
            ModelId = $ModelId
            Stage = "acquisition"
            BackendMode = $BackendMode
            RunId = $acquisitionId
            OutputRoot = $OutputRoot
        }
        if (-not $buildNeeded) { $acquisitionArguments.SkipBuild = $true }
        Invoke-Phase7 $acquisitionArguments
        $buildNeeded = $false
    }

    $firstRun = $true
    foreach ($processorCount in $ProcessorCounts) {
        $threadLabel = if ($processorCount -eq 0) { "default" } else { "t$processorCount" }
        $runCount = 1 + $WarmRepetitions
        for ($index = 0; $index -lt $runCount; $index++) {
            $runClass = if ($index -eq 0) { "cold-session" } else { "warm-session" }
            $ordinal = if ($index -eq 0) { "cold" } else { "warm$index" }
            $runId = "$RunPrefix-$safeSerial-$ProcessAbi-$threadLabel-$ordinal"
            Write-Host "Running $runId ($BackendMode)..."
            $arguments = @{
                Serial = $Serial
                ProcessAbi = $ProcessAbi
                ModelId = $ModelId
                Stage = "worker"
                BackendMode = $BackendMode
                KeepAppData = $true
                SourcePath = $sourcePath
                FixtureId = $FixtureId
                RunId = $runId
                OutputRoot = $OutputRoot
                RunClass = $runClass
            }
            if (-not $buildNeeded) { $arguments.SkipBuild = $true }
            if ($index -eq 0 -and $firstRun) {
                $arguments.CleanInstallScenario = $true
            }
            if ($processorCount -gt 0) {
                $arguments.ProcessorCount = $processorCount
            }
            try {
                Invoke-Phase7 $arguments
                Add-Report -RunId $runId -RunClass $runClass -ProcessorCount $processorCount
            } catch {
                $message = "${runId}: $($_.Exception.Message)"
                $failures.Add($message)
                Write-Warning $message
            }
            $buildNeeded = $false
            $firstRun = $false
        }
    }
} finally {
    $generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    $deviceInfo = [ordered]@{
        serial = $Serial
        model = (& adb -s $Serial shell getprop ro.product.model).Trim()
        manufacturer = (& adb -s $Serial shell getprop ro.product.manufacturer).Trim()
        androidApi = [int]((& adb -s $Serial shell getprop ro.build.version.sdk).Trim())
        processAbi = $ProcessAbi
        abiList = (& adb -s $Serial shell getprop ro.product.cpu.abilist).Trim()
    }
    $summary = [ordered]@{
        schemaVersion = "phase7-resource-matrix-v1"
        generatedAtUtc = $generatedAt
        modelId = $ModelId
        fixtureId = $FixtureId
        backendMode = $BackendMode
        warmRepetitions = $WarmRepetitions
        processorCounts = @($ProcessorCounts)
        device = $deviceInfo
        sourceFixture = [ordered]@{
            fileName = $fixture.fileName
            byteSize = [int64]$fixture.byteSize
            sha256 = $fixture.sha256
            durationUs = [int64]$fixture.durationUs
        }
        failures = @($failures)
        runs = @($runRecords | ForEach-Object {
            $copy = [ordered]@{}
            foreach ($property in $_.Keys) {
                if ($property -ne "sourceReport") { $copy[$property] = $_[$property] }
            }
            $copy
        })
    }
    $summaryPath = Join-Path $deviceDirectory "$RunPrefix-$safeSerial-summary.json"
    $summary | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $summaryPath -Encoding utf8

    $markdown = [System.Text.StringBuilder]::new()
    [void]$markdown.AppendLine("# Phase 7 resource matrix")
    [void]$markdown.AppendLine("")
    [void]$markdown.AppendLine(("Generated: ``{0}``" -f $generatedAt))
    [void]$markdown.AppendLine("")
    [void]$markdown.AppendLine("| Run | Class | Threads | Backend | First ready (ms) | Full song (ms) | Process CPU (ms) | PSS delta (MiB) | Java (MiB) | Native (MiB) | Graphics (MiB) | Thermal samples | Peak thermal status |")
    [void]$markdown.AppendLine("| --- | --- | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
    foreach ($record in $runRecords) {
        $mib = { param($bytes) [math]::Round(([double]$bytes / 1MB), 1) }
        [void]$markdown.AppendLine((
            "| {0} | {1} | {2} | {3} | {4} | {5} | {6} | {7} | {8} | {9} | {10} | {11} | {12} |" -f
            $record.runId,
            $record.runClass,
            $record.cpuThreads,
            $record.backendUsed,
            $record.firstReadyMs,
            $record.fullSongMs,
            $record.processCpuMs,
            (&$mib $record.peakPssDeltaBytes),
            (&$mib $record.peakJavaBytes),
            (&$mib $record.peakNativeBytes),
            (&$mib $record.peakGraphicsBytes),
            $record.thermalSampleCount,
            $record.thermalPeakStatus
        ))
    }
    [void]$markdown.AppendLine("")
    [void]$markdown.AppendLine("The first run is marked ``cold-session``; later runs reuse the installed model and are marked ``warm-session``. Raw worker reports and host ``dumpsys`` diagnostics remain beside this summary.")
    if ($failures.Count -gt 0) {
        [void]$markdown.AppendLine("")
        [void]$markdown.AppendLine("## Failures")
        foreach ($failure in $failures) { [void]$markdown.AppendLine("- $failure") }
    }
    $markdownPath = Join-Path $deviceDirectory "$RunPrefix-$safeSerial-summary.md"
    $markdown.ToString() | Set-Content -LiteralPath $markdownPath -Encoding utf8
    Write-Host "Saved resource summary to $summaryPath"
    Write-Host "Saved resource table to $markdownPath"
}

if ($failures.Count -gt 0) {
    throw "Phase 7 resource matrix had $($failures.Count) failed run(s)."
}
if ($runRecords.Count -eq 0) {
    throw "Phase 7 resource matrix produced no passing worker reports."
}
Write-Host "Phase 7 resource matrix passed with $($runRecords.Count) worker report(s)."
