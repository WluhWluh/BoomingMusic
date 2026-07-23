param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,

    [Parameter(Mandatory = $true)]
    [ValidateSet("arm64-v8a", "armeabi-v7a", "x86_64")]
    [string]$ProcessAbi,

    [string]$ModelId = "uvr_mdxnet_3_9662",

    [ValidateSet("cpu", "auto")]
    [string]$BackendMode = "auto",

    [string]$SourceRepository = "",
    [string[]]$FixtureId = @(),
    [string]$OutputRoot = "",
    [string]$RunPrefix = "phase7-formats-v1",
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$fixturesPath = Join-Path $repoRoot "docs/validation/litert-phase7/fixtures-v2.json"
$runner = Join-Path $PSScriptRoot "run_phase7_validation.ps1"

if ($BackendMode -eq "auto" -and $ProcessAbi -ne "arm64-v8a") {
    throw "The Phase 7 Auto format corpus is currently qualified only for arm64-v8a."
}
if ([string]::IsNullOrWhiteSpace($SourceRepository)) {
    $SourceRepository = Join-Path $repoRoot "..\..\MusicSourceSeparation"
}
$sourceRoot = (Resolve-Path -LiteralPath $SourceRepository).Path
$fixtures = Get-Content -LiteralPath $fixturesPath -Raw | ConvertFrom-Json
$sourceRevision = (& git -C $sourceRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $sourceRevision -ne $fixtures.sourceRevision) {
    throw "MusicSourceSeparation must be checked out at $($fixtures.sourceRevision); actual: $sourceRevision"
}

$formatFixtures = @($fixtures.fixtures) | Where-Object {
    @($_.purpose) -contains "source-format" -and
        ($FixtureId.Count -eq 0 -or $FixtureId -contains $_.fixtureId)
}
if ($formatFixtures.Count -eq 0) {
    throw "No requested source-format fixtures were found in fixtures-v2.json."
}
if ($FixtureId.Count -gt 0 -and $formatFixtures.Count -ne $FixtureId.Count) {
    $resolvedIds = @($formatFixtures | ForEach-Object fixtureId)
    $missingIds = @($FixtureId | Where-Object { $_ -notin $resolvedIds })
    throw "Unknown or non-format fixture IDs: $($missingIds -join ', ')"
}

$failures = [System.Collections.Generic.List[string]]::new()
$buildNeeded = -not $SkipBuild
foreach ($fixture in $formatFixtures) {
    $sourcePath = Join-Path $sourceRoot ($fixture.relativeSourcePath -replace '/', '\')
    if (-not (Test-Path -LiteralPath $sourcePath -PathType Leaf)) {
        throw "Missing generated fixture $($fixture.fixtureId): $sourcePath. Run the companion corpus generator first."
    }
    $safeSerial = $Serial -replace '[^A-Za-z0-9._-]', '_'
    $runId = "$RunPrefix-$safeSerial-$ProcessAbi-$($fixture.fixtureId)"
    $arguments = @{
        Serial = $Serial
        ProcessAbi = $ProcessAbi
        ModelId = $ModelId
        Stage = "worker"
        BackendMode = $BackendMode
        KeepAppData = $true
        SourcePath = $sourcePath
        FixtureId = $fixture.fixtureId
        RunId = $runId
    }
    if (-not [string]::IsNullOrWhiteSpace($OutputRoot)) {
        $arguments.OutputRoot = $OutputRoot
    }
    if (-not $buildNeeded) {
        $arguments.SkipBuild = $true
    }

    Write-Host "Running $($fixture.fixtureId) on $Serial ($ProcessAbi/$BackendMode)..."
    try {
        & $runner @arguments
    } catch {
        $failures.Add("$($fixture.fixtureId): $($_.Exception.Message)")
        Write-Warning $failures[$failures.Count - 1]
    } finally {
        $buildNeeded = $false
    }
}

if ($failures.Count -gt 0) {
    throw "Phase 7 format corpus failed:`n$($failures -join "`n")"
}
Write-Host "Phase 7 format corpus passed for $($formatFixtures.Count) fixtures."
