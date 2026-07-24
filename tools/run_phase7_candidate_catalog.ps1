param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,

    [ValidateSet("arm64-v8a", "armeabi-v7a", "x86_64")]
    [string]$ProcessAbi = "arm64-v8a",

    [string]$BssTfliteRepository = "",
    [string[]]$ModelId = @(),
    [string]$OutputRoot = "",
    [switch]$MetadataOnly,
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$phase7Runner = Join-Path $PSScriptRoot "run_phase7_validation.ps1"
$catalogPath = Join-Path $repoRoot `
    "app\src\main\assets\source-separation\model-catalog-v2.json"
$package = "com.wluhwluh.booming.sourcesep.debug"
$testRunner = "$package.test/androidx.test.runner.AndroidJUnitRunner"
$testClass = "com.mardous.booming.separation.SourceSeparationPhase7CandidateDeviceTest"
$testMethod = "validateDownloadOnlyCandidate"
$adb = (Get-Command adb -ErrorAction Stop).Source

if ([string]::IsNullOrWhiteSpace($BssTfliteRepository)) {
    $BssTfliteRepository = Join-Path $repoRoot "..\..\BSSModels\bss-tflite"
}
$bssRoot = (Resolve-Path -LiteralPath $BssTfliteRepository).Path
$bssCatalogPath = Join-Path $bssRoot "catalog\model-catalog-v2.json"
$conversionManifestPath = Join-Path $bssRoot "manifests\all-candidates-fp32.json"
if (-not (Test-Path -LiteralPath $bssCatalogPath -PathType Leaf)) {
    throw "bss-tflite catalog is missing: $bssCatalogPath"
}
if (-not (Test-Path -LiteralPath $conversionManifestPath -PathType Leaf)) {
    throw "bss-tflite conversion manifest is missing: $conversionManifestPath"
}

if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $OutputRoot = Join-Path $repoRoot "build\phase7-candidate-catalog"
}
New-Item -ItemType Directory -Force -Path $OutputRoot | Out-Null
$OutputRoot = (Resolve-Path -LiteralPath $OutputRoot).Path
$safeSerial = $Serial -replace '[^A-Za-z0-9._-]', '_'
$deviceDirectory = Join-Path $OutputRoot $safeSerial
New-Item -ItemType Directory -Force -Path $deviceDirectory | Out-Null

function Get-Sha256([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Require-Equal($Actual, $Expected, [string]$Label) {
    if ($Actual -ne $Expected) {
        throw "$Label mismatch: expected '$Expected', got '$Actual'."
    }
}

function Require-SafeName([string]$Value, [string]$Label) {
    if ($Value -notmatch '^[A-Za-z0-9._-]{1,120}$') {
        throw "$Label contains unsupported characters: $Value"
    }
}

function Invoke-Adb {
    $output = & $adb -s $Serial @args
    if ($LASTEXITCODE -ne 0) {
        throw "adb command failed for $Serial`: adb $($args -join ' ')"
    }
    return $output
}

function Invoke-DownloadWithRetry([string]$Uri, [string]$Destination) {
    $lastError = $null
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        Remove-Item -LiteralPath $Destination -Force -ErrorAction SilentlyContinue
        try {
            Invoke-WebRequest -Uri $Uri -UseBasicParsing -OutFile $Destination
            if (-not (Test-Path -LiteralPath $Destination -PathType Leaf) -or
                    (Get-Item -LiteralPath $Destination).Length -le 0) {
                throw "Download created an empty file."
            }
            return
        } catch {
            $lastError = $_.Exception
            if ($attempt -lt 3) { Start-Sleep -Seconds ([math]::Pow(2, $attempt - 1)) }
        }
    }
    throw "Could not download $Uri after three attempts: $($lastError.Message)"
}

function Get-ReleaseArtifact($ReleaseManifest, [string]$RequestedModelId) {
    $row = @($ReleaseManifest.artifacts) | Where-Object {
        $_.modelId -eq $RequestedModelId
    } | Select-Object -First 1
    if ($null -eq $row) {
        throw "Remote Release manifest is missing $RequestedModelId."
    }
    return $row
}

function Get-ConversionModel($ConversionManifest, [string]$RequestedModelId) {
    $row = @($ConversionManifest.models) | Where-Object {
        $_.modelId -eq $RequestedModelId
    } | Select-Object -First 1
    if ($null -eq $row) {
        throw "Conversion manifest is missing $RequestedModelId."
    }
    return $row
}

$catalogSha256 = Get-Sha256 $catalogPath
$bssCatalogSha256 = Get-Sha256 $bssCatalogPath
Require-Equal $bssCatalogSha256 $catalogSha256 "Bundled/bss-tflite catalog SHA-256"
$catalog = Get-Content -LiteralPath $catalogPath -Raw | ConvertFrom-Json
$conversionManifest = Get-Content -LiteralPath $conversionManifestPath -Raw |
    ConvertFrom-Json
Require-Equal $conversionManifest.status "converted_and_desktop_validated" `
    "Conversion manifest status"
Require-Equal @($conversionManifest.missingModelIds).Count 0 `
    "Conversion manifest missing-model count"
Require-Equal @($conversionManifest.models).Count @($catalog.artifacts).Count `
    "Conversion/catalog artifact count"

$bssRevision = (& git -C $bssRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $bssRevision -notmatch '^[0-9a-f]{40}$') {
    throw "Could not resolve the bss-tflite revision."
}
$conversionManifestSha256 = Get-Sha256 $conversionManifestPath

$candidateEntries = @($catalog.entries) | Where-Object {
    $_.supportLevel -eq "download-only" -and
        [string]::IsNullOrWhiteSpace([string]$_.contractId)
} | Sort-Object modelId
if ($candidateEntries.Count -ne 27) {
    throw "Expected 27 contract-free download-only candidates, got $($candidateEntries.Count)."
}
if ($ModelId.Count -gt 0) {
    $unknown = @($ModelId) | Where-Object {
        $_ -notin @($candidateEntries.modelId)
    }
    if ($unknown.Count -gt 0) {
        throw "Requested model is not a contract-free candidate: $($unknown -join ', ')"
    }
    $candidateEntries = @($candidateEntries) | Where-Object {
        $_.modelId -in $ModelId
    }
}
if ($candidateEntries.Count -eq 0) {
    throw "The candidate matrix is empty."
}

$releaseTags = @(
    foreach ($entry in $candidateEntries) {
        $artifact = @($catalog.artifacts) | Where-Object {
            $_.artifactId -eq $entry.artifactId
        } | Select-Object -First 1
        if ($null -eq $artifact -or $null -eq $artifact.tflite -or
                $null -eq $artifact.tflite.releaseAsset) {
            throw "Candidate has no published TFLite asset: $($entry.modelId)"
        }
        [string]$artifact.tflite.releaseAsset.tag
    }
) | Sort-Object -Unique
if ($releaseTags.Count -ne 1) {
    throw "Candidate audit requires one immutable Release tag, got $($releaseTags -join ', ')."
}
$releaseTag = [string]($releaseTags | Select-Object -First 1)
$releaseBaseUrl = "https://github.com/WluhWluh/bss-tflite/releases/download/$releaseTag"
$releaseManifestUrl = "$releaseBaseUrl/release-manifest-v1.json"
$remoteManifestPath = Join-Path $deviceDirectory "release-manifest-v1.json"
Invoke-DownloadWithRetry $releaseManifestUrl $remoteManifestPath
$remoteManifestSha256 = Get-Sha256 $remoteManifestPath
$releaseManifest = Get-Content -LiteralPath $remoteManifestPath -Raw | ConvertFrom-Json
Require-Equal $releaseManifest.releaseTag $releaseTag "Release manifest tag"
Require-Equal ([int]$releaseManifest.artifactCount) 30 "Release manifest artifact count"
Require-Equal ([int64]$releaseManifest.totalArtifactBytes) 1461964976 `
    "Release manifest artifact bytes"

$reviewedSidecars = @($releaseManifest.reviewedSidecars.fileName) | Sort-Object
$expectedSidecars = @(
    foreach ($contract in $catalog.contracts) {
        "$($contract.artifact.fileName).json"
    }
) | Sort-Object
$sidecarDifference = Compare-Object $reviewedSidecars $expectedSidecars
if ($null -ne $sidecarDifference) {
    throw "Release sidecar set does not match the three reviewed catalog contracts."
}

$candidateInputs = [System.Collections.Generic.List[object]]::new()
foreach ($entry in $candidateEntries) {
    $artifact = @($catalog.artifacts) | Where-Object {
        $_.artifactId -eq $entry.artifactId
    } | Select-Object -First 1
    $tflite = $artifact.tflite
    $conversion = Get-ConversionModel $conversionManifest $entry.modelId
    $release = Get-ReleaseArtifact $releaseManifest $entry.modelId
    $desktop = $conversion.validation.desktop
    Require-Equal $conversion.status "converted_and_desktop_validated" `
        "$($entry.modelId) conversion status"
    Require-Equal $conversion.artifact.file $tflite.fileName `
        "$($entry.modelId) conversion filename"
    Require-Equal $conversion.artifact.sha256 $tflite.sha256 `
        "$($entry.modelId) conversion SHA-256"
    Require-Equal ([int64]$conversion.artifact.bytes) ([int64]$tflite.byteSize) `
        "$($entry.modelId) conversion byte size"
    Require-Equal $release.artifact.fileName $tflite.fileName `
        "$($entry.modelId) Release filename"
    Require-Equal $release.artifact.sha256 $tflite.sha256 `
        "$($entry.modelId) Release SHA-256"
    Require-Equal ([int64]$release.artifact.byteSize) ([int64]$tflite.byteSize) `
        "$($entry.modelId) Release byte size"
    Require-Equal $release.supportLevel "download-only" `
        "$($entry.modelId) Release support level"
    Require-Equal $release.activationPolicy $entry.activationPolicy `
        "$($entry.modelId) Release activation policy"
    Require-Equal $tflite.releaseAsset.url "$releaseBaseUrl/$($tflite.fileName)" `
        "$($entry.modelId) immutable Release URL"
    if ("$($tflite.fileName).json" -in $reviewedSidecars) {
        throw "Contract-free candidate unexpectedly has a reviewed sidecar: $($entry.modelId)"
    }
    if ($null -eq $desktop -or @($desktop.inputShapeNhwc).Count -ne 4 -or
            @($desktop.outputShapeNhwc).Count -ne 4) {
        throw "Candidate has no static NHWC conversion evidence: $($entry.modelId)"
    }
    $candidateInputs.Add([ordered]@{
        entry = $entry
        tflite = $tflite
        inputName = [string]$desktop.inputName
        outputName = [string]$desktop.outputName
        inputShape = (@($desktop.inputShapeNhwc) -join ',')
        outputShape = (@($desktop.outputShapeNhwc) -join ',')
    })
}

$metadataSummary = [ordered]@{
    schemaVersion = "phase7-candidate-catalog-metadata-v1"
    generatedAtUtc = [DateTime]::UtcNow.ToString("o")
    status = "passed"
    catalog = [ordered]@{
        sha256 = $catalogSha256
        bssTfliteRevision = $bssRevision
        conversionManifestSha256 = $conversionManifestSha256
        artifactCount = @($catalog.artifacts).Count
        reviewedContractCount = @($catalog.contracts).Count
    }
    release = [ordered]@{
        tag = $releaseTag
        manifestUrl = $releaseManifestUrl
        manifestSha256 = $remoteManifestSha256
        sourceRevision = $releaseManifest.sourceRevision
        artifactCount = [int]$releaseManifest.artifactCount
        totalArtifactBytes = [int64]$releaseManifest.totalArtifactBytes
        reviewedSidecars = $reviewedSidecars
    }
    candidateCount = $candidateInputs.Count
    candidates = @(
        foreach ($candidate in $candidateInputs) {
            [ordered]@{
                modelId = $candidate.entry.modelId
                displayName = $candidate.entry.displayName
                activationPolicy = $candidate.entry.activationPolicy
                supportLevel = $candidate.entry.supportLevel
                reviewedContract = $false
                reviewedSidecar = $false
                artifactFileName = $candidate.tflite.fileName
                artifactSha256 = $candidate.tflite.sha256
                artifactBytes = [int64]$candidate.tflite.byteSize
                releaseUrl = $candidate.tflite.releaseAsset.url
                inputName = $candidate.inputName
                inputShapeNhwc = @($candidate.inputShape -split ',' | ForEach-Object { [int]$_ })
                outputName = $candidate.outputName
                outputShapeNhwc = @($candidate.outputShape -split ',' | ForEach-Object { [int]$_ })
            }
        }
    )
}
$metadataSummaryPath = Join-Path $deviceDirectory `
    "phase7-candidate-catalog-metadata.json"
$metadataSummary | ConvertTo-Json -Depth 15 |
    Set-Content -LiteralPath $metadataSummaryPath -Encoding utf8
Write-Host "Saved candidate catalog metadata to $metadataSummaryPath"
if ($MetadataOnly) {
    Write-Host "Phase 7 candidate catalog metadata passed with $($candidateInputs.Count) candidates."
    return
}

$setupRunId = "phase7-candidate-catalog-setup"
$setupArguments = @{
    Serial = $Serial
    ProcessAbi = $ProcessAbi
    ModelId = "uvr_mdxnet_3_9662"
    Stage = "identity"
    RunId = $setupRunId
    OutputRoot = $OutputRoot
    BackendMode = "cpu"
}
if ($SkipBuild) { $setupArguments.SkipBuild = $true }
& $phase7Runner @setupArguments
if ($LASTEXITCODE -ne 0) {
    throw "Phase 7 setup runner exited with code $LASTEXITCODE."
}
$setupInputPath = Join-Path $deviceDirectory "$setupRunId-inputs.json"
if (-not (Test-Path -LiteralPath $setupInputPath -PathType Leaf)) {
    throw "Phase 7 setup input envelope was not created: $setupInputPath"
}
$setupInputs = Get-Content -LiteralPath $setupInputPath -Raw | ConvertFrom-Json
Require-Equal $setupInputs.catalog.sha256 $catalogSha256 "Installed catalog SHA-256"
Require-Equal $setupInputs.catalog.sourceRevision $bssRevision `
    "Installed catalog source revision"

$deviceUserOutput = & $adb -s $Serial shell am get-current-user 2>$null
$deviceUserId = ([string]($deviceUserOutput | Select-Object -First 1)).Trim()
if ($LASTEXITCODE -ne 0 -or $deviceUserId -notmatch '^\d+$') {
    throw "Could not resolve the numeric Android user for $Serial."
}

$reports = [System.Collections.Generic.List[object]]::new()
$failures = [System.Collections.Generic.List[string]]::new()
foreach ($candidate in $candidateInputs) {
    $entry = $candidate.entry
    $tflite = $candidate.tflite
    $runId = "phase7-candidate-$($entry.modelId)"
    Require-SafeName $runId "Candidate run ID"
    Write-Host "Auditing $($entry.modelId) on $Serial..."
    Invoke-Adb shell pm clear $package | Out-Null
    $instrumentArguments = @(
        "shell", "am", "instrument", "--user", $deviceUserId, "-w", "-r",
        "-e", "class", "$testClass#$testMethod",
        "-e", "runId", $runId,
        "-e", "serial", $Serial,
        "-e", "processAbi", $ProcessAbi,
        "-e", "modelId", $entry.modelId,
        "-e", "artifactFileName", $tflite.fileName,
        "-e", "artifactSha256", $tflite.sha256,
        "-e", "artifactBytes", [string]$tflite.byteSize,
        "-e", "releaseTag", $tflite.releaseAsset.tag,
        "-e", "releaseUrl", $tflite.releaseAsset.url,
        "-e", "activationPolicy", $entry.activationPolicy,
        "-e", "inputName", $candidate.inputName,
        "-e", "outputName", $candidate.outputName,
        "-e", "inputShape", $candidate.inputShape,
        "-e", "outputShape", $candidate.outputShape,
        "-e", "appCommit", $setupInputs.appCommit,
        "-e", "appApkSha256", $setupInputs.appApk.sha256,
        "-e", "testApkSha256", $setupInputs.testApk.sha256,
        "-e", "catalogSha256", $setupInputs.catalog.sha256,
        "-e", "catalogSourceRevision", $setupInputs.catalog.sourceRevision,
        "-e", "litertVersion", $setupInputs.runtime.version,
        $testRunner
    )
    $instrumentOutput = & $adb -s $Serial @instrumentArguments 2>&1
    $instrumentExit = $LASTEXITCODE
    $instrumentOutput | ForEach-Object { Write-Host $_ }
    $remoteReport = "files/phase7-validation-reports/$runId-candidate.json"
    $reportText = & $adb -s $Serial exec-out run-as $package cat $remoteReport 2>$null
    $reportExit = $LASTEXITCODE
    if ($reportExit -ne 0 -or $null -eq $reportText) {
        $failures.Add("$($entry.modelId): instrumentation created no report (exit $instrumentExit)")
        continue
    }
    $reportPath = Join-Path $deviceDirectory "$runId-candidate.json"
    ([string[]]$reportText -join "`n") |
        Set-Content -LiteralPath $reportPath -Encoding utf8
    try {
        $report = Get-Content -LiteralPath $reportPath -Raw | ConvertFrom-Json
    } catch {
        $failures.Add("$($entry.modelId): invalid JSON report")
        continue
    }
    $reports.Add([ordered]@{
        modelId = $entry.modelId
        displayName = $entry.displayName
        activationPolicy = $entry.activationPolicy
        artifactFileName = $tflite.fileName
        artifactSha256 = $tflite.sha256
        artifactBytes = [int64]$tflite.byteSize
        reportFile = "$safeSerial/$runId-candidate.json"
        status = $report.status
        device = $report.device
        download = $report.download
        structure = $report.structure
        activation = $report.activation
        cleanup = $report.cleanup
        error = $report.error
    })
    if ($instrumentExit -ne 0 -or $report.status -ne "passed") {
        $failures.Add("$($entry.modelId): $($report.error)")
    }
}
& $adb -s $Serial shell pm clear $package 2>$null | Out-Null

$summary = [ordered]@{
    schemaVersion = "phase7-candidate-catalog-summary-v1"
    generatedAtUtc = [DateTime]::UtcNow.ToString("o")
    status = if ($failures.Count -eq 0 -and $reports.Count -eq $candidateInputs.Count) {
        "passed"
    } else {
        "failed"
    }
    identity = [ordered]@{
        appCommit = $setupInputs.appCommit
        appApkSha256 = $setupInputs.appApk.sha256
        testApkSha256 = $setupInputs.testApk.sha256
        processAbi = $ProcessAbi
        catalogSha256 = $catalogSha256
        catalogSourceRevision = $setupInputs.catalog.sourceRevision
        bssTfliteRevision = $bssRevision
        conversionManifestSha256 = $conversionManifestSha256
        litertVersion = $setupInputs.runtime.version
    }
    release = [ordered]@{
        tag = $releaseTag
        manifestUrl = $releaseManifestUrl
        manifestSha256 = $remoteManifestSha256
        sourceRevision = $releaseManifest.sourceRevision
        artifactCount = [int]$releaseManifest.artifactCount
        totalArtifactBytes = [int64]$releaseManifest.totalArtifactBytes
        reviewedSidecars = $reviewedSidecars
    }
    device = [ordered]@{
        serial = $Serial
        model = if ($reports.Count -gt 0) { $reports[0].device.model } else { $null }
        androidApi = if ($reports.Count -gt 0) { $reports[0].device.androidApi } else { $null }
        processAbi = $ProcessAbi
    }
    expectedCandidateCount = $candidateInputs.Count
    reportCount = $reports.Count
    failures = @($failures)
    candidates = @($reports)
}
$summaryPath = Join-Path $deviceDirectory "phase7-candidate-catalog-summary.json"
$summary | ConvertTo-Json -Depth 20 |
    Set-Content -LiteralPath $summaryPath -Encoding utf8
Write-Host "Saved candidate catalog summary to $summaryPath"
if ($summary.status -ne "passed") {
    throw "Phase 7 candidate catalog failed: $($failures -join '; ')"
}
Write-Host "Phase 7 candidate catalog passed with $($reports.Count) reports."
