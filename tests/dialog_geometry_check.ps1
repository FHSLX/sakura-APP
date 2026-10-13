# 对话几何复修检查（不启动浏览器）。
#
# 1) 用真实 web_ui.render_page 生成 fixture 应用页；
# 2) 跑非浏览器行为测试（真实 app.js 函数：缩放只应用一次、偏移夹取）；
# 3) 跑独立断言 CLI 的离线自测（合成 DOM JSON：正确 vs 双重缩放/裁切）；
# 4) 若已有 Codex 浏览器运行落盘的 geometry-results.json，再跑真实 DOM JSON 断言。
#
# Codex 浏览器步骤（cua_repl，不用 headless/Playwright/CDP）：
#   node validation-dialog-geometry-20261013/fixture_server.js
#   打开 http://127.0.0.1:8781/?all=1  → 页面自动 POST DOM JSON 到 /results
#   然后 node validation-dialog-geometry-20261013/assert_geometry.js
#
# 用法: pwsh -NoProfile -File tests\dialog_geometry_check.ps1

$ErrorActionPreference = 'Stop'
try {
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    $OutputEncoding = [System.Text.Encoding]::UTF8
} catch { }

$root = Split-Path -Parent $PSScriptRoot
$geoDir = Join-Path $root 'validation-dialog-geometry-20261013'
if (-not (Test-Path -LiteralPath $geoDir)) { throw "missing $geoDir" }

Write-Output '--- render real page (fixture) ---'
& py -3.13 (Join-Path $geoDir 'render_page.py')
if ($LASTEXITCODE -ne 0) { throw "render_page failed with exit code $LASTEXITCODE" }

Write-Output '--- non-browser geometry behavior test ---'
& node (Join-Path $root 'tests\ux_media_voice_check.js')
if ($LASTEXITCODE -ne 0) { throw "behavior test failed with exit code $LASTEXITCODE" }

Write-Output '--- assertion CLI self-test (synthetic DOM JSON) ---'
& node (Join-Path $geoDir 'assert_geometry_selftest.js')
if ($LASTEXITCODE -ne 0) { throw "assertion self-test failed with exit code $LASTEXITCODE" }

$results = Join-Path $geoDir 'geometry-results.json'
if (Test-Path -LiteralPath $results) {
    Write-Output '--- browser DOM JSON assertions (Codex-run results) ---'
    & node (Join-Path $geoDir 'assert_geometry.js') $results
    if ($LASTEXITCODE -ne 0) { throw "geometry assertions failed with exit code $LASTEXITCODE" }
} else {
    Write-Output '--- browser DOM JSON: PENDING Codex browser run ---'
    Write-Output 'run: node validation-dialog-geometry-20261013/fixture_server.js'
    Write-Output 'then open http://127.0.0.1:8781/?all=1 via cua_repl (page POSTs /results)'
}
exit 0
