# 编译并运行「聊天与立绘连接地址分裂」补充修复的真实生产逻辑行为测试。
#
# 被测的是真实生产类：ConnectionProfile / PetConfig / ConnectionSync / RemoteBridge。
# Android 系统接口由 tests/androidstub-overlay 的最小替身提供；其中 SharedPreferences
# 是真实存值的内存实现并记录每次落盘快照，用来证明 origin + token 原子写入。
#
# 覆盖：
#   - 单一规范化连接配置（http/https、默认/显式端口、IPv6、非法主机拒绝）；
#   - 原生旧主机 + 聊天页新 IP → 开启/重载立绘指向新 origin 且 token 同步；
#   - 无效地址 / 本地 Capacitor 页面 / 空 token / 非 HTTP(S) 不覆盖；
#   - 相同地址重复上报不触发重载；旧立绘页面不回写主配置；
#   - 去敏连接诊断只含类别与一致性。
#
# Usage: powershell -NoProfile -File tests\addressfix_behavior_check.ps1
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
    'ConnectionProfile.java',
    'DisplayPreferences.java',
    'PetConfig.java',
    'ConnectionSync.java',
    'RenderSignalSink.java',
    'RenderSignalRouter.java',
    'RemoteBridge.java'
) | ForEach-Object { Join-Path $srcDir $_ }

$testFiles = @(
    (Join-Path $root 'tests\java\com\sakura\remote\AddressFixBehaviorTest.java')
)

if (-not $Out) { $Out = Join-Path $env:TEMP 'sakura-addressfix-classes' }
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

Write-Output '--- AddressFixBehaviorTest（真实 PetConfig / ConnectionSync / RemoteBridge） ---'
& java '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' `
    -cp $out com.sakura.remote.AddressFixBehaviorTest
exit $LASTEXITCODE
