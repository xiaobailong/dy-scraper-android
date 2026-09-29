param(
    [Parameter(Mandatory = $true)][string]$VersionName,
    [Parameter(Mandatory = $true)][int]$VersionCode
)

$ErrorActionPreference = 'Stop'

$file = Join-Path $PSScriptRoot '..\app\build.gradle.kts'

if (-not (Test-Path $file)) {
    Write-Error "build.gradle.kts not found: $file"
    exit 1
}

try {
    $content = Get-Content $file -Raw -Encoding UTF8
    $content = $content -replace '(?m)(^\s*versionCode\s*=\s*)\d+', "`${1}$VersionCode"
    $content = $content -replace '(?m)(^\s*versionName\s*=\s*)"[^"]*"', "`${1}`"$VersionName`""

    $utf8Bom = New-Object System.Text.UTF8Encoding($true)
    [System.IO.File]::WriteAllText($file, $content, $utf8Bom)

    Write-Output "build.gradle.kts updated: versionCode=$VersionCode, versionName=$VersionName"
    exit 0
} catch {
    Write-Error "Failed to update build.gradle.kts: $_"
    exit 1
}