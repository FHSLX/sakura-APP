# INT-07 mutation 证据：临时恢复真实缺陷，真实适配层测试必须失败。
#
# 做法：
#   1) 把 12 个真实生产源码复制到临时目录；
#   2) 按 tests/mutations/lifecycle-mutations.json 在**副本**上恢复旧缺陷；
#   3) 用同一份 RealAdapterBehaviorTest + 同一份 androidstub 重新编译、运行；
#   4) 断言测试**失败**（退出码非 0 且出现 [FAIL]）。若某个 mutation 逃逸（测试仍绿），
#      脚本以非 0 退出。
#
# 正式源码全程只读，绝不被修改。
#
# Usage: pwsh -NoProfile -File tests\real_adapter_mutation_check.ps1 [-Report <json>]

param(
    [string]$Report = ""
)

$ErrorActionPreference = 'Stop'
try {
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
} catch { }

$root = Split-Path -Parent $PSScriptRoot
$srcDir = Join-Path $root 'phone_app\android\app\src\main\java\com\sakura\remote'
$stubDir = Join-Path $root 'tests\androidstub'
$testFile = Join-Path $root 'tests\java\com\sakura\remote\RealAdapterBehaviorTest.java'
$specPath = Join-Path $root 'tests\mutations\lifecycle-mutations.json'

$prodNames = @(
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
)

if (-not (Test-Path -LiteralPath $specPath)) { throw "missing $specPath" }
$stubFiles = @(Get-ChildItem -LiteralPath $stubDir -Recurse -File -Filter *.java |
    Select-Object -ExpandProperty FullName)

$utf8 = New-Object System.Text.UTF8Encoding($false)
$spec = [System.IO.File]::ReadAllText($specPath, [System.Text.Encoding]::UTF8) | ConvertFrom-Json

$work = Join-Path $env:TEMP ('sakura-mutation-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $work -Force | Out-Null

$results = New-Object System.Collections.Generic.List[object]
$escaped = 0

foreach ($mutation in $spec.mutations) {
    Write-Output ("=== mutation {0} ({1}) ===" -f $mutation.id, $mutation.file)
    $mutDir = Join-Path $work $mutation.id
    New-Item -ItemType Directory -Path $mutDir -Force | Out-Null
    foreach ($name in $prodNames) {
        Copy-Item -LiteralPath (Join-Path $srcDir $name) -Destination (Join-Path $mutDir $name)
    }
    $target = Join-Path $mutDir $mutation.file
    $text = [System.IO.File]::ReadAllText($target, [System.Text.Encoding]::UTF8)
    foreach ($edit in $mutation.edits) {
        if (-not $text.Contains($edit.old)) {
            throw ("mutation {0}: anchor not found in {1}" -f $mutation.id, $mutation.file)
        }
        $text = $text.Replace($edit.old, $edit.new)
    }
    [System.IO.File]::WriteAllText($target, $text, $utf8)

    $classes = Join-Path $mutDir 'classes'
    New-Item -ItemType Directory -Path $classes -Force | Out-Null
    $prodFiles = @(Get-ChildItem -LiteralPath $mutDir -File -Filter *.java |
        Select-Object -ExpandProperty FullName)

    $compileLog = & javac -encoding UTF-8 -d $classes @prodFiles @stubFiles @($testFile) 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw ("mutation {0} failed to compile: {1}" -f $mutation.id, ($compileLog -join "`n"))
    }

    $runLog = & java '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' `
        -cp $classes com.sakura.remote.RealAdapterBehaviorTest 2>&1
    $exitCode = $LASTEXITCODE
    $joined = ($runLog | Out-String)
    $hasFail = $joined.Contains('[FAIL]')
    $isCaught = ($exitCode -ne 0) -and $hasFail
    if (-not $isCaught) { $escaped++ }

    $firstFail = ($runLog | Where-Object { $_.Contains('[FAIL]') } | Select-Object -First 1)
    $firstFailText = if ($firstFail) { ([string]$firstFail).Trim() } else { '' }
    Write-Output ("  exit={0} hasFail={1} caught={2}" -f $exitCode, $hasFail, $isCaught)
    if ($firstFailText) { Write-Output ("  first: {0}" -f $firstFailText) }

    $results.Add([ordered]@{
        id = $mutation.id
        file = $mutation.file
        expectedCoverage = $mutation.expect
        mutantExitCode = $exitCode
        hasFailLine = $hasFail
        caught = $isCaught
        firstFailLine = $firstFailText
    })
}

$summary = [ordered]@{
    generatedAt = [DateTimeOffset]::UtcNow.ToString('o')
    testFile = 'tests/java/com/sakura/remote/RealAdapterBehaviorTest.java'
    stubs = 'tests/androidstub (Android API doubles + OverlayService collaborator double)'
    mutations = $results
    escaped = $escaped
    allCaught = ($escaped -eq 0)
    note = 'Only temporary copies were mutated; official product sources untouched.'
}
$json = $summary | ConvertTo-Json -Depth 5
if ($Report) {
    $reportDir = Split-Path -Parent $Report
    if ($reportDir) { New-Item -ItemType Directory -Path $reportDir -Force | Out-Null }
    [System.IO.File]::WriteAllText($Report, $json, $utf8)
}
Write-Output $json

Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue

if ($escaped -gt 0) {
    Write-Output ("MUTATION EVIDENCE FAILED: {0} mutation(s) escaped" -f $escaped)
    exit 1
}
Write-Output ("MUTATION EVIDENCE OK: {0}/{1} caught" -f $results.Count, $results.Count)
exit 0
