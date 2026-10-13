# 编译并运行**空白悬浮窗修复（FIX-BLANK-01/02）真实生产逻辑**行为测试。
#
# 与 real_adapter_behavior_check.ps1 的区别：
#   这里面向本轮修复的可见性链路，编译并实例化真实生产类
#   OverlayVisibilityController / RenderAvailabilityState / PetWindow /
#   OverlaySurface / OverlaySurfaceHost，Android 界面与 WebView 接口由
#   tests/androidstub-overlay 的最小替身提供（与 tests/androidstub 互不干扰）。
#   测试代码里没有复制失败升降级/重试预算/代际丢弃/卡片形态等生产控制流。
#
# Usage: powershell -NoProfile -File tests\blank_overlay_real_adapter_check.ps1
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
    'CaptureOrientation.java',
    'FileChooserBridge.java',
    'OverlayFileChooser.java',
    'FileChooserActivity.java',
    'RemoteBridge.java'
) | ForEach-Object { Join-Path $srcDir $_ }

$testFile = Join-Path $root 'tests\java\com\sakura\remote\BlankOverlayRealAdapterTest.java'

if (-not $Out) { $Out = Join-Path $env:TEMP 'sakura-blank-overlay-classes' }
$out = $Out

foreach ($file in $prodFiles + @($testFile)) {
    if (-not (Test-Path -LiteralPath $file)) { throw "missing $file" }
}
if (-not (Test-Path -LiteralPath $stubDir)) { throw "missing $stubDir" }

$stubFiles = @(Get-ChildItem -LiteralPath $stubDir -Recurse -File -Filter *.java |
    # tests/androidstub-overlay 里遗留的 RemoteBridge 替身已废弃（FIX-RUNTIME-01 起
    # 真实生产 RemoteBridge 必须参与编译，否则桥线程封送链路失去覆盖）；
    # 它现在是空编译单元，且所在目录存在目录级删除拒绝项，故在编译列表中排除。
    Where-Object { $_.Name -ne 'RemoteBridge.java' } |
    Select-Object -ExpandProperty FullName)
if ($stubFiles.Count -eq 0) { throw "no stub sources under $stubDir" }

Remove-Item -LiteralPath $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $out -Force | Out-Null

$javac = Get-Command javac -ErrorAction SilentlyContinue
if (-not $javac) { throw 'javac not found on PATH' }

& javac -encoding UTF-8 -d $out @prodFiles @stubFiles @($testFile)
if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }

Write-Output '--- BlankOverlayRealAdapterTest (FIX-BLANK-01/02 真实生产类) ---'
& java '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -cp $out com.sakura.remote.BlankOverlayRealAdapterTest
exit $LASTEXITCODE
