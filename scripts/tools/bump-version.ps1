param(
    [Parameter(Mandatory = $true)][int]$NewCode
)

$ErrorActionPreference = 'Stop'

$newName = "1.0.$NewCode"
$file = Join-Path $PSScriptRoot '..\version.properties'

if (-not (Test-Path $file)) {
    Write-Error "version.properties not found: $file"
    exit 1
}

try {
    $content = Get-Content $file -Raw -Encoding UTF8
    $content = $content -replace 'versionCode=\d+', "versionCode=$NewCode"
    $content = $content -replace 'versionName=[\d.]+', "versionName=$newName"

    $utf8Bom = New-Object System.Text.UTF8Encoding($true)
    [System.IO.File]::WriteAllText($file, $content, $utf8Bom)

    Write-Output "version.properties updated: versionCode=$NewCode, versionName=$newName"
    exit 0
} catch {
    Write-Error "Failed to update version.properties: $_"
    exit 1
}