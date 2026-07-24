param(
    [string]$ApkDirectory = "app\build\outputs\apk\github\debug",
    [string]$OutputPath = "build\phase7-apk-inventory.json",
    [string]$PackageName = "com.wluhwluh.booming.sourcesep.debug",
    [string[]]$InstalledTarget = @()
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$adb = (Get-Command adb -ErrorAction Stop).Source
$apkRoot = (Resolve-Path (Join-Path $repoRoot $ApkDirectory)).Path
$buildIdentityPath = Join-Path $apkRoot "phase7-build-identity-v1.json"
$variantOrder = @("arm64-v8a", "armeabi-v7a", "x86_64", "x86", "universal")

function Get-Sha256([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-ZipEntrySha256([System.IO.Compression.ZipArchiveEntry]$Entry) {
    $algorithm = [System.Security.Cryptography.SHA256]::Create()
    $stream = $Entry.Open()
    try {
        $bytes = $algorithm.ComputeHash($stream)
        return ([System.BitConverter]::ToString($bytes) -replace '-', '').ToLowerInvariant()
    } finally {
        $stream.Dispose()
        $algorithm.Dispose()
    }
}

function Get-Variant([string]$FileName) {
    $stem = [System.IO.Path]::GetFileNameWithoutExtension($FileName)
    foreach ($variant in $variantOrder) {
        if ($stem.EndsWith("-$variant", [System.StringComparison]::Ordinal)) {
            return $variant
        }
    }
    return $null
}

function Invoke-AdbText([string]$Serial, [string[]]$Arguments) {
    $output = & $adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed for $Serial with exit code ${LASTEXITCODE}: $Arguments`n$output"
    }
    return (($output | ForEach-Object { [string]$_ }) -join "`n").Trim()
}

if (-not (Test-Path -LiteralPath $buildIdentityPath -PathType Leaf)) {
    throw "Phase 7 build identity is missing: $buildIdentityPath"
}
$buildIdentity = Get-Content -LiteralPath $buildIdentityPath -Raw | ConvertFrom-Json
if ($buildIdentity.schemaVersion -ne "phase7-build-identity-v1" -or
        [string]$buildIdentity.appCommit -notmatch '^[0-9a-f]{40}$') {
    throw "Phase 7 build identity is invalid: $buildIdentityPath"
}

Push-Location $repoRoot
try {
    & python tools/verify_litert_apks.py $apkRoot
    if ($LASTEXITCODE -ne 0) {
        throw "LiteRT APK inventory verification failed."
    }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $apkRecords = [System.Collections.Generic.List[object]]::new()
    foreach ($apk in Get-ChildItem -LiteralPath $apkRoot -Filter "*.apk" | Sort-Object Name) {
        $variant = Get-Variant $apk.Name
        if ($null -eq $variant) { continue }
        $apkSha256 = Get-Sha256 $apk.FullName
        $recordedIdentity = @($buildIdentity.appApks) | Where-Object {
            $_.fileName -eq $apk.Name
        } | Select-Object -First 1
        if ($null -eq $recordedIdentity -or
                $recordedIdentity.sha256 -ne $apkSha256 -or
                [int64]$recordedIdentity.bytes -ne [int64]$apk.Length) {
            throw "APK does not match the Phase 7 build identity: $($apk.Name)"
        }

        $archive = [System.IO.Compression.ZipFile]::OpenRead($apk.FullName)
        try {
            $entries = @($archive.Entries)
            $nativeEntries = @($entries | Where-Object { $_.FullName -like "lib/*" })
            $runtimeEntries = @($nativeEntries | Where-Object {
                $_.Name -like "libLiteRt*" -or $_.Name -like "*onnxruntime*"
            })
            $runtimeRecords = @($runtimeEntries | Sort-Object FullName | ForEach-Object {
                [pscustomobject][ordered]@{
                    entryName = $_.FullName
                    runtime = if ($_.Name -like "libLiteRt*") { "LiteRT" } else { "ORT" }
                    bytes = [int64]$_.Length
                    compressedBytes = [int64]$_.CompressedLength
                    sha256 = Get-ZipEntrySha256 $_
                }
            })
            $liteRtEntries = @($runtimeRecords | Where-Object { $_.runtime -eq "LiteRT" })
            $ortEntries = @($runtimeRecords | Where-Object { $_.runtime -eq "ORT" })
            $apkRecords.Add([pscustomobject][ordered]@{
                variant = $variant
                fileName = $apk.Name
                bytes = [int64]$apk.Length
                sha256 = $apkSha256
                zipPayloadBytes = [int64](($entries | Measure-Object Length -Sum).Sum)
                nativeBytes = [int64](($nativeEntries | Measure-Object Length -Sum).Sum)
                liteRtBytes = [int64](($liteRtEntries | Measure-Object bytes -Sum).Sum)
                ortBytes = [int64](($ortEntries | Measure-Object bytes -Sum).Sum)
                otherNativeBytes = [int64](
                    (($nativeEntries | Measure-Object Length -Sum).Sum) -
                    (($runtimeRecords | Measure-Object bytes -Sum).Sum)
                )
                nativeEntryCount = $nativeEntries.Count
                runtimeEntries = $runtimeRecords
            })
        } finally {
            $archive.Dispose()
        }
    }

    $actualVariants = @($apkRecords.variant)
    $missingVariants = @($variantOrder | Where-Object { $_ -notin $actualVariants })
    if ($missingVariants.Count -gt 0) {
        throw "APK variants are missing from the inventory: $missingVariants"
    }

    $installedRecords = [System.Collections.Generic.List[object]]::new()
    $seenInstalledVariants = [System.Collections.Generic.HashSet[string]]::new()
    foreach ($target in $InstalledTarget) {
        $parts = $target.Split('=', 2)
        if ($parts.Count -ne 2 -or $parts[0] -notin $variantOrder[0..3] -or
                [string]::IsNullOrWhiteSpace($parts[1])) {
            throw "InstalledTarget must use <abi>=<adb-serial>: $target"
        }
        $variant = $parts[0]
        $serial = $parts[1]
        if (-not $seenInstalledVariants.Add($variant)) {
            throw "InstalledTarget repeats ABI $variant."
        }
        $localRecord = @($apkRecords) | Where-Object { $_.variant -eq $variant } |
            Select-Object -First 1
        $packagePathOutput = Invoke-AdbText $serial @("shell", "pm", "path", $PackageName)
        $packagePaths = @($packagePathOutput -split "`n" | Where-Object {
            $_.StartsWith("package:", [System.StringComparison]::Ordinal)
        })
        if ($packagePaths.Count -ne 1) {
            throw "Expected one installed base APK for $PackageName on $serial."
        }
        $packagePath = $packagePaths[0].Substring("package:".Length).Trim()
        $remoteHashLine = Invoke-AdbText $serial @("shell", "sha256sum", $packagePath)
        if ($remoteHashLine -notmatch '^([0-9a-fA-F]{64})\s+') {
            throw "Could not parse installed APK SHA-256 on $serial."
        }
        $installedSha256 = $Matches[1].ToLowerInvariant()
        if ($installedSha256 -ne $localRecord.sha256) {
            throw "Installed $variant APK hash does not match the local split on $serial."
        }
        $installedBytesText = Invoke-AdbText $serial @("shell", "stat", "-c", "%s", $packagePath)
        if ($installedBytesText -notmatch '^\d+$') {
            throw "Could not parse installed APK byte size on $serial."
        }
        $installedBytes = [int64]$installedBytesText
        $baseDuText = Invoke-AdbText $serial @("shell", "du", "-sk", $packagePath)
        if ($baseDuText -notmatch '^(\d+)\s+') {
            throw "Could not parse installed APK disk usage on $serial."
        }
        $baseAllocatedKiB = [int64]$Matches[1]
        $codePath = $packagePath -replace '/base\.apk$', ''
        $codeDuOutput = & $adb -s $serial shell du -sk $codePath 2>&1
        $codeDuLine = @($codeDuOutput | ForEach-Object { [string]$_ } |
            Where-Object { $_ -match '^\d+\s+' } | Select-Object -First 1)
        $codeAllocatedKiB = if ($codeDuLine.Count -eq 1 -and
                $codeDuLine[0] -match '^(\d+)\s+') {
            [int64]$Matches[1]
        } else {
            $null
        }
        $packageDump = Invoke-AdbText $serial @("shell", "dumpsys", "package", $PackageName)
        $primaryAbiMatch = [regex]::Match($packageDump, '(?m)^\s*primaryCpuAbi=(\S+)\s*$')
        if (-not $primaryAbiMatch.Success -or $primaryAbiMatch.Groups[1].Value -ne $variant) {
            throw "Installed package ABI does not match $variant on $serial."
        }
        $installedRecords.Add([pscustomobject][ordered]@{
            variant = $variant
            serial = $serial
            manufacturer = Invoke-AdbText $serial @("shell", "getprop", "ro.product.manufacturer")
            model = Invoke-AdbText $serial @("shell", "getprop", "ro.product.model")
            androidApi = [int](Invoke-AdbText $serial @("shell", "getprop", "ro.build.version.sdk"))
            installedApkBytes = $installedBytes
            installedApkSha256 = $installedSha256
            baseApkAllocatedKiB = $baseAllocatedKiB
            codePathAllocatedKiB = $codeAllocatedKiB
        })
    }

    $relativeApkRoot = [System.IO.Path]::GetRelativePath($repoRoot, $apkRoot).Replace('\', '/')
    $report = [ordered]@{
        schemaVersion = "phase7-dual-runtime-inventory-v1"
        generatedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
        appCommit = [string]$buildIdentity.appCommit
        testApk = $buildIdentity.testApk
        packageName = $PackageName
        apkDirectory = $relativeApkRoot
        runtimeState = "dual-runtime-ort-oracle-and-litert-production"
        apks = @($apkRecords | Sort-Object {
            [array]::IndexOf($variantOrder, $_.variant)
        })
        installed = @($installedRecords | Sort-Object {
            [array]::IndexOf($variantOrder, $_.variant)
        })
    }
    $outputFullPath = [System.IO.Path]::GetFullPath((Join-Path $repoRoot $OutputPath))
    $outputParent = Split-Path -Parent $outputFullPath
    New-Item -ItemType Directory -Force -Path $outputParent | Out-Null
    $report | ConvertTo-Json -Depth 12 |
        Set-Content -LiteralPath $outputFullPath -Encoding utf8
    Write-Host "Saved Phase 7 APK inventory to $outputFullPath"
} finally {
    Pop-Location
}
