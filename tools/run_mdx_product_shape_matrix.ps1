param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,

    [ValidateSet("cpu", "gpu")]
    [string]$BackendMode = "cpu",

    [string]$BssTfliteRoot = "C:\Users\User\Documents\BSSModels\bss-tflite",

    [string]$MatrixFile =
        "C:\Users\User\Documents\MusicSourceSeparation\data\mdx-managed-buffer-shape-matrix-v1.json",

    [string]$AppApk =
        "$PSScriptRoot\..\app\build\outputs\apk\fdroid\debug\BoomingSS-1.3.1-beta.2-ss.1.debug-fdroid-universal.apk",

    [string]$TestApk =
        "$PSScriptRoot\..\app\build\outputs\apk\androidTest\fdroid\debug\app-fdroid-debug-androidTest.apk",

    [string]$RunId = "mdx-product-all13-$BackendMode-$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())",

    [switch]$SkipInstall,
    [switch]$SkipStage,
    [switch]$KeepStage
)

$ErrorActionPreference = "Stop"
$package = "com.wluhwluh.booming.sourcesep.debug"
$runner = "$package.test/androidx.test.runner.AndroidJUnitRunner"
$testClass =
    "com.mardous.booming.separation.model.litert.MdxLiteRtProductShapeMatrixDeviceTest"
$stagingPath = "files/mdx-product-shape-matrix"
$temporaryRoot = "/data/local/tmp/bss-mdx-product-all13-$([guid]::NewGuid().ToString('N'))"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$reportDirectory = Join-Path $repoRoot (
    "build\litert-validation\mdx-product-shapes\" + $Serial.Replace(":", "_")
)
$localReport = Join-Path $reportDirectory "$RunId.json"

if ($RunId -notmatch '^[A-Za-z0-9._-]{1,160}$') {
    throw "Unsafe run ID."
}
$matrix = Get-Content -LiteralPath $MatrixFile -Raw | ConvertFrom-Json
if ($matrix.schemaVersion -ne 1 -or $matrix.models.Count -ne 13) {
    throw "Expected the frozen 13-shape MDX matrix."
}

function Invoke-Adb {
    & adb -s $Serial @args
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed with exit code ${LASTEXITCODE}: $args"
    }
}

try {
    if ((& adb -s $Serial get-state).Trim() -ne "device") {
        throw "Device is not ready: $Serial"
    }
    if (-not $SkipInstall) {
        Invoke-Adb install -r -t $AppApk
        Invoke-Adb install -r -t $TestApk
    }
    $processAbi = (& adb -s $Serial shell getprop ro.product.cpu.abi).Trim()
    if ($processAbi -ne "arm64-v8a") {
        throw "This product matrix currently requires an arm64 process, got $processAbi."
    }

    if (-not $SkipStage) {
        Invoke-Adb shell rm -rf $temporaryRoot
        Invoke-Adb shell mkdir -p $temporaryRoot
        Invoke-Adb shell chmod 777 $temporaryRoot
        Invoke-Adb shell run-as $package rm -rf $stagingPath
        Invoke-Adb shell run-as $package mkdir -p $stagingPath
        foreach ($entry in $matrix.models) {
            $modelPath = Join-Path $BssTfliteRoot (
                "artifacts\all-candidates-fp32\" + $entry.modelFile
            )
            if (-not (Test-Path -LiteralPath $modelPath -PathType Leaf)) {
                throw "Missing frozen model asset: $modelPath"
            }
            $localSha = (Get-FileHash -LiteralPath $modelPath -Algorithm SHA256).Hash.ToLowerInvariant()
            if ($localSha -ne $entry.sha256) {
                throw "Frozen model hash mismatch: $($entry.modelId)"
            }
            Invoke-Adb push $modelPath "$temporaryRoot/$($entry.modelFile)"
            Invoke-Adb shell run-as $package cp "$temporaryRoot/$($entry.modelFile)" "$stagingPath/$($entry.modelFile)"
            $remoteHashLine = (& adb -s $Serial shell run-as $package sha256sum "$stagingPath/$($entry.modelFile)") -join " "
            if ($LASTEXITCODE -ne 0 -or
                ($remoteHashLine -split '\s+')[0].ToLowerInvariant() -ne $entry.sha256) {
                throw "Staged model hash mismatch: $($entry.modelId)"
            }
            Invoke-Adb shell rm "$temporaryRoot/$($entry.modelFile)"
        }
    }

    Invoke-Adb shell am instrument -w -r -e class $testClass -e runId $RunId -e backendMode $BackendMode -e processAbi $processAbi $runner

    New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null
    $remoteReport = "files/source-separation/mdx-product-shape-matrix-reports/$RunId.json"
    $reportText = (& adb -s $Serial exec-out run-as $package cat $remoteReport) -join "`n"
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($reportText)) {
        throw "Unable to read the product shape-matrix report."
    }
    [IO.File]::WriteAllText(
        $localReport,
        $reportText,
        [Text.UTF8Encoding]::new($false)
    )
    $report = Get-Content -LiteralPath $localReport -Raw | ConvertFrom-Json
    if ($report.status -ne "complete" -or $report.completedShapes -ne 13 -or
        $report.runtime.artifactVersion -ne "2.2.0-bss.2") {
        throw "The product shape matrix did not qualify: $localReport"
    }
    [pscustomobject]@{
        Serial = $Serial
        RunId = $RunId
        Backend = $BackendMode
        CompletedShapes = $report.completedShapes
        PssGrowthBytes = $report.memory.pssGrowthBytes
        NativeHeapGrowthBytes = $report.memory.nativeHeapGrowthBytes
        Report = (Resolve-Path -LiteralPath $localReport).Path
    }
} finally {
    & adb -s $Serial shell rm -rf $temporaryRoot 2>$null | Out-Null
    if (-not $KeepStage) {
        & adb -s $Serial shell run-as $package rm -rf $stagingPath 2>$null | Out-Null
    }
}
