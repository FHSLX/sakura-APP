# 编译并运行**真机显示链路运行时修复（FIX-RUNTIME-01..05）真实生产逻辑**行为测试。
#
# 与 blank_overlay_real_adapter_check.ps1 的区别：
#   这里额外把真实 **RemoteBridge** 拉进编译与断言范围（tests/androidstub-overlay
#   里原有的 RemoteBridge 替身已废弃为空编译单元），因为本轮修复的核心之一
#   就是「桥线程 → 主线程封送」：必须在真实桥对象上从后台线程调用，才能证明
#   落点绝不在桥线程执行。
#
# 覆盖：
#   - 桥线程 → 主线程封送（真实 RemoteBridge + RenderSignalRouter + RenderSignalSink）；
#   - 权威代际与 load→retry→ready 全链路、迟到信号丢弃；
#   - 加载期有界可见卡片 / READY 升级 FULL / 隐藏理由优先；
#   - 去敏限频 Log 诊断（白名单 + 限频 + 反刷屏）。
#
# Usage: powershell -NoProfile -File tests\runtimefix_behavior_check.ps1
#   -Out 输出目录（默认临时目录）

param(
    [string]$Out = ""
)

$ErrorActionPreference = 'Stop'
try {
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    $OutputEncoding = [System.Text.Encoding]::UTF8
} catch { }

$root = Split-Path -Parent $PSScriptRoot
$srcDir = Join-Path $root 'phone_app\android\app\src\main\java\com\sakura\remote'
$stubDir = Join-Path $root 'tests\androidstub-overlay'

# 真实生产源码（被测对象）
$prodFiles = @(
    'OverlayVisibilityController.java',
    'RenderAvailabilityState.java',
    'PetWindow.java',
    'OverlaySurface.java',
    'OverlaySurfaceHost.java',
    'RenderSignalSink.java',
    'RenderSignalRouter.java',
    'RuntimeDiagnostics.java',
    'CaptureOrientation.java',
    'FileChooserBridge.java',
    'OverlayFileChooser.java',
    'FileChooserActivity.java',
    'RemoteBridge.java'
) | ForEach-Object { Join-Path $srcDir $_ }

$testFiles = @(
    (Join-Path $root 'tests\java\com\sakura\remote\OverlayRenderRoutingTest.java'),
    (Join-Path $root 'tests\java\com\sakura\remote\BlankOverlayRealAdapterTest.java')
)

if (-not $Out) { $Out = Join-Path $env:TEMP 'sakura-runtimefix-classes' }
$out = $Out

foreach ($file in $prodFiles + $testFiles) {
    if (-not (Test-Path -LiteralPath $file)) { throw "missing $file" }
}
if (-not (Test-Path -LiteralPath $stubDir)) { throw "missing $stubDir" }

$stubFiles = @(Get-ChildItem -LiteralPath $stubDir -Recurse -File -Filter *.java |
    Select-Object -ExpandProperty FullName)
if ($stubFiles.Count -eq 0) { throw "no stub sources under $stubDir" }

Remove-Item -LiteralPath $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $out -Force | Out-Null

$javac = Get-Command javac -ErrorAction SilentlyContinue
if (-not $javac) { throw 'javac not found on PATH' }

& javac -encoding UTF-8 -proc:none -d $out @prodFiles @stubFiles @testFiles
if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }

$failed = 0

Write-Output '--- OverlayRenderRoutingTest（FIX-RUNTIME-01/02/03/05 真实生产类） ---'
& java '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' `
    -cp $out com.sakura.remote.OverlayRenderRoutingTest
if ($LASTEXITCODE -ne 0) { $failed = 1 }

Write-Output '--- BlankOverlayRealAdapterTest（FIX-BLANK-01 + 卡片语义回归） ---'
& java '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' `
    -cp $out com.sakura.remote.BlankOverlayRealAdapterTest
if ($LASTEXITCODE -ne 0) { $failed = 1 }

exit $failed
