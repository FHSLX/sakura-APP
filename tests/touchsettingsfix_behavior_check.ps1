# 真机穿透 / 设置修复（TS-01..TS-07）的**相关行为组**离线验证与证据记录。
#
# 只跑与本次改动直接相关的行为测试 + 必要回归，不跑完整 aggregate：
#   - Node 前端行为：复杂轮廓/细线/非整除足迹、上限一致性、真实 Sakura PNG、
#     App 设置保存与开关、页面会话接线；
#   - 独立复审探针（validation-touchsettingsfix-20261011/codex-thin-pixel-probe.js）；
#   - JVM 真实生产逻辑：偏好 commit 回执、地址同步、真实适配层、运行时/空白窗回归；
#   - Python 静态资源与页面接线。
#
# 结果写入 validation-touchsettingsfix-20261011/test-results-20261011.json 与 .log。
#
# Usage: pwsh -NoProfile -File tests\touchsettingsfix_behavior_check.ps1

param(
    [string]$OutDir = ""
)

$ErrorActionPreference = 'Stop'
try {
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    $OutputEncoding = [System.Text.Encoding]::UTF8
} catch { }

$root = Split-Path -Parent $PSScriptRoot
if (-not $OutDir) { $OutDir = Join-Path $root 'validation-touchsettingsfix-20261011' }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$logPath = Join-Path $OutDir 'test-results-20261011.log'
$jsonPath = Join-Path $OutDir 'test-results-20261011.json'

# JDK 位置（任务指定）；已设置则不覆盖。javac/java 不在 PATH 时补上。
$jdk = 'C:/Program Files/Microsoft/jdk-21.0.9.10-hotspot'
if (-not $env:JAVA_HOME -and (Test-Path $jdk)) {
    $env:JAVA_HOME = $jdk
    $env:PATH = (Join-Path $jdk 'bin') + ';' + $env:PATH
}

$results = New-Object System.Collections.ArrayList
$logLines = New-Object System.Collections.ArrayList

function Add-Log([string]$text) {
    [void]$logLines.Add($text)
    Write-Output $text
}

function Invoke-Case([string]$name, [string]$kind, [string]$command, [string[]]$arguments) {
    $started = Get-Date
    Add-Log ("`n===== {0} ({1}) =====" -f $name, $kind)
    $output = & $command @arguments 2>&1
    $code = $LASTEXITCODE
    $output | ForEach-Object { Add-Log ([string]$_) }
    $elapsed = [math]::Round(((Get-Date) - $started).TotalSeconds, 1)
    Add-Log ("----- {0}: exit={1} ({2}s)" -f $name, $code, $elapsed)
    [void]$results.Add([ordered]@{
        name = $name
        kind = $kind
        exitCode = $code
        seconds = $elapsed
        status = if ($code -eq 0) { 'pass' } else { 'fail' }
    })
}

# ---- Node 前端行为（真实 static/*.js）----
$nodeTests = @(
    'page_session_check.js',
    'web_session_wiring_check.js',
    'touch_region_geometry_check.js',
    'touch_region_reviewfix_check.js',
    'touch_region_limit_check.js',
    'touch_region_real_png_check.js',
    'app_settings_check.js'
)
foreach ($test in $nodeTests) {
    Invoke-Case $test 'node' 'node' @((Join-Path $root ('tests\' + $test)))
}

# ---- 独立复审探针（保留原样，作为外部证据）----
$probe = Join-Path $OutDir 'codex-thin-pixel-probe.js'
if (Test-Path $probe) {
    Invoke-Case 'codex-thin-pixel-probe.js' 'probe' 'node' @($probe)
} else {
    Add-Log '[SKIP] codex-thin-pixel-probe.js 不在 validation 目录（公开克隆仓库不带）'
}

# ---- JVM 真实生产逻辑 ----
$jvmTests = @(
    'touchsettings_behavior_check.ps1',
    'addressfix_behavior_check.ps1',
    'real_adapter_behavior_check.ps1',
    'runtimefix_behavior_check.ps1',
    'blank_overlay_real_adapter_check.ps1'
)
foreach ($test in $jvmTests) {
    Invoke-Case $test 'jvm' 'pwsh' @('-NoProfile', '-File', (Join-Path $root ('tests\' + $test)))
}

# ---- Python 静态资源与页面接线（公开克隆仓库也应有；缺 python 则 SKIP）----
$python = Get-Command py -ErrorAction SilentlyContinue
if ($python) {
    Invoke-Case 'touch_region_static_check.py' 'python' 'py' @('-3.13', (Join-Path $root 'tests\touch_region_static_check.py'))
} else {
    $python = Get-Command python -ErrorAction SilentlyContinue
    if ($python) {
        Invoke-Case 'touch_region_static_check.py' 'python' 'python' @((Join-Path $root 'tests\touch_region_static_check.py'))
    } else {
        Add-Log '[SKIP] 未找到 python，跳过静态资源检查'
    }
}

$failed = @($results | Where-Object { $_.exitCode -ne 0 })
$summary = [ordered]@{
    generatedBy = 'tests/touchsettingsfix_behavior_check.ps1'
    timezone = 'Asia/Shanghai'
    generatedAt = (Get-Date).ToString('o')
    total = $results.Count
    passed = $results.Count - $failed.Count
    failed = $failed.Count
    results = $results
}

$logLines -join "`n" | Set-Content -LiteralPath $logPath -Encoding utf8
$summary | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $jsonPath -Encoding utf8

Write-Output ''
Write-Output ("{0}/{1} 项通过，失败 {2}" -f $summary.passed, $summary.total, $summary.failed)
Write-Output ("results: {0}" -f $jsonPath)
if ($failed.Count -gt 0) { exit 1 }
exit 0
