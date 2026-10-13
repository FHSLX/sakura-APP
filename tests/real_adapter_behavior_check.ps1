# 编译并运行**真实生产适配层**行为测试（INT-02..INT-07）。
#
# 与 integration_behavior_check.ps1 的区别：
#   这里编译并实例化真实生产类 CaptureService / CapturePermissionActivity /
#   OverlayTouchRegionController（以及它们真实调用的 CaptureCoordinator 与各策略），
#   Android 系统接口由 tests/androidstub 的最小替身提供（可控 Handler 队列、焦点、
#   surface、projection、显示创建计数、假 Coordinator 目标）。
#   测试代码里没有复制 onStartCommand / onResume / abort / 区域提交等生产控制流。
#
# Usage: powershell -NoProfile -File tests\real_adapter_behavior_check.ps1
#   -Out  输出目录（默认临时目录；受限 shell 可能无法写既有源码子目录）

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
$stubDir = Join-Path $root 'tests\androidstub'

# 真实生产源码（被测对象）
$prodFiles = @(
    'AppCaptureYield.java',
    'CaptureService.java',
    'CapturePermissionActivity.java',
    'OverlayTouchRegionController.java',
    'CaptureCoordinator.java',
    'CaptureRequestRegistry.java',
    'PageSessionRegistry.java',
    'CaptureStartPolicy.java',
    'CaptureConsentPolicy.java',
    'RegionSessionGate.java',
    'RegionSnapshotStore.java',
    'RegionSessionState.java',
    'ScreenCapturer.java'
) | ForEach-Object { Join-Path $srcDir $_ }

$testFile = Join-Path $root 'tests\java\com\sakura\remote\RealAdapterBehaviorTest.java'
$probeFile = Join-Path $root 'tests\java\com\sakura\remote\RegionLateLookupProbe.java'

if (-not $Out) { $Out = Join-Path $env:TEMP 'sakura-real-adapter-classes' }
$out = $Out

foreach ($file in $prodFiles + @($testFile, $probeFile)) {
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

& javac -encoding UTF-8 -d $out @prodFiles @stubFiles @($testFile, $probeFile)
if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }

Write-Output '--- RegionLateLookupProbe (INT-04 真实生产 RegionSessionState) ---'
& java '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -cp $out com.sakura.remote.RegionLateLookupProbe
if ($LASTEXITCODE -ne 0) {
    Write-Output "RegionLateLookupProbe FAILED (exit $LASTEXITCODE)"
    exit 1
}

Write-Output '--- RealAdapterBehaviorTest ---'
& java '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -cp $out com.sakura.remote.RealAdapterBehaviorTest
exit $LASTEXITCODE
