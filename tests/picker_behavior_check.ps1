# 编译并运行 MEDIA-01 图片选择回调的真实生产行为测试。
#
# 被测：FileChooserBridge / OverlayFileChooser / FileChooserActivity（均为真实生产类）。
# 系统接口由 tests/androidstub-overlay 提供。
# 覆盖：回调恰好一次、abandon 契约、owner 作用域取消、替换旧请求、
# startActivity 失败契约、有界大小校验（未知大小/超大/非图片/file://）。
#
# Usage: pwsh -NoProfile -File tests\picker_behavior_check.ps1

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

$prodFiles = @(
    'CaptureOrientation.java',
    'FileChooserBridge.java',
    'OverlayFileChooser.java',
    'FileChooserActivity.java'
) | ForEach-Object { Join-Path $srcDir $_ }
$testFile = Join-Path $root 'tests\java\com\sakura\remote\PickerBehaviorTest.java'

if (-not $Out) { $Out = Join-Path $env:TEMP 'sakura-picker-classes' }
$out = $Out

foreach ($file in $prodFiles + @($testFile)) {
    if (-not (Test-Path -LiteralPath $file)) { throw "missing $file" }
}
# androidstub-overlay 里的 com/sakura/remote/RemoteBridge.java 是测试桩，编译无冲突
$stubFiles = @(Get-ChildItem -LiteralPath $stubDir -Recurse -File -Filter *.java |
    Select-Object -ExpandProperty FullName)

Remove-Item -LiteralPath $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $out -Force | Out-Null

$javac = Get-Command javac -ErrorAction SilentlyContinue
if (-not $javac) { throw 'javac not found on PATH' }

& javac -encoding UTF-8 -d $out @prodFiles @stubFiles @($testFile)
if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }

Write-Output '--- PickerBehaviorTest ---'
& java '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' `
    -cp $out com.sakura.remote.PickerBehaviorTest
exit $LASTEXITCODE
