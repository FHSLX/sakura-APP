# 编译并运行 AppCaptureYield（App 桌面截屏让位/恢复）真实生产行为测试。
#
# 被测的是真实生产类 AppCaptureYield；Android 系统接口由 tests/androidstub 提供。
# 覆盖：请求 id 匹配、按记录 task id 恢复、旧恢复与新截图重叠失效、
# 缺 ActivityManager 不崩溃。另附 MEDIA-02 静态接线守卫。
#
# Usage: pwsh -NoProfile -File tests\appyield_behavior_check.ps1

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

$prodFiles = @(@('AppCaptureYield.java') | ForEach-Object { Join-Path $srcDir $_ })
$testFile = Join-Path $root 'tests\java\com\sakura\remote\AppYieldBehaviorTest.java'

if (-not $Out) { $Out = Join-Path $env:TEMP 'sakura-appyield-classes' }
$out = $Out

foreach ($file in $prodFiles + @($testFile)) {
    if (-not (Test-Path -LiteralPath $file)) { throw "missing $file" }
}
$stubFiles = @(Get-ChildItem -LiteralPath $stubDir -Recurse -File -Filter *.java |
    Select-Object -ExpandProperty FullName)

Remove-Item -LiteralPath $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $out -Force | Out-Null

$javac = Get-Command javac -ErrorAction SilentlyContinue
if (-not $javac) { throw 'javac not found on PATH' }

& javac -encoding UTF-8 -d $out @prodFiles @stubFiles @($testFile)
if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }

Write-Output '--- AppYieldBehaviorTest ---'
& java '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' `
    -cp $out com.sakura.remote.AppYieldBehaviorTest
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

# MEDIA-02 静态接线守卫：Android 14+ 明确请求默认整屏，旧系统保留原路径
$activity = Get-Content -LiteralPath (Join-Path $srcDir 'CapturePermissionActivity.java') -Raw
$guardFailed = $false
function Assert-Wiring([string]$name, [bool]$condition) {
    Write-Output ("[{0}] (wiring) {1}" -f ($(if ($condition) { 'PASS' } else { 'FAIL' })), $name)
    if (-not $condition) { $script:guardFailed = $true }
}
Assert-Wiring 'API34+ 使用 MediaProjectionConfig 请求默认整屏' `
    ($activity.Contains('MediaProjectionConfig') -and `
     $activity.Contains('createConfigForDefaultDisplay()'))
Assert-Wiring '整屏配置只在 UPSIDE_DOWN_CAKE 及以上分支' `
    ($activity.Contains('Build.VERSION_CODES.UPSIDE_DOWN_CAKE'))
Assert-Wiring '旧系统仍走无参数 createScreenCaptureIntent()' `
    ($activity.Contains('manager.createScreenCaptureIntent()'))

if ($guardFailed) { exit 1 }
exit 0
