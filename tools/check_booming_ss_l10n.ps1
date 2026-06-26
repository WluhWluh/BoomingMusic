param(
    [string] $ResourceRoot = "app/src/main/res"
)

$ErrorActionPreference = "Stop"

function Get-StringEntries([string] $Path) {
    $entries = [ordered]@{}
    if (-not (Test-Path $Path)) {
        return $entries
    }

    $content = Get-Content $Path -Raw
    $matches = [regex]::Matches(
        $content,
        '<string\s+name="([^"]+)"[^>]*>(.*?)</string>',
        [System.Text.RegularExpressions.RegexOptions]::Singleline
    )

    foreach ($match in $matches) {
        $entries[$match.Groups[1].Value] = $match.Groups[2].Value
    }
    return $entries
}

function Get-Placeholders([string] $Value) {
    $matches = [regex]::Matches($Value, '%(?:\d+\$)?[dsf]')
    return @($matches | ForEach-Object { $_.Value })
}

$baseFile = Join-Path $ResourceRoot "values/strings_booming_ss.xml"
if (-not (Test-Path $baseFile)) {
    throw "Missing base file: $baseFile"
}

$baseEntries = Get-StringEntries $baseFile
$baseKeys = @($baseEntries.Keys)
$failed = $false

$localeDirs = Get-ChildItem $ResourceRoot -Directory |
    Where-Object { $_.Name -like "values-*" -and (Test-Path (Join-Path $_.FullName "strings.xml")) } |
    Sort-Object Name

foreach ($dir in $localeDirs) {
    $localizedFile = Join-Path $dir.FullName "strings_booming_ss.xml"
    if (-not (Test-Path $localizedFile)) {
        Write-Host "missing $($dir.Name)/strings_booming_ss.xml"
        continue
    }

    $localizedEntries = Get-StringEntries $localizedFile
    foreach ($key in $localizedEntries.Keys) {
        if ($baseEntries.Contains($key)) {
            continue
        }
        Write-Error "$($dir.Name): unexpected Booming SS key '$key'"
        $failed = $true
    }

    foreach ($key in $baseKeys) {
        if (-not $localizedEntries.Contains($key)) {
            Write-Error "$($dir.Name): missing Booming SS key '$key'"
            $failed = $true
            continue
        }

        $basePlaceholders = Get-Placeholders $baseEntries[$key]
        $localizedPlaceholders = Get-Placeholders $localizedEntries[$key]
        if (($basePlaceholders -join "|") -ne ($localizedPlaceholders -join "|")) {
            Write-Error "$($dir.Name): placeholder mismatch for '$key'"
            $failed = $true
        }
    }
}

if ($failed) {
    exit 1
}

Write-Host "Booming SS localization check completed."
