# Phone debugging helper for the sakura_remote plugin.
#
# Prerequisites (one-time, done by you):
#   1. On the phone: enable Developer options -> USB debugging.
#   2. Connect the phone over USB and tap "Allow USB debugging".
#   3. Have adb available. Either:
#        - unpack Android Platform Tools into tools\platform-tools
#          https://developer.android.com/tools/releases/platform-tools
#        - or put platform-tools on PATH
#        - or pass -AdbPath
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\connect_phone.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\connect_phone.ps1 -AppPort 8770
#
# It only establishes an adb port tunnel and lists Chrome debug targets.
# Nothing is installed and no phone setting is changed.
#
# ASCII-only on purpose so Windows PowerShell 5.1 parses it under any code page.

[CmdletBinding()]
param(
    [string]$AdbPath = "",
    [int]$ChromePort = 9222,
    [int]$AppPort = 8770,
    [string]$PcAddress = "",   # 电脑的局域网 IP，例如 192.168.1.100
    [switch]$SkipDevTools
)

$ErrorActionPreference = "Continue"

function Resolve-Adb {
    param([string]$Explicit)
    if ($Explicit -and (Test-Path $Explicit)) { return $Explicit }
    $local = Join-Path $PSScriptRoot "platform-tools\adb.exe"
    if (Test-Path $local) { return $local }
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    return ""
}

$adb = Resolve-Adb -Explicit $AdbPath
if (-not $adb) {
    Write-Host "adb not found." -ForegroundColor Yellow
    Write-Host ""
    Write-Host "Choose one:"
    Write-Host "  A. Download Android Platform Tools (~15 MB):"
    Write-Host "     https://developer.android.com/tools/releases/platform-tools"
    Write-Host "     Unpack so that this file exists: $PSScriptRoot\platform-tools\adb.exe"
    Write-Host "  B. Add platform-tools to PATH."
    Write-Host ""
    Write-Host "No adb needed at all if the phone is on the same WiFi - just open:"
    Write-Host "     http://${PcAddress}:$AppPort/?token=YOUR_TOKEN"
    exit 2
}

Write-Host "adb: $adb"
Write-Host ""
Write-Host "=== Connected devices ==="
& $adb devices -l
$devices = (& $adb devices) | Select-Object -Skip 1 | Where-Object { $_ -match "\sdevice$" }
if (-not $devices) {
    Write-Host ""
    Write-Host "No authorized device detected." -ForegroundColor Yellow
    Write-Host "Check the USB cable (data-capable), the 'Allow USB debugging' prompt, and vendor drivers."
    exit 1
}

Write-Host ""
Write-Host "=== Port tunnel ==="
Write-Host "phone localhost:$ChromePort  ->  PC localhost:$ChromePort"
& $adb forward "tcp:$ChromePort" "tcp:$ChromePort"

if (-not $SkipDevTools) {
    Start-Sleep -Seconds 1
    Write-Host ""
    Write-Host "=== Chrome debug targets on the phone ==="
    try {
        $version = Invoke-RestMethod -Uri "http://127.0.0.1:$ChromePort/json/version" -TimeoutSec 5
        Write-Host "Browser: $($version.Browser)"
        $list = Invoke-RestMethod -Uri "http://127.0.0.1:$ChromePort/json/list" -TimeoutSec 5
        $pages = @($list | Where-Object { $_.type -eq 'page' })
        if ($pages.Count -gt 0) {
            $pages | ForEach-Object {
                Write-Host ("  [{0}] {1}" -f $_.title, $_.url)
            }
        }
        else {
            Write-Host "  No page open yet. On the phone, open Chrome and visit:"
            Write-Host "      chrome://inspect"
            Write-Host "  Enable USB web debugging if prompted, then open the Sakura page."
        }
    }
    catch {
        Write-Host "Could not reach the phone Chrome debug port." -ForegroundColor Yellow
        Write-Host "Open chrome://inspect in the phone Chrome and retry."
    }
}

Write-Host ""
Write-Host "=== Phone URL ==="
Write-Host "LAN :  http://${PcAddress}:$AppPort/?token=YOUR_TOKEN"
Write-Host "USB :  http://127.0.0.1:$AppPort/?token=YOUR_TOKEN  (some setups)"
Write-Host ""
Write-Host "Tip - capture the phone screen to the PC:"
Write-Host "  & '$adb' exec-out screencap -p > phone.png"
