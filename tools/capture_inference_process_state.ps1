param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,

    [Parameter(Mandatory = $true)]
    [string]$OutputPath,

    [string]$PackageName = "com.wluhwluh.booming.sourcesep.debug",
    [string]$RunId = "manual",
    [string[]]$ProcessNames = @(
        "com.wluhwluh.booming.sourcesep.debug",
        "com.wluhwluh.booming.sourcesep.debug:source_separation"
    )
)

$ErrorActionPreference = "Stop"
$adb = (Get-Command adb -ErrorAction Stop).Source

function Invoke-AdbShell([string]$Command) {
    $output = & $adb -s $Serial shell $Command
    if ($LASTEXITCODE -ne 0) {
        throw "adb shell failed for '$Command'."
    }
    return @($output | ForEach-Object { ([string]$_).TrimEnd() })
}

function Read-ProcValue([int]$ProcessId, [string]$Path) {
    $ErrorActionPreference = "SilentlyContinue"
    $output = & $adb -s $Serial shell "cat /proc/$ProcessId/$Path" 2>$null
    if ($LASTEXITCODE -ne 0) {
        $output = & $adb -s $Serial shell run-as $PackageName `
            cat "/proc/$ProcessId/$Path" 2>$null
        if ($LASTEXITCODE -ne 0) {
            return $null
        }
    }
    return (($output -join "`n").Trim())
}

function Read-StatusValue([string]$Status, [string]$Name) {
    $match = [regex]::Match($Status, "(?m)^$([regex]::Escape($Name)):\s+(\d+)\s*(kB)?$")
    if (-not $match.Success) {
        return $null
    }
    $value = [int64]$match.Groups[1].Value
    if ($match.Groups[2].Success) {
        return $value * 1024L
    }
    return $value
}

function Read-MeminfoPss([int]$ProcessId) {
    $ErrorActionPreference = "SilentlyContinue"
    $output = & $adb -s $Serial shell "dumpsys meminfo $ProcessId" 2>$null
    if ($LASTEXITCODE -ne 0) {
        return $null
    }
    $text = $output -join "`n"
    $match = [regex]::Match($text, "(?m)^\s*TOTAL\s+(\d+)\s+")
    if (-not $match.Success) {
        return $null
    }
    return [int64]$match.Groups[1].Value * 1024L
}

$device = [ordered]@{
    serial = $Serial
    manufacturer = (Invoke-AdbShell "getprop ro.product.manufacturer" | Select-Object -First 1)
    model = (Invoke-AdbShell "getprop ro.product.model" | Select-Object -First 1)
    androidApi = [int](Invoke-AdbShell "getprop ro.build.version.sdk" | Select-Object -First 1)
    fingerprint = (Invoke-AdbShell "getprop ro.build.fingerprint" | Select-Object -First 1)
    abi = (Invoke-AdbShell "getprop ro.product.cpu.abi" | Select-Object -First 1)
}

$processes = @()
foreach ($processName in $ProcessNames) {
    $pidOutput = & $adb -s $Serial shell "pidof $processName" 2>$null
    $pidText = $pidOutput | Select-Object -First 1
    if ([string]::IsNullOrWhiteSpace($pidText)) {
        continue
    }
    foreach ($pidToken in ([string]$pidText).Trim().Split(' ', [System.StringSplitOptions]::RemoveEmptyEntries)) {
        $processId = [int]$pidToken
        $status = Read-ProcValue $processId "status"
        if ([string]::IsNullOrWhiteSpace($status)) {
            continue
        }
        $maps = Read-ProcValue $processId "maps"
        $processes += [ordered]@{
            processName = $processName
            role = $(if ($processName.EndsWith(":source_separation")) {
                "inference"
            } else {
                "main"
            })
            pid = $processId
            oomScoreAdj = [int](Read-ProcValue $processId "oom_score_adj")
            vmSizeBytes = Read-StatusValue $status "VmSize"
            vmPeakBytes = Read-StatusValue $status "VmPeak"
            vmRssBytes = Read-StatusValue $status "VmRSS"
            vmDataBytes = Read-StatusValue $status "VmData"
            threadCount = [int](Read-StatusValue $status "Threads")
            mappedRegionCount = $(if ([string]::IsNullOrWhiteSpace($maps)) {
                $null
            } else {
                @($maps -split "`n").Count
            })
            mapsReadable = -not [string]::IsNullOrWhiteSpace($maps)
            pssBytes = Read-MeminfoPss $processId
        }
    }
}

$servicesText = (Invoke-AdbShell "dumpsys activity services $PackageName") -join "`n"
$activityText = (Invoke-AdbShell "dumpsys activity processes") -join "`n"
$powerText = (Invoke-AdbShell "dumpsys power") -join "`n"
$matchingActivityLines = @(
    $activityText -split "`n" |
        Where-Object { $_ -like "*$PackageName*" } |
        ForEach-Object { $_.Trim() }
)
$matchingWakeLockLines = @(
    $powerText -split "`n" |
        Where-Object {
            $_ -like "*$PackageName*" -or $_ -like "*SourceSeparationProcessing*"
        } |
        ForEach-Object { $_.Trim() }
)
$displayState = @(
    $powerText -split "`n" |
        Where-Object { $_ -match "Wakefulness=|Display Power:" -or $_ -match "mScreenOn=" } |
        ForEach-Object { $_.Trim() }
)

$report = [ordered]@{
    schemaVersion = "litert-inference-process-state-v1"
    capturedAtUtc = [DateTime]::UtcNow.ToString("o")
    runId = $RunId
    packageName = $PackageName
    device = $device
    processes = $processes
    serviceDump = $servicesText
    activityProcessLines = $matchingActivityLines
    wakeLockLines = $matchingWakeLockLines
    displayState = $displayState
}

$parent = Split-Path -Parent $OutputPath
if (-not [string]::IsNullOrWhiteSpace($parent)) {
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
}
$report | ConvertTo-Json -Depth 8 |
    Set-Content -LiteralPath $OutputPath -Encoding utf8
Write-Output (Resolve-Path -LiteralPath $OutputPath)
