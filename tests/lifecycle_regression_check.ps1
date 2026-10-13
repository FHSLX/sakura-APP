# 生命周期收口（INT-02..INT-07）完整回归跑批。
#
# 分类记录（报告里会区分）：
#   - model  ：纯 Java 策略/状态类（IntegrationBehaviorTest / ReviewFixBehaviorTest /
#              CaptureRequestRegistryTest）—— 只证明模型；
#   - real   ：真实生产类 + 最小 Android 替身（RealAdapterBehaviorTest /
#              RegionLateLookupProbe）—— 证明适配层接线；
#   - web/py ：前端与插件侧回归（真实 JS/Python 源码）；
#   - mutation：临时副本恢复旧缺陷，证明测试能抓住回归。
#
# Python 冒烟测试需要写 tests/fixtures 与 tests/_tmp：宿主文件沙箱拒绝直接写既有
# 目录，因此与上一轮一致，在可写副本中运行（源文件仍是被测的真实源码）。
#
# Usage: pwsh -NoProfile -File tests\lifecycle_regression_check.ps1 `
#          -Report validation-lifecycle-20261011\tests.json `
#          -Log    validation-lifecycle-20261011\tests.log

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
    # 每次用例前清零 $LASTEXITCODE：脚本内出现 robocopy 等原生命令时，它会把
    # 「上一条原生命令的退出码」带进来，导致用例被判失败（JSON 里 failed 却是 0）。
    $global:LASTEXITCODE = 0
    $caseLog = & $body 2>&1 | Out-String
    $code = $LASTEXITCODE
    if ($null -eq $code) { $code = 0 }
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

    # ---- real 级 -----------------------------------------------------
    Invoke-Case 'real_adapter_behavior_check.ps1' 'real' {
        & (Join-Path $testsDir 'real_adapter_behavior_check.ps1')
    } | Out-Null
    Invoke-Case 'real_adapter_mutation_check.ps1' 'mutation' {
        & (Join-Path $testsDir 'real_adapter_mutation_check.ps1') -Report (Join-Path $root 'validation-lifecycle-20261011\mutation-evidence.json')
    } | Out-Null

    # ---- web (node) --------------------------------------------------
    foreach ($t in @('bubble_render_check.js', 'portrait_scale_check.js',
                     'tone_portrait_check.js', 'touch_region_geometry_check.js',
                     'touch_region_reviewfix_check.js', 'portrait_race_check.js',
                     'send_draft_check.js', 'page_session_check.js',
                     'web_session_wiring_check.js')) {
        Invoke-Case $t 'web' { & node (Join-Path $testsDir $t) } | Out-Null
    }

    # ---- python（可写副本） ------------------------------------------
    $copy = Join-Path $root 'validation-lifecycle-20261011\plugin-run'
    if (Test-Path -LiteralPath $copy) { Remove-Item -LiteralPath $copy -Recurse -Force }
    New-Item -ItemType Directory -Path $copy -Force | Out-Null
    robocopy (Join-Path $root 'sakura_remote') (Join-Path $copy 'sakura_remote') /E /XD __pycache__ /XF *.pyc /NFL /NDL /NJH /NJS /NP | Out-Null
    robocopy $testsDir (Join-Path $copy 'tests') /E /XD __pycache__ _tmp cache /XF *.pyc /NFL /NDL /NJH /NJS /NP | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $copy 'tests\_tmp') -Force | Out-Null

    foreach ($t in @('touch_region_static_check.py', 'fake_host_smoke.py',
                     'plugin_runtime_smoke.py')) {
        Invoke-Case $t 'web' { & py -3 (Join-Path $copy ('tests\' + $t)) } | Out-Null
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
