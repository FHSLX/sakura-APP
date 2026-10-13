# 空白悬浮窗修复（FIX-BLANK-01/02）完整回归跑批。
#
# 分类记录（报告里区分模型 / 真实生产代码 / web / python）：
#   - model   ：纯 Java 策略/状态类（IntegrationBehaviorTest / ReviewFixBehaviorTest /
#               CaptureRequestRegistryTest）—— 只证明模型；
#   - real    ：真实生产类 + 最小 Android 替身：
#                 · real_adapter_behavior_check.ps1（CaptureService / 控制器 / 会话）
#                 · blank_overlay_real_adapter_check.ps1（本轮可见性链路，
#                   OverlayVisibilityController / RenderAvailabilityState /
#                   PetWindow / OverlaySurface / OverlaySurfaceHost）；
#   - web     ：前端 JS 回归（真实 app.js / page_session.js 等）；
#   - python  ：插件侧静态与冒烟（可写副本，宿主沙箱不允许直接写 tests/_tmp）。
#
# Usage: pwsh -NoProfile -File tests\blank_overlay_regression_check.ps1 `
#          -Report validation-blank-overlay-20261011\tests.json `
#          -Log    validation-blank-overlay-20261011\tests.log

param(
    [string]$Report = "",
    [string]$Log = ""
)

$ErrorActionPreference = 'Continue'
try {
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    $OutputEncoding = [System.Text.Encoding]::UTF8
} catch { }

$root = Split-Path -Parent $PSScriptRoot
$testsDir = Join-Path $root 'tests'
$results = New-Object System.Collections.Generic.List[object]
$lines = New-Object System.Collections.Generic.List[string]

function Write-Both([string]$text) {
    Write-Output $text
    $lines.Add($text)
}

function Invoke-Case([string]$name, [string]$kind, [scriptblock]$body) {
    Write-Both ("=== {0} [{1}] ===" -f $name, $kind)
    $caseLog = & $body 2>&1 | Out-String
    $code = $LASTEXITCODE
    Write-Both $caseLog.TrimEnd()
    Write-Both ("--- {0} exit={1}" -f $name, $code)
    $results.Add([ordered]@{
        name = $name
        kind = $kind
        exitCode = $code
        passed = ($code -eq 0)
    })
    return $code
}

Push-Location $root
try {
    # ---- model 级 ----------------------------------------------------
    foreach ($t in @('integration_behavior_check.ps1', 'reviewfix_behavior_check.ps1',
                     'capture_registry_check.ps1')) {
        Invoke-Case $t 'model' { & (Join-Path $testsDir $t) } | Out-Null
    }

    # ---- real 级（真实生产类） ---------------------------------------
    Invoke-Case 'real_adapter_behavior_check.ps1' 'real' {
        & (Join-Path $testsDir 'real_adapter_behavior_check.ps1')
    } | Out-Null
    Invoke-Case 'blank_overlay_real_adapter_check.ps1' 'real' {
        & (Join-Path $testsDir 'blank_overlay_real_adapter_check.ps1')
    } | Out-Null

    # ---- web (node，真实前端源码) ------------------------------------
    foreach ($t in @('bubble_render_check.js', 'portrait_scale_check.js',
                     'tone_portrait_check.js', 'touch_region_geometry_check.js',
                     'touch_region_reviewfix_check.js', 'portrait_race_check.js',
                     'send_draft_check.js', 'page_session_check.js',
                     'web_session_wiring_check.js')) {
        Invoke-Case $t 'web' { & node (Join-Path $testsDir $t) } | Out-Null
    }

    # ---- python（可写副本） ------------------------------------------
    $copy = Join-Path $root 'validation-blank-overlay-20261011\plugin-run'
    if (Test-Path -LiteralPath $copy) { Remove-Item -LiteralPath $copy -Recurse -Force }
    New-Item -ItemType Directory -Path $copy -Force | Out-Null
    robocopy (Join-Path $root 'sakura_remote') (Join-Path $copy 'sakura_remote') /E /XD __pycache__ /XF *.pyc /NFL /NDL /NJH /NJS /NP | Out-Null
    robocopy $testsDir (Join-Path $copy 'tests') /E /XD __pycache__ _tmp cache /XF *.pyc /NFL /NDL /NJH /NJS /NP | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $copy 'tests\_tmp') -Force | Out-Null

    foreach ($t in @('touch_region_static_check.py', 'fake_host_smoke.py',
                     'plugin_runtime_smoke.py')) {
        Invoke-Case $t 'python' { & py -3 (Join-Path $copy ('tests\' + $t)) } | Out-Null
    }
} finally {
    Pop-Location
}

$passed = @($results | Where-Object { $_.passed }).Count
$failed = @($results | Where-Object { -not $_.passed }).Count
$summary = [ordered]@{
    generatedAt = [DateTimeOffset]::UtcNow.ToString('o')
    total = $results.Count
    passed = $passed
    failed = $failed
    results = $results
}
$json = $summary | ConvertTo-Json -Depth 5
Write-Both $json

if ($Log) {
    $logDir = Split-Path -Parent $Log
    if ($logDir) { New-Item -ItemType Directory -Path $logDir -Force | Out-Null }
    [System.IO.File]::WriteAllLines($Log, $lines, (New-Object System.Text.UTF8Encoding($false)))
}
if ($Report) {
    $reportDir = Split-Path -Parent $Report
    if ($reportDir) { New-Item -ItemType Directory -Path $reportDir -Force | Out-Null }
    [System.IO.File]::WriteAllText($Report, $json, (New-Object System.Text.UTF8Encoding($false)))
}

if ($failed -gt 0) {
    Write-Output ("REGRESSION FAILED: {0}/{1}" -f $failed, $results.Count)
    exit 1
}
Write-Output ("REGRESSION OK: {0}/{1}" -f $passed, $results.Count)
exit 0
