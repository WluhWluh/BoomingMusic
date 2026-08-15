param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,

    [Parameter(Mandatory = $true)]
    [ValidateSet("arm64-v8a", "armeabi-v7a", "x86_64", "x86")]
    [string]$ProcessAbi,

    [ValidateSet("cpu", "gpu", "auto-fallback")]
    [string]$Backend = "cpu",

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
    [ValidateSet(
        "gpu-opencl-bounded-fp32-v1",
        "gpu-auto-fp32-v1",
        "gpu-auto-fp16-v1",
        "gpu-opencl-fp32-v1",
        "gpu-opencl-low-fp32-v1",
        "gpu-opengl-fp32-v1"
    )]
    [string]$GpuProfileId = "gpu-auto-fp32-v1",
    [ValidateSet("setup", "probe", "invocation", "output-read")]
    [string]$GpuFailpoint = "invocation",
    [int]$ProcessorCountOverride = 0,
    [switch]$TestInFlightCancellation,
    [switch]$AllowUnsupportedResourceProbe,
    [switch]$PreflightOnly,
    [switch]$SkipBuild,
    [switch]$SkipInstall,
    [switch]$SkipRuntimeInstall,
    [string]$RuntimeReleaseTag = "downloadable-runtime-v2.2.0-bss.2-exp.1"
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$package = "com.wluhwluh.booming.sourcesep.debug"
$runner = "com.wluhwluh.booming.sourcesep.debug.test/androidx.test.runner.AndroidJUnitRunner"
$testClass = "com.mardous.booming.separation.model.litert.MdxLiteRtCpuValidationTest"
$testMethod = switch ($Backend) {
    "cpu" { "validateStagedModel" }
    "gpu" { "validateStagedGpuModel" }
    "auto-fallback" { "validateStagedGpuAutoFallback" }
}
$adb = (Get-Command adb -ErrorAction Stop).Source

if ([string]::IsNullOrWhiteSpace($RunId)) {
    $safeSerial = $Serial -replace '[^A-Za-z0-9._-]', '_'
    $RunId = "{0}-{1}-{2}-{3}-{4:yyyyMMdd-HHmmss}" -f `
        $safeSerial, $ProcessAbi, $Backend, $ModelId, (Get-Date)
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

function Get-Sha256([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
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

function Assert-InstalledApk([string]$PackageName, [string]$ExpectedSha256) {
    $actualSha256 = Get-InstalledApkSha256 $PackageName
    if ($actualSha256 -ne $ExpectedSha256) {
        throw ("Installed APK hash mismatch for {0}: expected {1}, actual {2}." -f
            $PackageName, $ExpectedSha256, $actualSha256)
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
if ($Backend -ne "cpu" -and $AllowUnsupportedResourceProbe) {
    throw "AllowUnsupportedResourceProbe is CPU-only."
}
if ($Backend -ne "cpu" -and $ProcessorCountOverride -gt 0) {
    throw "ProcessorCountOverride is CPU-only."
}
if ($Backend -eq "auto-fallback" -and $PreflightOnly) {
    throw "Auto fallback validation requires staged model and fixture files."
}
if ($Backend -eq "auto-fallback" -and $ProcessAbi -ne "arm64-v8a") {
    throw "Connected Auto fallback validation currently requires arm64-v8a."
}
if ($Backend -eq "auto-fallback" -and -not [string]::IsNullOrWhiteSpace($SecondaryModelId)) {
    throw "Auto fallback validation does not run session replacement."
}

$remoteRelativeRoot = ""
$remoteTempRoot = ""
$x86ProcessValidation = $ProcessAbi -eq "x86"
$arm32ResidentProcessValidation = $ProcessAbi -eq "armeabi-v7a"
$validationGradleProperties = @()
if ($x86ProcessValidation) {
    $validationGradleProperties += "-PboomingSs.x86ProcessValidation=true"
}
if ($arm32ResidentProcessValidation) {
    $validationGradleProperties += "-PboomingSs.arm32ResidentProcessValidation=true"
}
$sourceCommitAtInvocation = (& git -C $repoRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $sourceCommitAtInvocation -notmatch '^[0-9a-f]{40}$') {
    throw "Could not resolve the source commit for the LiteRT validation build."
}
Push-Location $repoRoot
try {
    if (-not $SkipBuild) {
        & .\gradlew.bat assembleGithubDebugAndroidTest `
            @validationGradleProperties --console=plain
        if ($LASTEXITCODE -ne 0) { throw "AndroidTest assembly failed." }
        & .\gradlew.bat assembleGithubDebug `
            @validationGradleProperties --console=plain
        if ($LASTEXITCODE -ne 0) { throw "App assembly failed." }
    }

    $appApk = Get-ChildItem "app\build\outputs\apk\github\debug" -Filter "*-$ProcessAbi.apk" |
        Select-Object -First 1
    if ($null -eq $appApk) { throw "No $ProcessAbi app APK was found." }
    $testApk = Get-ChildItem "app\build\outputs\apk\androidTest\github\debug" -Filter "*.apk" |
        Select-Object -First 1
    if ($null -eq $testApk) { throw "No AndroidTest APK was found." }

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
        if ([bool]$buildIdentity.x86ProcessValidation -ne $x86ProcessValidation -or
                [bool]$buildIdentity.arm32ResidentProcessValidation -ne
                    $arm32ResidentProcessValidation) {
            throw "The selected APK used different process-validation gates."
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
            x86ProcessValidation = $x86ProcessValidation
            arm32ResidentProcessValidation = $arm32ResidentProcessValidation
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

    if ($SkipInstall) {
        Assert-InstalledApk $package $appApkSha256
        Assert-InstalledApk ($package + ".test") $testApkSha256
    } else {
        Invoke-Adb install -r -t $appApk.FullName
        Invoke-Adb install -r -t $testApk.FullName
    }
    if (-not $SkipRuntimeInstall) {
        & (Join-Path $PSScriptRoot "install_litert_cpu_runtime.ps1") `
            -Serial $Serial `
            -Package $package `
            -ProcessAbi $ProcessAbi `
            -ReleaseTag $RuntimeReleaseTag
        if ($LASTEXITCODE -ne 0) {
            throw "LiteRT CPU runtime provisioning failed."
        }
    }
    Invoke-Adb shell am force-stop $package
    if (-not $PreflightOnly) {
        & $adb -s $Serial shell monkey -p $package 1 2>$null | Out-Null
        Start-Sleep -Milliseconds 750
        & $adb -s $Serial shell input keyevent KEYCODE_HOME 2>$null | Out-Null
    }

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
        "-e", "class", "$testClass#$testMethod",
        "-e", "runId", $RunId,
        "-e", "modelId", $ModelId,
        "-e", "fixtureName", $FixtureName,
        "-e", "processAbi", $ProcessAbi,
        "-e", "appCommit", $appCommit,
        "-e", "appApkSha256", $appApkSha256,
        "-e", "testApkSha256", $testApkSha256,
        "-e", "testInFlightCancellation", $TestInFlightCancellation.IsPresent.ToString().ToLowerInvariant(),
        "-e", "allowUnsupportedResourceProbe", $AllowUnsupportedResourceProbe.IsPresent.ToString().ToLowerInvariant(),
        "-e", "preflightOnly", $PreflightOnly.IsPresent.ToString().ToLowerInvariant()
    )
    if ($Backend -ne "cpu") {
        $instrumentArguments += @(
            "-e", "gpuProfileId", $GpuProfileId
        )
    }
    if ($Backend -eq "auto-fallback") {
        $instrumentArguments += @(
            "-e", "gpuFailpoint", $GpuFailpoint
        )
    }

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
