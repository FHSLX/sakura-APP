# Install the sakura_remote plugin into a Sakura installation.
#
# Usage (run from the repository root):
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\install_to_sakura.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\install_to_sakura.ps1 -SakuraRoot "F:\sakura"
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\install_to_sakura.ps1 -Disable
#
# Layout it produces (two DIFFERENT folders on purpose):
#   <root>\plugins\user\<id>\        plugin code      (updating the plugin replaces this)
#   <root>\data\plugins\<id>\        user data+config (never overwritten by an update)
#
# A shipped config.json is NOT copied into the code folder: PluginConfig merges
# <code>\config.json with <data>\config.json, so a stale copy in the code folder
# would silently override the user's saved settings on every reinstall.
#
# ASCII-only on purpose so Windows PowerShell 5.1 parses it under any code page.

[CmdletBinding()]
param(
    [string]$SakuraRoot = "F:\sakura",
    [string]$PluginId = "sakura.remote",
    [switch]$Disable,
    [switch]$Force
)

$ErrorActionPreference = "Stop"

$repo = Split-Path -Parent $PSScriptRoot

# 插件源码可能有两种布局：
#   - Registry 投稿要求 plugin.yaml 在**仓库根目录**（当前布局）
#   - 早期在 sakura_remote/ 子目录下
# 两个都认，省得改了布局就装不上。
$source = ""
foreach ($candidate in @($repo, (Join-Path $repo "sakura_remote"))) {
    if (Test-Path (Join-Path $candidate "plugin.yaml")) {
        $source = $candidate
        break
    }
}
if (-not $source) {
    throw "Plugin source not found (no plugin.yaml in $repo or $repo\sakura_remote)"
}
if (-not (Test-Path (Join-Path $SakuraRoot "sakura.exe"))) {
    Write-Warning "sakura.exe not found under $SakuraRoot - please check -SakuraRoot."
}

$codeTarget = Join-Path (Join-Path $SakuraRoot "plugins\user") $PluginId
$dataTarget = Join-Path (Join-Path $SakuraRoot "data\plugins") $PluginId

Write-Host "Plugin code -> $codeTarget"
New-Item -ItemType Directory -Force -Path $codeTarget | Out-Null
Copy-Item -Path (Join-Path $source "*") -Destination $codeTarget -Recurse -Force
Remove-Item (Join-Path $codeTarget "config.json") -Force -ErrorAction SilentlyContinue
Get-ChildItem $codeTarget -Recurse -Directory -Filter "__pycache__" -ErrorAction SilentlyContinue |
    Remove-Item -Recurse -Force -ErrorAction SilentlyContinue

Write-Host "User data   -> $dataTarget"
New-Item -ItemType Directory -Force -Path $dataTarget | Out-Null
$configPath = Join-Path $dataTarget "config.json"
if ($Force -or -not (Test-Path $configPath)) {
    $skeleton = @'
{
  "enabled": true,
  "host": "127.0.0.1",
  "port": 8770,
  "token": "sakura",
  "autoplay": true,
  "tts_enabled": true
}
'@
    Set-Content -Path $configPath -Value $skeleton -Encoding UTF8
    Write-Host "Wrote default user config (CHANGE THE TOKEN before exposing the port)."
}
else {
    Write-Host "Kept existing user config: $configPath"
}
if ($Disable) {
    $json = Get-Content $configPath -Raw | ConvertFrom-Json
    $json.enabled = $false
    $json | ConvertTo-Json | Set-Content -Path $configPath -Encoding UTF8
    Write-Host "Set enabled=false in the user config."
}

Write-Host ""
Write-Host "Installed files:"
Get-ChildItem $codeTarget -Recurse -File | ForEach-Object {
    "  code  {0}  ({1} bytes)" -f $_.FullName.Substring($codeTarget.Length + 1), $_.Length
}

$pluginsYaml = Join-Path $SakuraRoot "config\plugins.yaml"
$enabledText = if ($Disable) { "false" } else { "true" }
if (Test-Path $pluginsYaml) {
    $backup = "$pluginsYaml.bak_sakura_remote"
    if (-not (Test-Path $backup)) {
        Copy-Item $pluginsYaml $backup
        Write-Host "Backed up: $backup"
    }
    $lines = @(Get-Content $pluginsYaml)
    $index = -1
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i].Trim() -eq "- id: $PluginId") { $index = $i; break }
    }
    if ($index -ge 0) {
        if ($index + 1 -lt $lines.Count -and $lines[$index + 1] -match "^\s*enabled:") {
            $lines[$index + 1] = "  enabled: $enabledText"
        }
        else {
            $lines = $lines[0..$index] + @("  enabled: $enabledText") + $lines[($index + 1)..($lines.Count - 1)]
        }
        Set-Content -Path $pluginsYaml -Value $lines -Encoding UTF8
        Write-Host "Updated plugins.yaml entry for $PluginId (enabled=$enabledText)."
    }
    else {
        Add-Content -Path $pluginsYaml -Value "- id: $PluginId"
        Add-Content -Path $pluginsYaml -Value "  enabled: $enabledText"
        Write-Host "Appended $PluginId to plugins.yaml (enabled=$enabledText)."
    }
}
else {
    Write-Warning "$pluginsYaml not found - enable the plugin manually."
}

Write-Host ""
Write-Host "Next steps:"
Write-Host "  1. Restart Sakura (the plugin list is read at startup)."
Write-Host "  2. Settings -> 'Phone Remote' -> set port and token, then Save (touch a field so the panel enables Save)."
Write-Host "  3. Open on the phone: http://<PC-IP>:<port>/?token=<your-token>"
Write-Host ""
Write-Host "User config ($configPath):"
Get-Content $configPath -Raw
