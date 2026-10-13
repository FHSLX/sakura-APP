# 编译并运行「显示 / 穿透偏好」真实生产逻辑行为测试（TS-03 / TS-04 / TS-05 / TS-06）。
#
# 被测的是真实生产类：DisplayPreferences / PetConfig / ConnectionProfile。
# Android 的 Context / SharedPreferences 由 tests/androidstub-overlay 的内存替身
# 提供（真实存值），因此断言的是「落盘后再回读」的值，不做「只改文字」的假保存。
#
# 覆盖：
#   - 无偏好时穿透默认开、浮动默认关；
#   - 明确选择后 has/value 正确、重复读保持；
#   - applyDisplayPreferences 的 ok / invalid / 回读一致 / 幂等 / 单 key 隔离；
#   - 失败响应只含稳定安全字符。
#
# Usage: pwsh -NoProfile -File tests\touchsettings_behavior_check.ps1
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
    'DisplayPreferences.java',
    'PassThroughAlpha.java',
    'PetConfig.java',
    'ConnectionProfile.java'
) | ForEach-Object { Join-Path $srcDir $_ }

$testFiles = @(
    (Join-Path $root 'tests\java\com\sakura\remote\TouchSettingsBehaviorTest.java')
)

if (-not $Out) { $Out = Join-Path $env:TEMP 'sakura-touchsettings-classes' }
$out = $Out

foreach ($file in $prodFiles + $testFiles) {
    if (-not (Test-Path -LiteralPath $file)) { throw "missing $file" }
}
if (-not (Test-Path -LiteralPath $stubDir)) { throw "missing $stubDir" }

$stubFiles = @(Get-ChildItem -LiteralPath $stubDir -Recurse -File -Filter *.java |
    # 遗留的 RemoteBridge 替身是空编译单元，排除以免与真实类同名
    Where-Object { $_.Name -ne 'RemoteBridge.java' } |
    Select-Object -ExpandProperty FullName)
if ($stubFiles.Count -eq 0) { throw "no stub sources under $stubDir" }

Remove-Item -LiteralPath $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $out -Force | Out-Null

$javac = Get-Command javac -ErrorAction SilentlyContinue
if (-not $javac) { throw 'javac not found on PATH' }

& javac -encoding UTF-8 -d $out @prodFiles @stubFiles @testFiles
if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }

Write-Output '--- TouchSettingsBehaviorTest ---'
& java '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' `
    -cp $out com.sakura.remote.TouchSettingsBehaviorTest
exit $LASTEXITCODE
