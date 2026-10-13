# 本轮三项修复的真实行为测试：
#   A) 悬浮窗输入焦点策略（真实生产类 OverlayInputFocus / OverlayFocusRootView，
#      编译期用真实 android.jar，常量内联后可在纯 JVM 运行）；
#   B) 拍照入口（真实生产类 FileChooserBridge / OverlayFileChooser /
#      FileChooserActivity，系统接口用 tests/androidstub-overlay 功能性替身：
#      Bitmap/BitmapFactory/Matrix/ExifInterface 基于 ImageIO，超预算缩图与
#      EXIF 方向跑的是真实生产控制流）；
#   C) 图库选择回调回归（PickerBehaviorTest，含每次 launch 独立 requestCode）。
#
# Usage: pwsh -NoProfile -File tests\camera_focus_behavior_check.ps1

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
$androidJar = Join-Path $root 'phone_app\.android-sdk\platforms\android-36\android.jar'

if (-not $Out) { $Out = Join-Path $env:TEMP 'sakura-camera-focus-classes' }

# 递归删除前先解析绝对路径，并确认落在 workspace 或系统临时目录内。
$workspace = [System.IO.Path]::GetFullPath($root)
$tempRoot = [System.IO.Path]::GetFullPath($env:TEMP)
$outFull = [System.IO.Path]::GetFullPath($Out)
$inWorkspace = $outFull.StartsWith($workspace, [System.StringComparison]::OrdinalIgnoreCase)
$inTemp = $outFull.StartsWith($tempRoot, [System.StringComparison]::OrdinalIgnoreCase)
if (-not ($inWorkspace -or $inTemp)) {
    throw "refusing to clean output outside workspace/temp: $outFull"
}

foreach ($file in @($androidJar)) {
    if (-not (Test-Path -LiteralPath $file)) { throw "missing $file" }
}

$javac = Get-Command javac -ErrorAction SilentlyContinue
if (-not $javac) { throw 'javac not found on PATH' }

Remove-Item -LiteralPath $outFull -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $outFull -Force | Out-Null

$failed = 0

# ---- A) 输入焦点策略（真实 android.jar） ---------------------------------
$outA = Join-Path $outFull 'focus'
New-Item -ItemType Directory -Path $outA -Force | Out-Null
$focusSources = @(
    'OverlayInputFocus.java',
    'OverlayFocusRootView.java'
) | ForEach-Object { Join-Path $srcDir $_ }
$focusTest = Join-Path $root 'tests\java\com\sakura\remote\OverlayInputFocusTest.java'
foreach ($file in $focusSources + @($focusTest)) {
    if (-not (Test-Path -LiteralPath $file)) { throw "missing $file" }
}

& javac -encoding UTF-8 -cp $androidJar -d $outA @focusSources @($focusTest)
if ($LASTEXITCODE -ne 0) { throw "javac (focus) failed with exit code $LASTEXITCODE" }

Write-Output '--- OverlayInputFocusTest ---'
& java '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' `
    -cp "$outA;$androidJar" com.sakura.remote.OverlayInputFocusTest
if ($LASTEXITCODE -ne 0) { $failed = 1 }

# ---- B/C) 拍照 + 图库回调（功能性替身） ---------------------------------
$outB = Join-Path $outFull 'chooser'
New-Item -ItemType Directory -Path $outB -Force | Out-Null
$prodFiles = @(
    'CaptureOrientation.java',
    'FileChooserBridge.java',
    'OverlayFileChooser.java',
    'FileChooserActivity.java',
    'AppFileChooserClient.java'
) | ForEach-Object { Join-Path $srcDir $_ }
$testFiles = @(
    (Join-Path $root 'tests\java\com\sakura\remote\CameraCaptureBehaviorTest.java'),
    (Join-Path $root 'tests\java\com\sakura\remote\AppFileChooserClientTest.java'),
    (Join-Path $root 'tests\java\com\sakura\remote\PickerBehaviorTest.java')
)
foreach ($file in $prodFiles + $testFiles) {
    if (-not (Test-Path -LiteralPath $file)) { throw "missing $file" }
}
$stubFiles = @(Get-ChildItem -LiteralPath $stubDir -Recurse -File -Filter *.java |
    Select-Object -ExpandProperty FullName)
if ($stubFiles.Count -eq 0) { throw "no stub sources under $stubDir" }

& javac -encoding UTF-8 -d $outB @prodFiles @stubFiles @testFiles
if ($LASTEXITCODE -ne 0) { throw "javac (chooser) failed with exit code $LASTEXITCODE" }

Write-Output '--- CameraCaptureBehaviorTest ---'
& java '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' `
    -cp $outB com.sakura.remote.CameraCaptureBehaviorTest
if ($LASTEXITCODE -ne 0) { $failed = 1 }

Write-Output '--- AppFileChooserClientTest ---'
& java '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' `
    -cp $outB com.sakura.remote.AppFileChooserClientTest
if ($LASTEXITCODE -ne 0) { $failed = 1 }

Write-Output '--- PickerBehaviorTest ---'
& java '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' `
    -cp $outB com.sakura.remote.PickerBehaviorTest
if ($LASTEXITCODE -ne 0) { $failed = 1 }

exit $failed
