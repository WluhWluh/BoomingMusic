param(
    [Parameter(Mandatory = $true)]
    [string]$AvdName,

    [string]$Serial = "emulator-5558",
    [int]$Port = 5558,
    [int[]]$MemoryMiB = @(2048, 3072, 4096),
    [int]$RunsPerBoot = 3,
    [string]$HeapGrowthLimit = "228m",
    [string]$ModelId = "uvr_mdxnet_3_9662",
    [string]$FixtureId = "coast_town_short_wav",
    [string]$SourceRepository = "",
    [string]$OutputRoot = "",
    [string]$RunPrefix = "phase5-x86-memory-v1",
    [int]$BootTimeoutSeconds = 300,
    [switch]$SkipBuild,
    [switch]$SkipAcquisition,
    [switch]$LeaveRunning
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$runner = Join-Path $PSScriptRoot "run_phase7_validation.ps1"
$fixturesPath = Join-Path $repoRoot "docs\validation\litert-phase7\fixtures-v2.json"
$adb = (Get-Command adb -ErrorAction Stop).Source
$emulator = if ($env:ANDROID_SDK_ROOT) {
    Join-Path $env:ANDROID_SDK_ROOT "emulator\emulator.exe"
} elseif ($env:ANDROID_HOME) {
    Join-Path $env:ANDROID_HOME "emulator\emulator.exe"
} else {
    Join-Path $env:LOCALAPPDATA "Android\Sdk\emulator\emulator.exe"
}

if (-not (Test-Path -LiteralPath $emulator -PathType Leaf)) {
    throw "Android emulator executable not found: $emulator"
}
if ($Port -lt 5554 -or $Port % 2 -ne 0 -or $Serial -ne "emulator-$Port") {
    throw "Serial must match the requested even emulator port."
}
if ($MemoryMiB.Count -lt 1 -or @($MemoryMiB | Where-Object { $_ -lt 1536 }).Count) {
    throw "Every emulator memory configuration must be at least 1536 MiB."
}
if ($RunsPerBoot -lt 3) {
    throw "RunsPerBoot must be at least three."
}
if ($HeapGrowthLimit -notmatch '^\d+[kKmMgG]$') {
    throw "HeapGrowthLimit must be an Android size such as 228m."
}
if ($BootTimeoutSeconds -lt 60) {
    throw "BootTimeoutSeconds must be at least 60."
}

if ([string]::IsNullOrWhiteSpace($SourceRepository)) {
    $SourceRepository = Join-Path $repoRoot "..\..\MusicSourceSeparation"
}
$sourceRoot = (Resolve-Path -LiteralPath $SourceRepository).Path
if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $OutputRoot = Join-Path $repoRoot "build\phase5-x86-memory"
}
New-Item -ItemType Directory -Force -Path $OutputRoot | Out-Null
$OutputRoot = (Resolve-Path -LiteralPath $OutputRoot).Path

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
$summaryPath = Join-Path $deviceDirectory "$RunPrefix-summary.json"
$minimumRuntimeHeapBytes = 128L * 1024L * 1024L
$minimumVaGapBytes = 128L * 1024L * 1024L
$runRecords = [System.Collections.Generic.List[object]]::new()
$bootRecords = [System.Collections.Generic.List[object]]::new()
$failures = [System.Collections.Generic.List[string]]::new()
$buildPrepared = [bool]$SkipBuild
$installPrepared = $false
$acquisitionPrepared = [bool]$SkipAcquisition
$activeSerial = $false

function Get-AdbText([string[]]$Arguments, [switch]$AllowFailure) {
    $output = & $adb @Arguments 2>$null
    $exitCode = $LASTEXITCODE
    if (-not $AllowFailure -and $exitCode -ne 0) {
        throw "adb failed with exit code ${exitCode}: $Arguments"
    }
    if ($null -eq $output) { return "" }
    return (($output | ForEach-Object { [string]$_ }) -join "`n").Trim()
}

function Get-DeviceText([string[]]$Arguments, [switch]$AllowFailure) {
    return Get-AdbText -Arguments (@("-s", $Serial) + $Arguments) `
        -AllowFailure:$AllowFailure
}

function Get-DeviceProperty([string]$Name) {
    return Get-DeviceText -Arguments @("shell", "getprop", $Name) -AllowFailure
}

function Test-DeviceOnline {
    $state = Get-DeviceText -Arguments @("get-state") -AllowFailure
    return $state -eq "device"
}

function Wait-DeviceOnline([int]$TimeoutSeconds) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        if (Test-DeviceOnline) { return }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "$Serial did not become online within $TimeoutSeconds seconds."
}

function Wait-FrameworkReady([int]$TimeoutSeconds) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $bootCompleted = Get-DeviceProperty "sys.boot_completed"
        $zygote = Get-DeviceProperty "init.svc.zygote"
        $packageService = Get-DeviceText `
            -Arguments @("shell", "service", "check", "package") `
            -AllowFailure
        $currentUser = Get-DeviceText `
            -Arguments @("shell", "am", "get-current-user") `
            -AllowFailure
        if ($bootCompleted -eq "1" -and $zygote -eq "running" -and
                $packageService -match 'found' -and $currentUser -match '^\d+$') {
            return
        }
        Start-Sleep -Seconds 1
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Android framework did not become ready within $TimeoutSeconds seconds."
}

function Get-RunningAvdName {
    if (-not (Test-DeviceOnline)) { return $null }
    $lines = @(Get-DeviceText -Arguments @("emu", "avd", "name") -AllowFailure) -split "`n"
    return @($lines | Where-Object { $_ -and $_ -ne "OK" }) |
        Select-Object -First 1
}

function Stop-RunningAvd {
    if (-not (Test-DeviceOnline)) { return }
    $runningAvd = Get-RunningAvdName
    if ($runningAvd -and $runningAvd -ne $AvdName) {
        throw "$Serial is running AVD '$runningAvd', not '$AvdName'."
    }
    [void](Get-DeviceText -Arguments @("emu", "kill") -AllowFailure)
    $deadline = [DateTime]::UtcNow.AddSeconds(60)
    do {
        if (-not (Test-DeviceOnline)) { return }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "$Serial did not stop within 60 seconds."
}

function Start-ColdAvd([int]$RequestedMemoryMiB) {
    Stop-RunningAvd
    $arguments = @(
        "-avd", $AvdName,
        "-port", [string]$Port,
        "-no-window",
        "-no-snapshot",
        "-no-boot-anim",
        "-memory", [string]$RequestedMemoryMiB
    )
    $process = Start-Process `
        -FilePath $emulator `
        -ArgumentList $arguments `
        -PassThru `
        -WindowStyle Hidden
    Wait-DeviceOnline $BootTimeoutSeconds
    Wait-FrameworkReady $BootTimeoutSeconds
    $runningAvd = Get-RunningAvdName
    if ($runningAvd -ne $AvdName) {
        throw "Expected AVD '$AvdName', found '$runningAvd'."
    }
    return [ordered]@{
        hostProcessId = $process.Id
        arguments = $arguments
    }
}

function Restart-FrameworkWithHeapLimit {
    $before = Get-DeviceProperty "dalvik.vm.heapgrowthlimit"
    [void](Get-DeviceText -Arguments @("root"))
    Wait-DeviceOnline 60
    [void](Get-DeviceText -Arguments @("shell", "stop"))
    [void](Get-DeviceText -Arguments @(
        "shell", "setprop", "dalvik.vm.heapgrowthlimit", $HeapGrowthLimit
    ))
    [void](Get-DeviceText -Arguments @("shell", "start"))
    Wait-FrameworkReady $BootTimeoutSeconds
    $after = Get-DeviceProperty "dalvik.vm.heapgrowthlimit"
    if ($after -ne $HeapGrowthLimit) {
        throw "Framework heap growth limit is '$after', expected '$HeapGrowthLimit'."
    }
    return [ordered]@{
        before = $before
        requested = $HeapGrowthLimit
        after = $after
        method = "adb-root-stop-setprop-start"
    }
}

function Get-MeminfoValue([string]$Text, [string]$Name) {
    $match = [regex]::Match($Text, "(?m)^$([regex]::Escape($Name)):\s+(\d+)\s+kB$")
    if (-not $match.Success) { return $null }
    return [int64]$match.Groups[1].Value * 1024L
}

function Read-IniSelection([string]$Path, [string[]]$Names) {
    $values = [ordered]@{}
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $values }
    foreach ($line in Get-Content -LiteralPath $Path) {
        if ($line -notmatch '^([^=]+)=(.*)$') { continue }
        $name = $Matches[1]
        if ($name -in $Names) { $values[$name] = $Matches[2] }
    }
    return $values
}

function Capture-BootEnvironment(
    [int]$BootOrdinal,
    [int]$RequestedMemoryMiB,
    $Launch,
    $HeapOverride
) {
    $meminfo = Get-DeviceText -Arguments @("shell", "cat", "/proc/meminfo")
    $avdDirectory = Join-Path $env:USERPROFILE ".android\avd\$AvdName.avd"
    $config = Read-IniSelection `
        -Path (Join-Path $avdDirectory "config.ini") `
        -Names @("abi.type", "hw.cpu.arch", "hw.ramSize", "vm.heapSize")
    $generated = Read-IniSelection `
        -Path (Join-Path $avdDirectory "hardware-qemu.ini") `
        -Names @("hw.cpu.arch", "hw.ramSize", "vm.heapSize")
    $emulatorVersion = (& $emulator -version 2>$null | Select-Object -First 1).Trim()
    return [ordered]@{
        schemaVersion = "phase5-x86-boot-environment-v1"
        capturedAtUtc = [DateTime]::UtcNow.ToString("o")
        sourceCommit = (& git -C $repoRoot rev-parse HEAD).Trim()
        dirtyTree = [bool]((& git -C $repoRoot status --porcelain) | Select-Object -First 1)
        avd = [ordered]@{
            name = $AvdName
            serial = $Serial
            bootOrdinal = $BootOrdinal
            requestedMemoryMiB = $RequestedMemoryMiB
            launch = $Launch
            config = $config
            generatedHardware = $generated
            emulatorVersion = $emulatorVersion
        }
        guest = [ordered]@{
            bootId = Get-DeviceText -Arguments @(
                "shell", "cat", "/proc/sys/kernel/random/boot_id"
            )
            androidApi = [int](Get-DeviceProperty "ro.build.version.sdk")
            buildFingerprint = Get-DeviceProperty "ro.build.fingerprint"
            cpuAbi = Get-DeviceProperty "ro.product.cpu.abi"
            cpuAbiList = Get-DeviceProperty "ro.product.cpu.abilist"
            totalMemoryBytes = Get-MeminfoValue $meminfo "MemTotal"
            availableMemoryBytes = Get-MeminfoValue $meminfo "MemAvailable"
            swapTotalBytes = Get-MeminfoValue $meminfo "SwapTotal"
            lowRam = Get-DeviceProperty "ro.config.low_ram"
        }
        framework = [ordered]@{
            heapGrowthLimitOverride = $HeapOverride
            heapSize = Get-DeviceProperty "dalvik.vm.heapsize"
            heapStartSize = Get-DeviceProperty "dalvik.vm.heapstartsize"
            heapMinFree = Get-DeviceProperty "dalvik.vm.heapminfree"
            heapMaxFree = Get-DeviceProperty "dalvik.vm.heapmaxfree"
            zygote = Get-DeviceProperty "init.svc.zygote"
            bootCompleted = Get-DeviceProperty "sys.boot_completed"
        }
    }
}

function Invoke-Phase7([hashtable]$Arguments) {
    & $runner @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Phase 7 runner exited with code $LASTEXITCODE."
    }
}

function Save-Summary {
    $summary = [ordered]@{
        schemaVersion = "phase5-x86-memory-matrix-v1"
        generatedAtUtc = [DateTime]::UtcNow.ToString("o")
        sourceCommit = (& git -C $repoRoot rev-parse HEAD).Trim()
        avdName = $AvdName
        serial = $Serial
        requestedMemoryMiB = @($MemoryMiB)
        runsPerBoot = $RunsPerBoot
        heapGrowthLimit = $HeapGrowthLimit
        modelId = $ModelId
        fixtureId = $FixtureId
        minimumRuntimeHeapBytes = $minimumRuntimeHeapBytes
        minimumVaGapBytes = $minimumVaGapBytes
        boots = @($bootRecords)
        runs = @($runRecords)
        failures = @($failures)
        status = if ($failures.Count -eq 0) { "passed" } else { "failed" }
    }
    $summary | ConvertTo-Json -Depth 12 |
        Set-Content -LiteralPath $summaryPath -Encoding utf8
}

try {
    for ($bootIndex = 0; $bootIndex -lt $MemoryMiB.Count; $bootIndex++) {
        $requestedMemory = [int]$MemoryMiB[$bootIndex]
        $bootOrdinal = $bootIndex + 1
        Write-Host "Cold boot $bootOrdinal/$($MemoryMiB.Count): ${requestedMemory} MiB"
        $launch = Start-ColdAvd $requestedMemory
        $activeSerial = $true
        $heapOverride = Restart-FrameworkWithHeapLimit
        $environment = Capture-BootEnvironment `
            -BootOrdinal $bootOrdinal `
            -RequestedMemoryMiB $requestedMemory `
            -Launch $launch `
            -HeapOverride $heapOverride
        $environmentRunId = "$RunPrefix-${requestedMemory}m-boot$bootOrdinal"
        $environmentPath = Join-Path $deviceDirectory "$environmentRunId-environment.json"
        $environment | ConvertTo-Json -Depth 10 |
            Set-Content -LiteralPath $environmentPath -Encoding utf8
        $relativeEnvironmentPath = $environmentPath.Substring($OutputRoot.Length).TrimStart(
            '\', '/'
        ).Replace('\', '/')
        $bootRecord = [ordered]@{
            bootOrdinal = $bootOrdinal
            requestedMemoryMiB = $requestedMemory
            actualMemoryBytes = $environment.guest.totalMemoryBytes
            swapTotalBytes = $environment.guest.swapTotalBytes
            bootId = $environment.guest.bootId
            heapGrowthLimit = $environment.framework.heapGrowthLimitOverride.after
            environmentFile = $relativeEnvironmentPath
            environmentSha256 = (Get-FileHash -LiteralPath $environmentPath `
                -Algorithm SHA256).Hash.ToLowerInvariant()
        }
        $bootRecords.Add($bootRecord)
        Save-Summary

        if (-not $acquisitionPrepared) {
            $acquisitionRunId = "$environmentRunId-acquisition"
            $acquisitionArguments = @{
                Serial = $Serial
                ProcessAbi = "x86"
                ModelId = $ModelId
                Stage = "acquisition"
                RunId = $acquisitionRunId
                OutputRoot = $OutputRoot
                BackendMode = "auto"
                X86ProcessValidation = $true
            }
            if ($buildPrepared) { $acquisitionArguments.SkipBuild = $true }
            Invoke-Phase7 $acquisitionArguments
            $buildPrepared = $true
            $installPrepared = $true
            $acquisitionPrepared = $true
        }

        for ($run = 1; $run -le $RunsPerBoot; $run++) {
            $runId = "$environmentRunId-worker$run"
            $arguments = @{
                Serial = $Serial
                ProcessAbi = "x86"
                ModelId = $ModelId
                Stage = "worker"
                SourcePath = $sourcePath
                FixtureId = $FixtureId
                RunId = $runId
                OutputRoot = $OutputRoot
                BackendMode = "auto"
                ExecutionHostMode = "bound-remote"
                KeepAppData = $true
                ProbeOriginalPlayback = $true
                ForceStopBeforeRun = $true
                X86ProcessValidation = $true
            }
            if ($buildPrepared) { $arguments.SkipBuild = $true }
            if ($installPrepared) { $arguments.SkipInstall = $true }
            $runError = $null
            try {
                Invoke-Phase7 $arguments
                $buildPrepared = $true
                $installPrepared = $true
            } catch {
                $runError = $_.Exception.Message
                $failures.Add("${runId}: $runError")
            }

            $reportPath = Join-Path $deviceDirectory "$runId-worker.json"
            $report = if (Test-Path -LiteralPath $reportPath -PathType Leaf) {
                Get-Content -LiteralPath $reportPath -Raw | ConvertFrom-Json
            } else {
                $null
            }
            $mainRuntimeMax = if ($null -ne $report) {
                [int64]$report.device.runtimeMaxMemoryBytes
            } else { 0L }
            $remoteRuntimeMax = if ($null -ne $report.executionHost.process) {
                [int64]$report.executionHost.process.runtimeMaxMemoryBytes
            } else { 0L }
            $minimumGap = if ($null -ne $report.memory.minimumLargestFreeAddressGapBytes) {
                [int64]$report.memory.minimumLargestFreeAddressGapBytes
            } else { 0L }
            $runtimeAdmissionPassed = $mainRuntimeMax -ge $minimumRuntimeHeapBytes -and
                $remoteRuntimeMax -ge $minimumRuntimeHeapBytes
            $vaGapPassed = $minimumGap -ge $minimumVaGapBytes
            if ($null -ne $report -and (-not $runtimeAdmissionPassed -or -not $vaGapPassed)) {
                $reason = "runtimeAdmission=$runtimeAdmissionPassed;vaGap=$vaGapPassed"
                $failures.Add("${runId}: $reason")
            }
            $record = [ordered]@{
                runId = $runId
                bootOrdinal = $bootOrdinal
                requestedMemoryMiB = $requestedMemory
                reportFile = if ($null -ne $report) {
                    $reportPath.Substring($OutputRoot.Length).TrimStart('\', '/').Replace('\', '/')
                } else { $null }
                reportSha256 = if ($null -ne $report) {
                    (Get-FileHash -LiteralPath $reportPath -Algorithm SHA256).Hash.ToLowerInvariant()
                } else { $null }
                status = if ($null -ne $report) { [string]$report.status } else { "missing" }
                runnerError = $runError
                appCommit = if ($null -ne $report) { [string]$report.identity.appCommit } else { $null }
                runnerRevision = if ($null -ne $report) {
                    [string]$report.identity.runnerRevision
                } else { $null }
                actualMemoryBytes = if ($null -ne $report) {
                    [int64]$report.device.totalMemoryBytes
                } else { $null }
                mainRuntimeMaxMemoryBytes = $mainRuntimeMax
                remoteRuntimeMaxMemoryBytes = $remoteRuntimeMax
                runtimeAdmissionPassed = $runtimeAdmissionPassed
                firstReadyMs = if ($null -ne $report) { $report.timing.firstReadyMs } else { $null }
                fullSongMs = if ($null -ne $report) { $report.timing.fullSongMs } else { $null }
                peakMainPssBytes = if ($null -ne $report) {
                    $report.memory.peakPssBytes
                } else { $null }
                peakRemotePssBytes = if ($null -ne $report) {
                    $report.memory.peakRemotePssBytes
                } else { $null }
                peakSummedPssBytes = if ($null -ne $report) {
                    $report.memory.peakSummedPssBytes
                } else { $null }
                minimumLargestFreeAddressGapBytes = $minimumGap
                vaGapPassed = $vaGapPassed
                finalSessionState = if ($null -ne $report.executionHost.session) {
                    [string]$report.executionHost.session.state
                } else { $null }
                nativeSessionCreationCount = if ($null -ne $report.executionHost.session) {
                    [int]$report.executionHost.session.nativeSessionCreationCount
                } else { 0 }
                unexpectedPlaybackEvents = if ($null -ne $report.originalPlayback) {
                    [int]$report.originalPlayback.unexpectedEventCount
                } else { $null }
                playbackDriftMs = if ($null -ne $report.originalPlayback) {
                    [int64]$report.originalPlayback.maximumPositionDriftMs
                } else { $null }
            }
            $runRecords.Add($record)
            Save-Summary
        }

        if (-not $LeaveRunning -or $bootIndex -lt $MemoryMiB.Count - 1) {
            Stop-RunningAvd
            $activeSerial = $false
        }
    }
} finally {
    Save-Summary
    if ($activeSerial -and -not $LeaveRunning) {
        Stop-RunningAvd
    }
}

if ($failures.Count) {
    throw "Phase 5 x86 memory matrix recorded $($failures.Count) failure(s): $summaryPath"
}
Write-Host "Saved Phase 5 x86 memory summary to $summaryPath"
