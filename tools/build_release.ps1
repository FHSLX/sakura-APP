# Build a publishable release bundle, excluding private runtime data.
#
# Why a script: a release bundle must not carry this machine's runtime data
# (token, logs, caches), and picking files by hand is easy to get wrong.
# This declares exactly what goes in, and runs a privacy scan that FAILS the
# build if anything that looks like a secret is found.
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\build_release.ps1
#
# NOTE: keep this file pure ASCII. Windows PowerShell 5.1 reads BOM-less
# UTF-8 as ANSI, and non-ASCII characters here can break parsing.

param(
    # Optional fixed output directory. Defaults to dist\release-<timestamp>.
    # Useful for rebuilding into the same folder, and for testing the
    # privacy scan itself (put a probe file in, run again, expect failure).
    [string]$OutDir = ""
)

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

if ($OutDir) {
    $out = $OutDir
} else {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $out = Join-Path $root ("dist\release-" + $stamp)
}
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
# Flags *shapes* that must never ship, rather than a fixed list of strings
# (a fixed list would have to contain the very secrets we are hunting).
# The customary documentation addresses are whitelisted so examples pass.
$exampleIps = "192.168.1.100 192.168.1.1 192.168.0.1 192.168.1.10 10.0.0.1 10.0.0.2 127.0.0.1"

$reLanIp = "\b(?:192\.168|10\.\d{1,3}|172\.(?:1[6-9]|2\d|3[01]))\.\d{1,3}\.\d{1,3}\b"
$reWinPath = "[A-Za-z]:\\\\?(?:sakura|Sakura|Users|projects|repos|src)\\\\"
$reToken = "(?i)(?:token|secret|password)\s*[:=]\s*""[A-Za-z0-9_\-]{16,}"""
$rePlaceholder = "(?i)(your-username|example-personal|todo[_-]replace[_-]me|change[_-]?me)"

$rules = @(
    @{ name = "real LAN IP"; regex = $reLanIp; isIp = $true },
    @{ name = "absolute Windows path"; regex = $reWinPath; isIp = $false },
    @{ name = "hardcoded token"; regex = $reToken; isIp = $false },
    @{ name = "leftover placeholder"; regex = $rePlaceholder; isIp = $false }
)

$textExtensions = ".py .js .mjs .cjs .ts .css .json .md .txt .xml .html .htm .yaml .yml .webmanifest .ps1 .bat .cmd .sh .ini .cfg .conf .env .properties .gradle .pro .java .kt .toml"
$allowedExtensions = $textExtensions.Split(" ")

$allFiles = @(Get-ChildItem -LiteralPath $out -Recurse -File -ErrorAction SilentlyContinue)
$scanned = 0
$hits = New-Object System.Collections.ArrayList

foreach ($file in $allFiles) {
    $lowerName = $file.Name.ToLower()
    $ext = $file.Extension.ToLower()
    $isText = $allowedExtensions -contains $ext
    if (-not $isText -and $lowerName -notlike "*.env" -and $lowerName -ne ".env") {
        continue
    }
    $scanned = $scanned + 1
    $text = [IO.File]::ReadAllText($file.FullName, [Text.UTF8Encoding]::new($false))
    $relative = $file.FullName.Replace($out + "\", "")

    foreach ($rule in $rules) {
        $found = [Regex]::Matches($text, $rule.regex)
        foreach ($m in $found) {
            if ($rule.isIp -and $exampleIps.Contains($m.Value)) {
                continue
            }
            [void]$hits.Add($relative + "  ->  " + $rule.name + "  (" + $m.Value + ")")
        }
    }
}

Write-Host ("Privacy scan: {0} of {1} files inspected" -f $scanned, $allFiles.Count)

if ($hits.Count -gt 0) {
    Write-Host "PRIVACY SCAN FAILED -- bundle kept for inspection at:" -ForegroundColor Red
    Write-Host ("  " + $out) -ForegroundColor Red
    foreach ($h in $hits) {
        Write-Host ("  " + $h) -ForegroundColor Red
    }
    exit 1
}
Write-Host "Privacy scan passed (no LAN IPs, absolute paths, tokens, or placeholders)" -ForegroundColor Green

# 5) Package.
#
# 版本号从 plugin.yaml 读，不再写死 —— 之前写死成 1.0.0，
# 升版本时很容易漏改，产出的包名和实际版本对不上。
$pluginVersion = "0.0.0"
$yamlPath = Join-Path $root "sakura_remote\plugin.yaml"
if (Test-Path $yamlPath) {
    $versionLine = Select-String -Path $yamlPath -Pattern '^version:\s*(.+)$' | Select-Object -First 1
    if ($versionLine) {
        $pluginVersion = $versionLine.Matches[0].Groups[1].Value.Trim().Trim('"').Trim("'")
    }
}
Write-Host "Plugin version: $pluginVersion"

Compress-Archive -Path (Join-Path $out "plugin\*") `
    -DestinationPath (Join-Path $out "plugin-sakura.remote-$pluginVersion.zip") -Force
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
