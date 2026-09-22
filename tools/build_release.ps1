# Build a publishable release bundle, excluding private runtime data.
#
# Why a script: a release bundle must not carry this machine's runtime data
# (token, logs, caches), and picking files by hand is easy to get wrong.
# This declares exactly what goes in, and runs a privacy scan that FAILS the
# build if any hardcoded private string is found.
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\build_release.ps1
#
# NOTE: keep this file pure ASCII. Windows PowerShell 5.1 reads BOM-less
# UTF-8 as ANSI, and non-ASCII characters here will break parsing.

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$pluginSrc = Join-Path $root "sakura_remote"
$apk = Join-Path $root "phone_app\android\app\build\outputs\apk\release\app-release.apk"

if (-not (Test-Path $apk)) {
    throw "APK not found: $apk (run phone_app\build_apk.bat first)"
}
if (-not (Test-Path $pluginSrc)) {
    throw "Plugin source not found: $pluginSrc"
}

$stamp = Get-Date -Format "yyyyMMdd-HHmm"
$out = Join-Path $root ("dist\release-" + $stamp)
$pluginDst = Join-Path $out "plugin\sakura.remote"
New-Item -ItemType Directory -Force -Path $pluginDst | Out-Null

# 1) Plugin code, minus caches and personal runtime data.
Copy-Item (Join-Path $pluginSrc "*") $pluginDst -Recurse -Force
Get-ChildItem $out -Recurse -Directory -Filter "__pycache__" |
    Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
Get-ChildItem $out -Recurse -File -Include "*.pyc", "*.pyo" |
    Remove-Item -Force -ErrorAction SilentlyContinue

# 2) APK.
Copy-Item $apk (Join-Path $out "SakuraRemote-release.apk") -Force

# 3) Sanitized readme (the checked-in copy, never the working one).
$readme = Join-Path $PSScriptRoot "release_README.md"
if (Test-Path $readme) {
    Copy-Item $readme (Join-Path $out "README-install.md") -Force
} else {
    Write-Warning "tools\release_README.md missing; bundle will have no readme"
}

# 3b) Full user manual.
$manual = Join-Path $root "docs\manual.md"
if (Test-Path $manual) {
    Copy-Item $manual (Join-Path $out "MANUAL.md") -Force
} else {
    Write-Warning "docs\manual.md missing; bundle will have no manual"
}

# 4) Privacy scan.
#
# Instead of matching a fixed list of strings (which would have to contain the
# very secrets we are trying to detect), this flags *shapes* that should never
# appear in a release bundle:
#   * private LAN addresses
#   * absolute Windows paths pointing at a real install
#   * obvious placeholder tokens
# It is intentionally generic, so it never needs to embed anyone's real values.
$shapePatterns = @(
    @{ name = "private LAN IP";  re = '\b(?:192\.168|10\.\d{1,3}|172\.(?:1[6-9]|2\d|3[01]))\.\d{1,3}\.\d{1,3}\b' },
    @{ name = "absolute Windows path"; re = '[A-Za-z]:\\\\?(?:sakura|Sakura|Users|projects|repos|src)\\' },
    @{ name = "looks like a token"; re = '(?i)(?:token|secret|password)\s*[:=]\s*"[A-Za-z0-9_\-]{16,}"' },
    @{ name = "placeholder left in"; re = '(?i)(?:your-username|example-personal|todo[_-]replace[_-]me|change[_-]?me)' }
)
$hits = @()
$textExtensions = @(".py", ".js", ".css", ".json", ".md", ".xml", ".html", ".yaml", ".webmanifest")
Get-ChildItem $out -Recurse -File |
    Where-Object { $textExtensions -contains $_.Extension } |
    ForEach-Object {
        $text = [IO.File]::ReadAllText($_.FullName, [Text.UTF8Encoding]::new($false))
        foreach ($p in $shapePatterns) {
            if ($text -match $p.re) {
                $relative = $_.FullName.Replace(($out + "\"), "")
                $hits += ($relative + "  looks like " + $p.name)
            }
        }
    }

if ($hits.Count -gt 0) {
    Write-Host "PRIVACY SCAN FAILED -- bundle kept at $out for inspection" -ForegroundColor Red
    $hits | ForEach-Object { Write-Host ("   " + $_) -ForegroundColor Red }
    exit 1
}
Write-Host "Privacy scan passed (no LAN IPs, absolute paths, or leftover keys)" -ForegroundColor Green

# 5) Package.
Compress-Archive -Path (Join-Path $out "plugin\*") `
    -DestinationPath (Join-Path $out "plugin-sakura.remote-1.0.0.zip") -Force
Compress-Archive -Path (Join-Path $out "SakuraRemote-release.apk") `
    -DestinationPath (Join-Path $out "App-SakuraRemote.zip") -Force

Write-Host ""
Write-Host "Release bundle: $out"
Get-ChildItem $out | ForEach-Object {
    if ($_.PSIsContainer) {
        Write-Host ("  [dir] " + $_.Name)
    } else {
        Write-Host ("  {0,-40} {1,7:N0} KB" -f $_.Name, ($_.Length / 1KB))
    }
}
