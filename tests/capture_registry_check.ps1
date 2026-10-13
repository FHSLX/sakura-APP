# Compile and run the pure-JVM behaviour test for the capture request state machine.
#
# Why a script: CaptureRequestRegistry has no Android imports, so it can be tested
# with plain javac/java -- no device, no Emulator, no JUnit download. This is a real
# behavioural test (state transitions, single-active-request, exactly-once finish),
# not a source-text search.
#
# Usage: powershell -NoProfile -File tests\capture_registry_check.ps1
#
# -Out defaults to a temp directory: on some hosts the file sandbox allows the
# confined shell to write only under the workspace root or a temp area, so the
# compile output must not be forced into an existing source subdirectory.

param(
    [string]$Out = ""
)

$ErrorActionPreference = 'Stop'
try {
    # 让中文断言名在控制台/日志里可读
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    $OutputEncoding = [System.Text.Encoding]::UTF8
} catch { }

$root = Split-Path -Parent $PSScriptRoot
$registry = Join-Path $root 'phone_app\android\app\src\main\java\com\sakura\remote\CaptureRequestRegistry.java'
$test = Join-Path $root 'tests\java\com\sakura\remote\CaptureRequestRegistryTest.java'
if (-not $Out) { $Out = Join-Path $env:TEMP 'sakura-capture-registry-classes' }
$out = $Out

if (-not (Test-Path -LiteralPath $registry)) { throw "missing $registry" }
if (-not (Test-Path -LiteralPath $test)) { throw "missing $test" }

Remove-Item -LiteralPath $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $out -Force | Out-Null

$javac = (Get-Command javac -ErrorAction SilentlyContinue)
if (-not $javac) { throw 'javac not found on PATH' }

& javac -d $out $registry $test
if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }

& java '-Dfile.encoding=UTF-8' -cp $out com.sakura.remote.CaptureRequestRegistryTest
exit $LASTEXITCODE
