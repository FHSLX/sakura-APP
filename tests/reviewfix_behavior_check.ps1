# 编译并运行第二轮复审修正（RV-01..RV-09）的纯 JVM 行为测试。
#
# 这些类没有 Android import，因此可以用 javac/java 直接跑真实决策代码：
# 页面会话登记、区域会话/revision 裁决、快照保留、授权 Activity 生命周期、
# 服务启动裁决、授权阶段超时。
#
# Usage: powershell -NoProfile -File tests\reviewfix_behavior_check.ps1
#
# -Out 默认写到临时目录：受限 shell 可能无法写既有源码子目录。

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
$sources = @(
    'CaptureRequestRegistry.java',
    'PageSessionRegistry.java',
    'RegionSessionGate.java',
    'RegionSnapshotStore.java',
    'CaptureConsentPolicy.java',
    'CaptureStartPolicy.java'
) | ForEach-Object { Join-Path $srcDir $_ }
$tests = @(
    (Join-Path $root 'tests\java\com\sakura\remote\ReviewFixBehaviorTest.java'),
    (Join-Path $root 'tests\java\com\sakura\remote\CaptureRequestRegistryTest.java')
)
if (-not $Out) { $Out = Join-Path $env:TEMP 'sakura-reviewfix-classes' }
$out = $Out

foreach ($file in $sources + $tests) {
    if (-not (Test-Path -LiteralPath $file)) { throw "missing $file" }
}

Remove-Item -LiteralPath $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $out -Force | Out-Null

$javac = (Get-Command javac -ErrorAction SilentlyContinue)
if (-not $javac) { throw 'javac not found on PATH' }

$failed = 0
& javac -d $out @sources @tests
if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }

Write-Output '--- ReviewFixBehaviorTest ---'
& java '-Dfile.encoding=UTF-8' -cp $out com.sakura.remote.ReviewFixBehaviorTest
if ($LASTEXITCODE -ne 0) { $failed = 1 }
Write-Output '--- CaptureRequestRegistryTest ---'
& java '-Dfile.encoding=UTF-8' -cp $out com.sakura.remote.CaptureRequestRegistryTest
if ($LASTEXITCODE -ne 0) { $failed = 1 }

exit $failed
