param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,

    [Parameter(Mandatory = $true)]
    [ValidateSet("arm64-v8a", "armeabi-v7a", "x86_64", "x86")]
    [string]$ProcessAbi,

    [Parameter(Mandatory = $true)]
    [string]$ModelId,

    [string]$ModelPath = "",
    [string]$InputPath = "",
    [string]$ReferencePath = "",
    [string]$FixtureName = "coast_town_window0",
    [string]$RunId = "",
    [string]$SecondaryModelId = "",
    [string]$SecondaryModelPath = "",
    [string]$OutputRoot = "",
    [switch]$TestInFlightCancellation,
    [switch]$PreflightOnly,
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$package = "com.wluhwluh.booming.sourcesep.debug"
$runner = "com.wluhwluh.booming.sourcesep.debug.test/androidx.test.runner.AndroidJUnitRunner"
$testClass = "com.mardous.booming.separation.model.litert.MdxLiteRtCpuValidationTest"
$adb = (Get-Command adb -ErrorAction Stop).Source

if ([string]::IsNullOrWhiteSpace($RunId)) {
    $safeSerial = $Serial -replace '[^A-Za-z0-9._-]', '_'
    $RunId = "{0}-{1}-{2}-{3:yyyyMMdd-HHmmss}" -f $safeSerial, $ProcessAbi, $ModelId, (Get-Date)
}
if ($RunId -notmatch '^[A-Za-z0-9._-]{1,120}$') {
    throw "RunId contains unsupported characters: $RunId"
}
if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $OutputRoot = Join-Path $repoRoot "build\litert-validation\reports"
}

function Invoke-Adb {
    & $adb -s $Serial @args
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed with exit code ${LASTEXITCODE}: $args"
    }
}

function Require-File([string]$Path, [string]$Role) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "$Role file not found: $Path"
    }
    return (Resolve-Path -LiteralPath $Path).Path
}

if (-not $PreflightOnly) {
    $ModelPath = Require-File $ModelPath "Model"
    $InputPath = Require-File $InputPath "Input"
    $ReferencePath = Require-File $ReferencePath "Reference"
}
if (-not [string]::IsNullOrWhiteSpace($SecondaryModelId)) {
    $SecondaryModelPath = Require-File $SecondaryModelPath "Secondary model"
}

Push-Location $repoRoot
try {
    if (-not $SkipBuild) {
        & .\gradlew.bat assembleGithubDebugAndroidTest --console=plain
        if ($LASTEXITCODE -ne 0) { throw "AndroidTest assembly failed." }
        & .\gradlew.bat assembleGithubDebug --console=plain
        if ($LASTEXITCODE -ne 0) { throw "App assembly failed." }
    }

    $appApk = Get-ChildItem "app\build\outputs\apk\github\debug" -Filter "*-$ProcessAbi.apk" |
        Select-Object -First 1
    if ($null -eq $appApk) { throw "No $ProcessAbi app APK was found." }
    $testApk = Get-ChildItem "app\build\outputs\apk\androidTest\github\debug" -Filter "*.apk" |
        Select-Object -First 1
    if ($null -eq $testApk) { throw "No AndroidTest APK was found." }

    Invoke-Adb install -r -t $appApk.FullName
    Invoke-Adb install -r -t $testApk.FullName
    Invoke-Adb shell am force-stop $package
    & $adb -s $Serial shell monkey -p $package 1 2>$null | Out-Null
    Start-Sleep -Milliseconds 750
    & $adb -s $Serial shell input keyevent KEYCODE_HOME 2>$null | Out-Null

    $remoteRoot = "/sdcard/Android/data/$package/files/litert-validation-staging/$RunId"
    Invoke-Adb shell rm -rf $remoteRoot
    Invoke-Adb shell mkdir -p $remoteRoot
    & $adb -s $Serial shell run-as $package rm -rf "cache/litert-validation/$RunId" 2>$null | Out-Null
    $instrumentArguments = @(
        "-e", "class", "$testClass#validateStagedModel",
        "-e", "runId", $RunId,
        "-e", "modelId", $ModelId,
        "-e", "fixtureName", $FixtureName,
        "-e", "processAbi", $ProcessAbi,
        "-e", "appCommit", (git rev-parse HEAD),
        "-e", "testInFlightCancellation", $TestInFlightCancellation.IsPresent.ToString().ToLowerInvariant(),
        "-e", "preflightOnly", $PreflightOnly.IsPresent.ToString().ToLowerInvariant()
    )

    if (-not $PreflightOnly) {
        $remoteModel = "$remoteRoot/$(Split-Path -Leaf $ModelPath)"
        $remoteInput = "$remoteRoot/$(Split-Path -Leaf $InputPath)"
        $remoteReference = "$remoteRoot/$(Split-Path -Leaf $ReferencePath)"
        Invoke-Adb push $ModelPath $remoteModel
        Invoke-Adb push $InputPath $remoteInput
        Invoke-Adb push $ReferencePath $remoteReference
        $instrumentArguments += @(
            "-e", "modelPath", $remoteModel,
            "-e", "inputPath", $remoteInput,
            "-e", "inputSha256", (Get-FileHash -LiteralPath $InputPath -Algorithm SHA256).Hash.ToLowerInvariant(),
            "-e", "referencePath", $remoteReference,
            "-e", "referenceSha256", (Get-FileHash -LiteralPath $ReferencePath -Algorithm SHA256).Hash.ToLowerInvariant()
        )
    }
    if (-not [string]::IsNullOrWhiteSpace($SecondaryModelId)) {
        $remoteSecondary = "$remoteRoot/$(Split-Path -Leaf $SecondaryModelPath)"
        Invoke-Adb push $SecondaryModelPath $remoteSecondary
        $instrumentArguments += @(
            "-e", "secondaryModelId", $SecondaryModelId,
            "-e", "secondaryModelPath", $remoteSecondary
        )
    }

    Invoke-Adb shell am force-stop $package
    $instrumentOutput = & $adb -s $Serial shell am instrument -w -r @instrumentArguments $runner
    $instrumentExit = $LASTEXITCODE
    $instrumentText = $instrumentOutput -join "`n"
    Write-Host $instrumentText

    $localDirectory = Join-Path $OutputRoot (($Serial -replace '[^A-Za-z0-9._-]', '_'))
    New-Item -ItemType Directory -Force -Path $localDirectory | Out-Null
    $localReport = Join-Path $localDirectory "$RunId.json"
    $reportText = & $adb -s $Serial exec-out run-as $package cat "cache/litert-validation/$RunId/report.json"
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace(($reportText -join "`n"))) {
        throw "Validation report could not be read from the app cache."
    }
    $reportText -join "`n" | Set-Content -LiteralPath $localReport -Encoding utf8
    Invoke-Adb shell rm -rf $remoteRoot

    if ($instrumentExit -ne 0 -or $instrumentText -notmatch 'OK \(1 test\)') {
        throw "Instrumentation validation failed. Report: $localReport"
    }
    Write-Host "Saved validation report to $localReport"
} finally {
    Pop-Location
}
