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
    [int]$ProcessorCountOverride = 0,
    [switch]$TestInFlightCancellation,
    [switch]$AllowUnsupportedResourceProbe,
    [switch]$PreflightOnly,
    [switch]$SkipBuild,
    [switch]$SkipInstall
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

function Require-SafeLeafName([string]$Path, [string]$Role) {
    $leafName = Split-Path -Leaf $Path
    if ($leafName -notmatch '^[A-Za-z0-9._-]+$') {
        throw "$Role filename contains unsupported characters: $leafName"
    }
    return $leafName
}

if (-not $PreflightOnly) {
    $ModelPath = Require-File $ModelPath "Model"
    $InputPath = Require-File $InputPath "Input"
    $ReferencePath = Require-File $ReferencePath "Reference"
}
if (-not [string]::IsNullOrWhiteSpace($SecondaryModelId)) {
    $SecondaryModelPath = Require-File $SecondaryModelPath "Secondary model"
}
if ($PreflightOnly -and $AllowUnsupportedResourceProbe) {
    throw "AllowUnsupportedResourceProbe cannot be combined with PreflightOnly."
}

$remoteRelativeRoot = ""
$remoteTempRoot = ""
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

    if (-not $SkipInstall) {
        Invoke-Adb install -r -t $appApk.FullName
        Invoke-Adb install -r -t $testApk.FullName
    }
    Invoke-Adb shell am force-stop $package
    & $adb -s $Serial shell monkey -p $package 1 2>$null | Out-Null
    Start-Sleep -Milliseconds 750
    & $adb -s $Serial shell input keyevent KEYCODE_HOME 2>$null | Out-Null

    $appDataRoot = (& $adb -s $Serial shell run-as $package pwd) -join "`n"
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($appDataRoot)) {
        throw "Could not resolve the app data directory with run-as."
    }
    $appDataRoot = $appDataRoot.Trim()
    if ($appDataRoot -notmatch '^/data/(data|user/[0-9]+)/[A-Za-z0-9._-]+$') {
        throw "Unexpected app data directory: $appDataRoot"
    }
    $remoteRelativeRoot = "files/litert-validation-staging/$RunId"
    $remoteTempRoot = "/data/local/tmp/booming-ss-litert-validation-$RunId"
    Invoke-Adb shell run-as $package rm -rf $remoteRelativeRoot
    Invoke-Adb shell run-as $package mkdir -p $remoteRelativeRoot
    Invoke-Adb shell rm -rf $remoteTempRoot
    Invoke-Adb shell mkdir -p $remoteTempRoot
    & $adb -s $Serial shell run-as $package rm -rf "cache/litert-validation/$RunId" 2>$null | Out-Null
    $instrumentArguments = @(
        "-e", "class", "$testClass#validateStagedModel",
        "-e", "runId", $RunId,
        "-e", "modelId", $ModelId,
        "-e", "fixtureName", $FixtureName,
        "-e", "processAbi", $ProcessAbi,
        "-e", "appCommit", (git rev-parse HEAD),
        "-e", "testInFlightCancellation", $TestInFlightCancellation.IsPresent.ToString().ToLowerInvariant(),
        "-e", "allowUnsupportedResourceProbe", $AllowUnsupportedResourceProbe.IsPresent.ToString().ToLowerInvariant(),
        "-e", "preflightOnly", $PreflightOnly.IsPresent.ToString().ToLowerInvariant()
    )

    if (-not $PreflightOnly) {
        $modelLeaf = Require-SafeLeafName $ModelPath "Model"
        $inputLeaf = Require-SafeLeafName $InputPath "Input"
        $referenceLeaf = Require-SafeLeafName $ReferencePath "Reference"
        $remoteModel = "$appDataRoot/$remoteRelativeRoot/$modelLeaf"
        $remoteInput = "$appDataRoot/$remoteRelativeRoot/$inputLeaf"
        $remoteReference = "$appDataRoot/$remoteRelativeRoot/$referenceLeaf"
        foreach ($stagedFile in @(
            @{ Local = $ModelPath; Leaf = $modelLeaf },
            @{ Local = $InputPath; Leaf = $inputLeaf },
            @{ Local = $ReferencePath; Leaf = $referenceLeaf }
        )) {
            $temporaryPath = "$remoteTempRoot/$($stagedFile.Leaf)"
            $relativePath = "$remoteRelativeRoot/$($stagedFile.Leaf)"
            Invoke-Adb push $stagedFile.Local $temporaryPath
            Invoke-Adb shell chmod 644 $temporaryPath
            Invoke-Adb shell run-as $package cp $temporaryPath $relativePath
            Invoke-Adb shell run-as $package ls -l $relativePath
        }
        $instrumentArguments += @(
            "-e", "modelPath", $remoteModel,
            "-e", "inputPath", $remoteInput,
            "-e", "inputSha256", (Get-FileHash -LiteralPath $InputPath -Algorithm SHA256).Hash.ToLowerInvariant(),
            "-e", "referencePath", $remoteReference,
            "-e", "referenceSha256", (Get-FileHash -LiteralPath $ReferencePath -Algorithm SHA256).Hash.ToLowerInvariant()
        )
    }
    if (-not [string]::IsNullOrWhiteSpace($SecondaryModelId)) {
        $secondaryLeaf = Require-SafeLeafName $SecondaryModelPath "Secondary model"
        $temporarySecondary = "$remoteTempRoot/$secondaryLeaf"
        $relativeSecondary = "$remoteRelativeRoot/$secondaryLeaf"
        $remoteSecondary = "$appDataRoot/$relativeSecondary"
        Invoke-Adb push $SecondaryModelPath $temporarySecondary
        Invoke-Adb shell chmod 644 $temporarySecondary
        Invoke-Adb shell run-as $package cp $temporarySecondary $relativeSecondary
        Invoke-Adb shell run-as $package ls -l $relativeSecondary
        $instrumentArguments += @(
            "-e", "secondaryModelId", $SecondaryModelId,
            "-e", "secondaryModelPath", $remoteSecondary
        )
    }
    if ($ProcessorCountOverride -gt 0) {
        $instrumentArguments += @(
            "-e", "processorCountOverride", $ProcessorCountOverride
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

    if ($instrumentExit -ne 0 -or $instrumentText -notmatch 'OK \(1 test\)') {
        throw "Instrumentation validation failed. Report: $localReport"
    }
    Write-Host "Saved validation report to $localReport"
} finally {
    if (-not [string]::IsNullOrWhiteSpace($remoteRelativeRoot)) {
        & $adb -s $Serial shell run-as $package rm -rf $remoteRelativeRoot 2>$null | Out-Null
    }
    if (-not [string]::IsNullOrWhiteSpace($remoteTempRoot)) {
        & $adb -s $Serial shell rm -rf $remoteTempRoot 2>$null | Out-Null
    }
    Pop-Location
}
