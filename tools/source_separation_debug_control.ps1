param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-z][a-z0-9_.]*$')]
    [string]$Command,

    [string]$Serial = "",

    [string]$PackageName = "com.wluhwluh.booming.sourcesep.debug",

    [string]$Arg = "",

    [string[]]$Extra = @(),

    [switch]$Wait,

    [ValidateRange(1, 86400)]
    [int]$TimeoutSeconds = 600,

    [ValidateRange(50, 10000)]
    [int]$PollIntervalMs = 250,

    [string]$PullTo = ""
)

$ErrorActionPreference = "Stop"
$adb = (Get-Command adb -ErrorAction Stop).Source
$authority = "content://$PackageName.debug-control"

function Invoke-Control([string]$Method, [string]$CallArg, [string[]]$Bindings) {
    $remoteArguments = @(
        "content", "call",
        "--uri", $authority,
        "--method", $Method
    )
    if (-not [string]::IsNullOrWhiteSpace($CallArg)) {
        $remoteArguments += @("--arg", $CallArg)
    }
    foreach ($binding in $Bindings) {
        if ($binding -notmatch '^[A-Za-z][A-Za-z0-9_]*:[bsilfd]:.*$') {
            throw "Invalid typed extra '$binding'. Expected key:{b,s,i,l,f,d}:value."
        }
        $remoteArguments += @("--extra", $binding)
    }
    $singleQuote = [char]39
    $quotedSingleQuote = [string]::Concat(
        $singleQuote, [char]34, $singleQuote, [char]34, $singleQuote
    )
    $remoteCommand = ($remoteArguments | ForEach-Object {
        [string]::Concat(
            $singleQuote,
            $_.Replace([string]$singleQuote, $quotedSingleQuote),
            $singleQuote
        )
    }) -join " "

    $adbArguments = @()
    if (-not [string]::IsNullOrWhiteSpace($Serial)) {
        $adbArguments += @("-s", $Serial)
    }
    $adbArguments += @("shell", $remoteCommand)

    $output = & $adb @adbArguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "ADB content call failed: $($output -join [Environment]::NewLine)"
    }
    $text = $output -join "`n"
    $json = Read-BalancedJson $text
    if ([string]::IsNullOrWhiteSpace($json)) {
        throw "The provider response did not contain a JSON envelope: $text"
    }
    return $json | ConvertFrom-Json -Depth 100
}

function Read-BalancedJson([string]$Text) {
    $marker = "json="
    $markerIndex = $Text.IndexOf($marker, [StringComparison]::Ordinal)
    if ($markerIndex -lt 0) { return $null }
    $start = $Text.IndexOf('{', $markerIndex + $marker.Length)
    if ($start -lt 0) { return $null }

    $depth = 0
    $quoted = $false
    $escaped = $false
    for ($index = $start; $index -lt $Text.Length; $index++) {
        $character = $Text[$index]
        if ($quoted) {
            if ($escaped) {
                $escaped = $false
            } elseif ($character -eq '\') {
                $escaped = $true
            } elseif ($character -eq '"') {
                $quoted = $false
            }
            continue
        }
        if ($character -eq '"') {
            $quoted = $true
        } elseif ($character -eq '{') {
            $depth++
        } elseif ($character -eq '}') {
            $depth--
            if ($depth -eq 0) {
                return $Text.Substring($start, $index - $start + 1)
            }
        }
    }
    return $null
}

function Wait-Operation([string]$OperationId) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $response = Invoke-Control "operation.get" "" @("operation_id:s:$OperationId")
        if (-not $response.ok) {
            throw "Operation query failed [$($response.code)]: $($response.message)"
        }
        $operation = $response.data
        if ($operation.status -notin @("queued", "running")) {
            return $response
        }
        if ([DateTime]::UtcNow -ge $deadline) {
            throw "Timed out waiting for operation $OperationId after $TimeoutSeconds seconds."
        }
        Start-Sleep -Milliseconds $PollIntervalMs
    } while ($true)
}

function Pull-Diagnostics([string]$RemotePath, [string]$Destination) {
    if ([string]::IsNullOrWhiteSpace($RemotePath)) {
        throw "The completed operation did not return a diagnostics path."
    }
    $directoryRequested = $Destination.EndsWith([IO.Path]::DirectorySeparatorChar) -or
        $Destination.EndsWith([IO.Path]::AltDirectorySeparatorChar) -or
        (Test-Path -LiteralPath $Destination -PathType Container)
    if ($directoryRequested) {
        New-Item -ItemType Directory -Force -Path $Destination | Out-Null
        $resolvedDestination = Join-Path $Destination (Split-Path -Leaf $RemotePath)
    } else {
        $resolvedDestination = $Destination
        $parent = Split-Path -Parent $Destination
        if (-not [string]::IsNullOrWhiteSpace($parent)) {
            New-Item -ItemType Directory -Force -Path $parent | Out-Null
        }
    }
    $adbArguments = @()
    if (-not [string]::IsNullOrWhiteSpace($Serial)) {
        $adbArguments += @("-s", $Serial)
    }
    $adbArguments += @("pull", $RemotePath, $resolvedDestination)
    & $adb @adbArguments
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to pull diagnostics archive '$RemotePath'."
    }
    return (Resolve-Path -LiteralPath $resolvedDestination).Path
}

if ($Command -eq "ui.launch") {
    $launchArguments = @()
    if (-not [string]::IsNullOrWhiteSpace($Serial)) {
        $launchArguments += @("-s", $Serial)
    }
    $launchArguments += @(
        "shell", "am", "start", "--user", "0", "-n",
        "$PackageName/com.mardous.booming.activities.MainActivity"
    )
    $launchOutput = & $adb @launchArguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to wake the Debug application: $($launchOutput -join [Environment]::NewLine)"
    }
    Start-Sleep -Milliseconds 250
}

$response = Invoke-Control $Command $Arg $Extra
if (-not $response.ok) {
    $response | ConvertTo-Json -Depth 100
    throw "Debug command failed [$($response.code)]: $($response.message)"
}

if ($Wait -and -not [string]::IsNullOrWhiteSpace([string]$response.operationId)) {
    $response = Wait-Operation ([string]$response.operationId)
}

if ($response.data.status -in @("failed", "canceled")) {
    $response | ConvertTo-Json -Depth 100
    throw "Debug operation ended as $($response.data.status): $($response.data.message)"
}

if (-not [string]::IsNullOrWhiteSpace($PullTo)) {
    if ($Command -ne "diagnostics.export") {
        throw "-PullTo is only valid with diagnostics.export."
    }
    if ($response.data.status -in @("queued", "running")) {
        throw "Use -Wait with -PullTo so the diagnostics operation can finish."
    }
    $pulledPath = Pull-Diagnostics ([string]$response.data.result.path) $PullTo
    $response.data.result | Add-Member -NotePropertyName pulledPath -NotePropertyValue $pulledPath
}

$response | ConvertTo-Json -Depth 100
