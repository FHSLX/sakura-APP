param(
    [string]$Out = "",
    [switch]$SkipNode
)

# NOTE: this file is intentionally ASCII-only.
#
# Every other *.ps1 in this repository is stored in the local ANSI codepage (GBK),
# so Windows PowerShell 5.1 parses them fine. This file was rewritten by an editor
# as UTF-8 without BOM; PowerShell 5.1 then reads it as ANSI, mangles non-ASCII
# characters into bytes that break the parser, and the whole script fails to load.
# The tests/ directory is write-restricted for the sandboxed shell (an expected
# deny entry), so the encoding cannot be repaired from a command line here.
# Keeping this script ASCII-only makes it parse under any codepage.
# Chinese wording of the UI copy is asserted by tests/app_settings_check.js, which
# reads app.js as UTF-8.

$ErrorActionPreference = 'Stop'
try {
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    $OutputEncoding = [System.Text.Encoding]::UTF8
} catch { }

$root = Split-Path -Parent $PSScriptRoot
$srcDir = Join-Path $root 'phone_app\android\app\src\main\java\com\sakura\remote'
$javaSrc = @(
    'CaptureRequestRegistry.java',
    'PageSessionRegistry.java',
    'RegionSessionGate.java',
    'RegionSnapshotStore.java',
    'RegionSessionState.java',
    'CaptureConsentPolicy.java',
    'CaptureStartPolicy.java'
) | ForEach-Object { Join-Path $srcDir $_ }
$javaTest = Join-Path $root 'tests\java\com\sakura\remote\IntegrationBehaviorTest.java'
$regressionTests = @(
    (Join-Path $root 'tests\java\com\sakura\remote\ReviewFixBehaviorTest.java'),
    (Join-Path $root 'tests\java\com\sakura\remote\CaptureRequestRegistryTest.java')
)
$nodeTests = @(
    'page_session_check.js',
    'web_session_wiring_check.js',
    'touch_region_reviewfix_check.js',
    'touch_region_geometry_check.js',
    'touch_region_limit_check.js',
    'touch_region_real_png_check.js',
    'touch_region_dirty_check.js',
    'app_settings_check.js'
)

if (-not $Out) { $Out = Join-Path $env:TEMP 'sakura-integration-classes' }
$out = $Out

foreach ($file in $javaSrc + @($javaTest) + $regressionTests) {
    if (-not (Test-Path -LiteralPath $file)) { throw "missing $file" }
}

$failed = 0

# ---- 1) Node front-end wiring tests --------------------------------------
if (-not $SkipNode) {
    $node = Get-Command node -ErrorAction SilentlyContinue
    if (-not $node) {
        Write-Output '[SKIP] node not found on PATH'
    } else {
        foreach ($test in $nodeTests) {
            $file = Join-Path $root ('tests\' + $test)
            if (-not (Test-Path -LiteralPath $file)) { throw "missing $file" }
            Write-Output "--- node $test ---"
            & node $file
            if ($LASTEXITCODE -ne 0) { $failed = 1 }
        }
    }
}

# ---- 2) Pure-Java lifecycle driver tests ---------------------------------
Remove-Item -LiteralPath $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $out -Force | Out-Null

$javac = Get-Command javac -ErrorAction SilentlyContinue
if (-not $javac) { throw 'javac not found on PATH' }

& javac -d $out @javaSrc @($javaTest) @regressionTests
if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }

Write-Output '--- IntegrationBehaviorTest ---'
& java '-Dfile.encoding=UTF-8' -cp $out com.sakura.remote.IntegrationBehaviorTest
if ($LASTEXITCODE -ne 0) { $failed = 1 }

Write-Output '--- ReviewFixBehaviorTest (regression) ---'
& java '-Dfile.encoding=UTF-8' -cp $out com.sakura.remote.ReviewFixBehaviorTest
if ($LASTEXITCODE -ne 0) { $failed = 1 }

Write-Output '--- CaptureRequestRegistryTest (regression) ---'
& java '-Dfile.encoding=UTF-8' -cp $out com.sakura.remote.CaptureRequestRegistryTest
if ($LASTEXITCODE -ne 0) { $failed = 1 }

# ---- 3) Wiring guards ----------------------------------------------------
# These assert that key call sites still exist. They are not behavior evidence.
Write-Output '--- wiring guards ---'
$guardFailed = $false
$guardPassed = 0
function Assert-Wiring([string]$name, [bool]$condition) {
    Write-Output ("[{0}] (wiring) {1}" -f ($(if ($condition) { 'PASS' } else { 'FAIL' })), $name)
    if ($condition) { $script:guardPassed++ } else { $script:guardFailed = $true }
}

$service = Get-Content -LiteralPath (Join-Path $srcDir 'CaptureService.java') -Raw
Assert-Wiring 'CaptureService rejects a refused start via rejectStart' `
    (($service -split 'rejectStart\(').Length -ge 3)
Assert-Wiring 'CaptureService no longer stops unconditionally (legacy call gone)' `
    (-not $service.Contains('startForegroundBestEffortAndStop();'))
Assert-Wiring 'CaptureService stops an empty start with stopSelf(startId)' `
    ($service.Contains('stopSelf(startId)'))

$activity = Get-Content -LiteralPath (Join-Path $srcDir 'CapturePermissionActivity.java') -Raw
Assert-Wiring 'consent page uses real wait decision CaptureConsentPolicy.nextWaitAction' `
    ($activity.Contains('CaptureConsentPolicy.nextWaitAction'))
Assert-Wiring 'consent page no longer has the focus-grace attempt constant' `
    (-not $activity.Contains('FOCUS_GRACE_ATTEMPTS'))
Assert-Wiring 'consent page trusts only real hasWindowFocus' `
    ($activity.Contains('hasWindowFocus()'))
Assert-Wiring 'consent page onPause cancels the wait' `
    ($activity.Contains('public void onPause()') -and `
     $activity.Contains('removeCallbacks(waitRunnable)'))
Assert-Wiring 'consent page does not treat !isFinishing as resumed' `
    (-not ($activity.Contains('!isFinishing() && !isDestroyed() &&')))

$controller = Get-Content -LiteralPath (Join-Path $srcDir 'OverlayTouchRegionController.java') -Raw
Assert-Wiring 'controller delegates session/snapshot/generation to RegionSessionState' `
    ($controller.Contains('RegionSessionState sessions'))
Assert-Wiring 'controller no longer owns gate/snapshots itself' `
    ((-not $controller.Contains('private final RegionSessionGate gate')) -and `
     (-not $controller.Contains('private final RegionSnapshotStore snapshots')))

$webUi = Get-Content -LiteralPath (Join-Path $root 'sakura_remote\web_ui.py') -Raw
Assert-Wiring 'web_ui loads page_session.js before app.js' `
    ($webUi.IndexOf('page_session.js') -gt 0 -and `
     $webUi.IndexOf('/page_session.js') -lt $webUi.IndexOf('/app.js'))
Assert-Wiring 'build number covers page_session.js' `
    ($webUi.Contains('"overlay_touch_region.js", "page_session.js"'))

$appJs = Get-Content -LiteralPath (Join-Path $root 'sakura_remote\static\app.js') -Raw
Assert-Wiring 'app.js no longer invents its own random PAGE_SESSION_ID' `
    (-not $appJs.Contains("const PAGE_SESSION_ID = 'ps-'"))
Assert-Wiring 'app.js uses the shared generation SakuraPageSession' `
    ($appJs.Contains('window.SakuraPageSession'))
Assert-Wiring 'app.js exposes SakuraDisplayPreferences' `
    ($appJs.Contains('window.SakuraDisplayPreferences'))
Assert-Wiring 'save button reads the visible number inputs current values' `
    ($appJs.Contains('function commitVisibleDisplayValues') -and `
     $appJs.Contains('el.scaleNumber') -and $appJs.Contains('el.fontNumber'))
Assert-Wiring 'App pass-through toggle is no longer disabled outside the overlay' `
    ($appJs.Contains('el.passThroughToggle.disabled = !supported'))
# TS-03 UI copy: the old "transparent areas pass through" promise must be gone.
# The wording is Chinese, so the exact strings are asserted by
# tests/app_settings_check.js (which reads app.js as UTF-8); this ASCII-only guard
# only proves the message table is still wired and its three main keys exist.
Assert-Wiring 'pass-through message table still wired for applied/unchanged/disabled' `
    ($appJs.Contains('function passThroughMessage') -and `
     $appJs.Contains("'applied':") -and $appJs.Contains("'unchanged':") -and `
     $appJs.Contains("'disabled':"))

$mainActivity = Get-Content -LiteralPath (Join-Path $srcDir 'MainActivity.java') -Raw
Assert-Wiring 'MainActivity reports real pass-through capability (API33+)' `
    ($mainActivity.Contains('Build.VERSION_CODES.TIRAMISU'))
Assert-Wiring 'MainActivity never submits a region itself' `
    ($mainActivity.Contains('return "unsupported";'))

$overlayService = Get-Content -LiteralPath (Join-Path $srcDir 'OverlayService.java') -Raw
Assert-Wiring 'OverlayService uses testable PassThroughAlpha for idempotent alpha' `
    ($overlayService.Contains('PassThroughAlpha passThroughAlpha'))
Assert-Wiring 'OverlayService syncs preferences over the in-process channel' `
    ($overlayService.Contains('applyDisplayPreferencesFromHost'))

$touchRegion = Get-Content -LiteralPath (Join-Path $root 'sakura_remote\static\overlay_touch_region.js') -Raw
Assert-Wiring 'outline scan reads the source 1:1 in tiles (no nearest-neighbour resize)' `
    ($touchRegion.Contains('drawImage(img, sx0, sy0, tileCols, tileRows, 0, 0, tileCols, tileRows)'))
Assert-Wiring 'source pixel footprint is OR-ed into every intersecting grid cell' `
    ($touchRegion.Contains('Math.ceil((sourceX + 1) * gridW / naturalW)') -and `
     $touchRegion.Contains('Math.ceil((sourceY + 1) * gridH / naturalH)'))
Assert-Wiring 'coordinate mapping keeps original cell width and block end boundary' `
    ($touchRegion.Contains('baseWidth') -and $touchRegion.Contains('endCellX'))
# TS-02 performance stop-loss: dirty-version gate and scan generation must remain.
Assert-Wiring 'geometry dirty-version gate still present (cheap signature first)' `
    ($touchRegion.Contains('computeLayoutSignature') -and `
     $touchRegion.Contains('signatureHits') -and `
     $touchRegion.Contains("preSignature === layoutVersion"))
Assert-Wiring 'scan cache is bound to the image generation (rescan only on change)' `
    ($touchRegion.Contains('scanFailureKey') -and `
     $touchRegion.Contains('scanCache.key === key'))
Assert-Wiring 'native retry path no longer clears the scan cache' `
    (-not ($touchRegion -match "(?s)retryBudget--;.{0,400}?scanCache = null;"))
Assert-Wiring 'transient native rejection is not treated as permanent failure' `
    ($touchRegion.Contains("failSafe('native-error')") -and `
     -not ($touchRegion -match "(?s)isPermanent\(reasonCode\)\s*=\s*\{?[\s\S]{0,300}payload-rejected"))
Assert-Wiring 'polling stops while the page is hidden' `
    ($touchRegion.Contains('visibilityStops') -and $touchRegion.Contains('isPageHidden'))
# TS-03 outer-contour semantics: in-row gaps must be filled (user-authorized),
# and the fill counter must reach payload/status so an accidental bounding-box
# regression or a silent removal of the fill cannot pass unnoticed.
Assert-Wiring 'rows merge to the outer contour (leftmost to rightmost, gaps filled)' `
    ($touchRegion.Contains('runs = [[runs[0][0], runs[runs.length - 1][1]]]'))
Assert-Wiring 'outer-contour fill counter reaches payload and status' `
    ($touchRegion.Contains('filledGaps') -and $touchRegion.Contains('interiorFill'))
Assert-Wiring 'level choice returns at the first in-budget level' `
    ($touchRegion -match '(?s)function chooseLevel[\s\S]{0,900}?return \{ level: level, rows: rows \};')

$controllerSource = Get-Content -LiteralPath (Join-Path $srcDir 'OverlayTouchRegionController.java') -Raw
Assert-Wiring 'native side skips rewriting an identical region (still ACKs)' `
    ($controllerSource.Contains('lastAppliedSignature') -and `
     $controllerSource.Contains('regionSignature(region)') -and `
     $controllerSource.Contains('host.onTouchRegionApplied(revision, rectCount, fallback)'))
Assert-Wiring 'native side invalidates the region signature on page switch / re-attach' `
    (($controllerSource -split 'lastAppliedSignature = "";').Length -ge 4)

$petConfig = Get-Content -LiteralPath (Join-Path $srcDir 'PetConfig.java') -Raw
Assert-Wiring 'preferences are written with commit confirmation, not apply+readback' `
    ($petConfig.Contains('editor.commit()') -and $petConfig.Contains('commit-failed'))

if ($guardFailed) { $failed = 1 }
Write-Output ("wiring guards: pass={0} fail={1}" -f $guardPassed, $(if ($guardFailed) { 1 } else { 0 }))

exit $failed
