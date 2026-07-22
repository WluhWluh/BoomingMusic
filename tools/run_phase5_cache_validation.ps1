param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,

    [Parameter(Mandatory = $true)]
    [ValidateSet("arm64-v8a", "armeabi-v7a", "x86_64", "x86")]
    [string]$ProcessAbi,

    [Parameter(Mandatory = $true)]
    [string]$PrimaryModelPath,

    [string]$PrimaryModelId = "uvr_mdxnet_3_9662",

    [string]$SecondaryModelPath = "",

    [string]$SecondaryModelId = "",

    [Parameter(Mandatory = $true)]
    [string]$SourcePath,

    [string]$RunId = "",

    [string]$OutputRoot = "",

    [switch]$SkipBuild,
    [switch]$SkipClearRecovery
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$package = "com.wluhwluh.booming.sourcesep.debug"
$runner = "$package.test/androidx.test.runner.AndroidJUnitRunner"
$testClass = "com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCacheDeviceTest"
$adb = (Get-Command adb -ErrorAction Stop).Source

if ([string]::IsNullOrWhiteSpace($RunId)) {
    $safeSerial = $Serial -replace '[^A-Za-z0-9._-]', '_'
    $RunId = "{0}-{1}-{2:yyyyMMdd-HHmmss}" -f $safeSerial, $ProcessAbi, (Get-Date)
}
if ($RunId -notmatch '^[A-Za-z0-9._-]{1,120}$') {
    throw "RunId contains unsupported characters: $RunId"
}
if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $OutputRoot = Join-Path $repoRoot "build\phase5-cache-validation"
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
    $leaf = Split-Path -Leaf $Path
    if ($leaf -notmatch '^[A-Za-z0-9._-]+$') {
        throw "$Role filename contains unsupported characters: $leaf"
    }
    return $leaf
}

function Read-Report([string]$RelativePath, [string]$LocalName) {
    $text = & $adb -s $Serial exec-out run-as $package cat $RelativePath
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace(($text -join "`n"))) {
        throw "Could not read validation report: $RelativePath"
    }
    $directory = Join-Path $OutputRoot ($Serial -replace '[^A-Za-z0-9._-]', '_')
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    $path = Join-Path $directory $LocalName
    $text -join "`n" | Set-Content -LiteralPath $path -Encoding utf8
    Write-Host "Saved Phase 5 validation report to $path"
}

$PrimaryModelPath = Require-File $PrimaryModelPath "Primary model"
$SourcePath = Require-File $SourcePath "Source audio"
if (-not [string]::IsNullOrWhiteSpace($SecondaryModelId)) {
    $SecondaryModelPath = Require-File $SecondaryModelPath "Secondary model"
} elseif (-not [string]::IsNullOrWhiteSpace($SecondaryModelPath)) {
    throw "SecondaryModelId is required when SecondaryModelPath is provided."
}

$remoteRelativeRoot = ""
$remoteTempRoot = ""
Push-Location $repoRoot
try {
    if (-not $SkipBuild) {
        & .\gradlew.bat :app:assembleGithubDebugAndroidTest --console=plain
        if ($LASTEXITCODE -ne 0) { throw "Phase 5 AndroidTest assembly failed." }
        & .\gradlew.bat :app:assembleGithubDebug --console=plain
        if ($LASTEXITCODE -ne 0) { throw "Phase 5 app assembly failed." }
    }

    $appApk = Get-ChildItem "app\build\outputs\apk\github\debug" -Filter "*-${ProcessAbi}.apk" |
        Select-Object -First 1
    $testApk = Get-ChildItem "app\build\outputs\apk\androidTest\github\debug" -Filter "*.apk" |
        Select-Object -First 1
    if ($null -eq $appApk) { throw "No $ProcessAbi app APK was found." }
    if ($null -eq $testApk) { throw "No GitHub debug AndroidTest APK was found." }

    Invoke-Adb install -r -t $appApk.FullName
    Invoke-Adb install -r -t $testApk.FullName
    Invoke-Adb shell pm clear $package

    $appDataRoot = (& $adb -s $Serial shell run-as $package pwd) -join "`n"
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($appDataRoot)) {
        throw "Could not resolve the debug app data directory with run-as."
    }
    $appDataRoot = $appDataRoot.Trim()
    if ($appDataRoot -notmatch '^/data/(data|user/[0-9]+)/[A-Za-z0-9._-]+$') {
        throw "Unexpected app data directory: $appDataRoot"
    }

    $remoteRelativeRoot = "files/phase5-validation-staging/$RunId"
    $remoteTempRoot = "/data/local/tmp/booming-ss-phase5-$RunId"
    Invoke-Adb shell run-as $package mkdir -p $remoteRelativeRoot
    Invoke-Adb shell rm -rf $remoteTempRoot
    Invoke-Adb shell mkdir -p $remoteTempRoot

    $staged = @(
        @{ Role = "Primary model"; Local = $PrimaryModelPath; Argument = "primaryModelPath" },
        @{ Role = "Source audio"; Local = $SourcePath; Argument = "sourcePath" }
    )
    if (-not [string]::IsNullOrWhiteSpace($SecondaryModelId)) {
        $staged += @{
            Role = "Secondary model"
            Local = $SecondaryModelPath
            Argument = "secondaryModelPath"
        }
    }

    $instrumentArguments = @(
        "-e", "class", "$testClass#validateModelAwareCacheLifecycle",
        "-e", "runId", $RunId,
        "-e", "processAbi", $ProcessAbi,
        "-e", "primaryModelId", $PrimaryModelId
    )
    foreach ($item in $staged) {
        $leaf = Require-SafeLeafName $item.Local $item.Role
        $temporary = "$remoteTempRoot/$leaf"
        $relative = "$remoteRelativeRoot/$leaf"
        Invoke-Adb push $item.Local $temporary
        Invoke-Adb shell chmod 644 $temporary
        Invoke-Adb shell run-as $package cp $temporary $relative
        $instrumentArguments += @("-e", $item.Argument, "$appDataRoot/$relative")
    }
    if (-not [string]::IsNullOrWhiteSpace($SecondaryModelId)) {
        $instrumentArguments += @("-e", "secondaryModelId", $SecondaryModelId)
    }

    Invoke-Adb shell am force-stop $package
    $lifecycleOutput = & $adb -s $Serial shell am instrument -w -r @instrumentArguments $runner
    $lifecycleExit = $LASTEXITCODE
    $lifecycleText = $lifecycleOutput -join "`n"
    Write-Host $lifecycleText
    Read-Report "files/phase5-validation-reports/$RunId-lifecycle.json" "$RunId-lifecycle.json"
    if ($lifecycleExit -ne 0 -or $lifecycleText -notmatch 'OK \(1 test\)') {
        throw "Phase 5 lifecycle instrumentation failed."
    }

    if (-not $SkipClearRecovery) {
        Invoke-Adb shell am force-stop $package
        $clearOutput = & $adb -s $Serial shell am instrument -w -r `
            -e class "$testClass#validateCacheClearRecovery" `
            -e runId $RunId `
            $runner
        $clearExit = $LASTEXITCODE
        $clearText = $clearOutput -join "`n"
        Write-Host $clearText
        Read-Report "files/phase5-validation-reports/$RunId-clear-cache.json" "$RunId-clear-cache.json"
        if ($clearExit -ne 0 -or $clearText -notmatch 'OK \(1 test\)') {
            throw "Phase 5 clear-cache instrumentation failed."
        }
    }
} finally {
    if (-not [string]::IsNullOrWhiteSpace($remoteRelativeRoot)) {
        & $adb -s $Serial shell run-as $package rm -rf $remoteRelativeRoot 2>$null | Out-Null
    }
    if (-not [string]::IsNullOrWhiteSpace($remoteTempRoot)) {
        & $adb -s $Serial shell rm -rf $remoteTempRoot 2>$null | Out-Null
    }
    Pop-Location
}
