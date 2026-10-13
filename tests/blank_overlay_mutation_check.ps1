# Mutation 证据：把旧缺陷恢复到**临时副本**，用同一份真实生产逻辑测试重跑，
# 证明测试确实能抓住这些回归；正式源码不被修改。
#
# Usage: pwsh -NoProfile -File tests\blank_overlay_mutation_check.ps1 `
#          -Report validation-blank-overlay-20261011\mutation-evidence.json `
#          -Log    validation-blank-overlay-20261011\mutation-evidence.log

param(
    [string]$Report = "",
    [string]$Log = "",
    [string]$WorkDir = ""
)

$ErrorActionPreference = 'Stop'
try {
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    $OutputEncoding = [System.Text.Encoding]::UTF8
} catch { }

$root = Split-Path -Parent $PSScriptRoot
$srcDir = Join-Path $root 'phone_app\android\app\src\main\java\com\sakura\remote'
$stubDir = Join-Path $root 'tests\androidstub-overlay'
$mutationsFile = Join-Path $root 'tests\mutations\blank-overlay-mutations.json'
$testFile = Join-Path $root 'tests\java\com\sakura\remote\BlankOverlayRealAdapterTest.java'

$prodFiles = @(
    'OverlayVisibilityController.java',
    'RenderAvailabilityState.java',
    'PetWindow.java',
    'OverlaySurface.java',
    'OverlaySurfaceHost.java',
    # FIX-RUNTIME-01 起 PetWindow 引用真实 RemoteBridge（桥线程→主线程封送），
    # 不一起编译会直接编译失败 —— 与 blank_overlay_real_adapter_check.ps1 对齐。
    'RenderSignalSink.java',
    'RenderSignalRouter.java',
    'RemoteBridge.java',
    'FileChooserBridge.java',
    'OverlayFileChooser.java',
    'FileChooserActivity.java',
    'CaptureOrientation.java'
)

foreach ($file in $prodFiles) {
    $path = Join-Path $srcDir $file
    if (-not (Test-Path -LiteralPath $path)) { throw "missing $path" }
}
if (-not (Test-Path -LiteralPath $mutationsFile)) { throw "missing $mutationsFile" }

$mutations = (Get-Content -LiteralPath $mutationsFile -Raw | ConvertFrom-Json).mutations
$stubFiles = @(Get-ChildItem -LiteralPath $stubDir -Recurse -File -Filter *.java |
    # tests/androidstub-overlay 里遗留的 RemoteBridge 替身已废弃（空编译单元）；
    # 真实生产 RemoteBridge 已在上面的 $prodFiles 中参与编译，故排除该替身。
    Where-Object { $_.Name -ne 'RemoteBridge.java' } |
    Select-Object -ExpandProperty FullName)

if (-not $WorkDir) { $WorkDir = Join-Path $env:TEMP 'sakura-blank-overlay-mutation' }
$work = [System.IO.Path]::GetFullPath($WorkDir)
$workspacePrefix = [System.IO.Path]::GetFullPath($root).TrimEnd('\') + '\'
$tempPrefix = [System.IO.Path]::GetFullPath($env:TEMP).TrimEnd('\') + '\'
if (-not ($work.StartsWith($workspacePrefix, [System.StringComparison]::OrdinalIgnoreCase) -or $work.StartsWith($tempPrefix, [System.StringComparison]::OrdinalIgnoreCase))) {
    throw "refusing to clean mutation output outside workspace/temp: $work"
}
if (Test-Path -LiteralPath $work) { Remove-Item -LiteralPath $work -Recurse -Force }
New-Item -ItemType Directory -Path $work -Force | Out-Null

$lines = New-Object System.Collections.Generic.List[string]
function Write-Both([string]$text) {
    Write-Output $text
    $lines.Add($text)
}

$results = New-Object System.Collections.Generic.List[object]
$escaped = 0

# 把正式源码复制到临时副本（正式文件只读）
foreach ($file in $prodFiles) {
    Copy-Item -LiteralPath (Join-Path $srcDir $file) -Destination (Join-Path $work $file) -Force
}

foreach ($mutation in $mutations) {
    $target = Join-Path $work $mutation.file
    $original = [System.IO.File]::ReadAllText($target)
    if (-not $original.Contains($mutation.old)) {
        throw "mutation $($mutation.id): 未能在 $($mutation.file) 中定位旧缺陷片段"
    }
    [System.IO.File]::WriteAllText($target, $original.Replace($mutation.old, $mutation.new))

    $out = Join-Path $work ("classes-" + $mutation.id)
    if (Test-Path -LiteralPath $out) { Remove-Item -LiteralPath $out -Recurse -Force }
    New-Item -ItemType Directory -Path $out -Force | Out-Null

    $sourceFiles = @($prodFiles | ForEach-Object { Join-Path $work $_ })
    & javac -encoding UTF-8 -d $out @sourceFiles @stubFiles @($testFile) 2>&1 |
        Out-File -LiteralPath (Join-Path $work ($mutation.id + '.javac.log')) -Encoding utf8
    if ($LASTEXITCODE -ne 0) {
        throw "mutation $($mutation.id): 临时副本编译失败（见 $work\$($mutation.id).javac.log）"
    }

    $runLog = & java '-Dstdout.encoding=UTF-8' -cp $out com.sakura.remote.BlankOverlayRealAdapterTest 2>&1 |
        Out-String
    $code = $LASTEXITCODE
    $caught = $code -ne 0
    if (-not $caught) { $escaped++ }
    $firstFail = ($runLog -split "`n" | Where-Object { $_ -match '^\[FAIL\]' } |
        Select-Object -First 1)
    if (-not $firstFail) { $firstFail = '' }

    Write-Both ("[{0}] {1} -> caught={2} exit={3}" -f $mutation.id, $mutation.defect, $caught, $code)
    if ($firstFail) { Write-Both ("        首个失败断言: " + $firstFail.Trim()) }

    $results.Add([ordered]@{
        id = $mutation.id
        file = $mutation.file
        defect = $mutation.defect
        caught = $caught
        exitCode = $code
        firstFailingCheck = $firstFail.Trim()
    })

    # 还原临时副本（下一轮 mutation 从干净副本开始）
    [System.IO.File]::WriteAllText($target, $original)
}

$summary = [ordered]@{
    generatedAt = [DateTimeOffset]::UtcNow.ToString('o')
    test = 'tests/java/com/sakura/remote/BlankOverlayRealAdapterTest.java'
    productionFiles = $prodFiles
    mutationsTotal = $results.Count
    mutatedCaught = @($results | Where-Object { $_.caught }).Count
    escaped = $escaped
    productionSourcesUnmodified = $true
    results = $results
}
$json = $summary | ConvertTo-Json -Depth 5
Write-Both $json

if ($Log) {
    $dir = Split-Path -Parent $Log
    if ($dir) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    [System.IO.File]::WriteAllLines($Log, $lines, (New-Object System.Text.UTF8Encoding($false)))
}
if ($Report) {
    $dir = Split-Path -Parent $Report
    if ($dir) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    [System.IO.File]::WriteAllText($Report, $json, (New-Object System.Text.UTF8Encoding($false)))
}

if ($escaped -gt 0) {
    Write-Output ("MUTATION ESCAPED: {0}/{1}" -f $escaped, $results.Count)
    exit 1
}
Write-Output ("MUTATION OK: {0}/{1} caught" -f $results.Count, $results.Count)
exit 0
