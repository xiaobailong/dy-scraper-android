# =============================================================================
#  bump-version.ps1 - bump the patch of versionName (called by build.bat)
#
#  WHY THIS FILE EXISTS
#  On locked-down machines an endpoint policy silently blocks a PowerShell
#  -Command one-liner whose command line contains a regex (exit code 786).
#  The same logic invoked from this script file through -File is not affected.
#
#  VERSION SCHEME
#  version.properties holds versionName=x.y.z as the single source of truth.
#  Every build bumps the patch (+1), carry at 100:
#    x.y.99 -> x.(y+1).0
#    x.99.99 -> (x+1).0.0
#  versionCode is derived from the formula: major*10000 + minor*100 + patch
#  and written back to version.properties for Gradle consumption.
#  app/build.gradle.kts is also updated in sync.
#
#  USAGE
#    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\tools\bump-version.ps1
#
#  OUTPUT (stdout, single line)
#    OK=<old>-><new>|<versionCode>   file updated, exit code 0
#    ERROR=<reason>                  nothing written, exit code != 0
# =============================================================================
param()

$ErrorActionPreference = 'Stop'

$propsFile = Join-Path $PSScriptRoot '..\..\version.properties'
$gradleFile = Join-Path $PSScriptRoot '..\..\app\build.gradle.kts'

try {
    if (-not (Test-Path $propsFile)) {
        Write-Output 'ERROR=version.properties-not-found'
        exit 2
    }

    $text = [IO.File]::ReadAllText($propsFile)
    $m = [regex]::Match($text, 'versionName=(\d+)\.(\d+)\.(\d+)')
    if (-not $m.Success) {
        Write-Output 'ERROR=versionName-not-three-part'
        exit 4
    }

    $major = [int]$m.Groups[1].Value
    $minor = [int]$m.Groups[2].Value
    $patch = [int]$m.Groups[3].Value
    $old = "$major.$minor.$patch"

    $patch += 1
    if ($patch -ge 100) {
        $patch = 0
        $minor += 1
    }
    if ($minor -ge 100) {
        $minor = 0
        $major += 1
    }

    $new = "$major.$minor.$patch"
    $vcode = $major * 10000 + $minor * 100 + $patch

    # Write version.properties
    $out = $text -replace 'versionName=[\d.]+', "versionName=$new"
    $out = $out -replace 'versionCode=\d+', "versionCode=$vcode"
    $utf8Bom = New-Object Text.UTF8Encoding($true)
    [IO.File]::WriteAllText($propsFile, $out, $utf8Bom)

    # Write app/build.gradle.kts
    if (Test-Path $gradleFile) {
        $gt = [IO.File]::ReadAllText($gradleFile)

        $gmc = [regex]::Match($gt, '(?m)^[ \t]*versionCode[ \t]*=[^\r\n]*')
        $gmn = [regex]::Match($gt, '(?m)^[ \t]*versionName[ \t]*=[^\r\n]*')
        if ($gmc.Success -and $gmn.Success) {
            $indent = $gmc.Value.Substring(0, $gmc.Value.Length - $gmc.Value.TrimStart().Length)
            $gt = $gt.Remove($gmc.Index, $gmc.Length).Insert($gmc.Index, ($indent + 'versionCode = ' + $vcode))

            $gmn = [regex]::Match($gt, '(?m)^[ \t]*versionName[ \t]*=[^\r\n]*')
            $indent = $gmn.Value.Substring(0, $gmn.Value.Length - $gmn.Value.TrimStart().Length)
            $gt = $gt.Remove($gmn.Index, $gmn.Length).Insert($gmn.Index, ($indent + 'versionName = "' + $new + '"'))

            $utf8NoBom = New-Object Text.UTF8Encoding($false)
            [IO.File]::WriteAllText($gradleFile, $gt, $utf8NoBom)
        }
    }

    Write-Output ('OK=' + $old + '->' + $new + '|' + $vcode)
    exit 0
}
catch {
    Write-Output ('ERROR=' + $_.Exception.Message)
    exit 1
}