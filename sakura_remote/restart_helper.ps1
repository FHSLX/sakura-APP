# Restart Sakura on this PC. Launched by the phone side's "remote restart".
#
# Must run as a separate process: Sakura kills its own children when it exits,
# so the restart work has to live outside Sakura.
#
# Order of operations:
#   1. Ask the old process to quit (WM_CLOSE to its main window;
#      force-stop only as a fallback after a timeout)
#   2. Wait until it is really gone (otherwise the new instance fails to bind)
#   3. Start the new process and poll until the port is listening again
#   4. If it does not come back, start it once more and log the failure
#
# KEEP THIS FILE PURE ASCII. Windows PowerShell 5.1 reads BOM-less UTF-8 as
# ANSI, and non-ASCII characters here break parsing.

param(
    [string]$ConfigPath = "",
    [string]$Stage = "stop"
)

$ErrorActionPreference = "Continue"

# Arguments come from a small JSON file rather than the command line.
# Why: this script is launched through `schtasks /TR`, and that argument has a
# hard 261-character limit. Passing four paths inline blew past it
# (measured 269). A config file keeps the task command short and fixed.
if (-not $ConfigPath -or -not (Test-Path $ConfigPath)) {
    exit 1
}
try {
    $cfg = Get-Content -Path $ConfigPath -Raw | ConvertFrom-Json
} catch {
    exit 1
}
$OldPid = [int]$cfg.oldPid
$Port = [int]$cfg.port
$ExePath = [string]$cfg.exePath
$WorkDir = [string]$cfg.workDir
$HelperPath = [string]$cfg.helperPath

$logDir = Join-Path $WorkDir "data\plugins\sakura.remote\logs"
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Force -Path $logDir | Out-Null }
$log = Join-Path $logDir ("restart-{0}.log" -f (Get-Date -Format "yyyyMMdd-HHmmss"))

function Write-Log([string]$Message) {
    Add-Content -Path $log -Value ("{0}  {1}" -f (Get-Date -Format "HH:mm:ss"), $Message) -Encoding UTF8
}

function Test-Port([int]$TargetPort) {
    try {
        $client = New-Object Net.Sockets.TcpClient
        $client.Connect("127.0.0.1", $TargetPort)
        $client.Close()
        return $true
    } catch {
        return $false
    }
}

function Start-Sakura {
    # Launch through WMI instead of Start-Process.
    #
    # Win32_Process.Create is served by the WMI service, so the new Sakura is not
    # a child of this script and does not die when this task ends.
    #
    # Note on quoting: keep the CommandLine simple. Nesting PowerShell quotes
    # inside a -Command string is error-prone; revisit if the install path ever
    # contains a space.
    $script = "Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = '" + $ExePath + "' } | Out-Null"
    $out = & powershell.exe -NoProfile -ExecutionPolicy Bypass -Command $script 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Log ("WMI start returned " + $LASTEXITCODE + ": " + ($out -join " "))
        Start-Process -FilePath $ExePath -WorkingDirectory $WorkDir | Out-Null
    }
}

# Two stages, because of how Task Scheduler treats a finished action:
# it tears down the entire process tree it created. A Sakura started in the
# same stage as the shutdown dies with that teardown (measured: the helper
# closed the old process and then nothing came back).
#
# So stage "stop" only stops things and then queues stage "start" as a SECOND
# task. That second task gets its own process tree, so the Sakura it spawns
# survives.
function Start-SecondStage {
    # Queue the start stage as its OWN task, so it gets a fresh process tree.
    # Task Scheduler kills this task's tree when the action ends, so the Sakura
    # we want must be started by a different task.
    #
    # Uses the ScheduledTasks module, not schtasks.exe: schtasks takes the whole
    # command as one /TR string and mangles nested quotes (measured: it treated
    # -NoProfile as a separate argument and refused to create the task).
    $argument = "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden " +
                "-File `"$HelperPath`" -ConfigPath `"$ConfigPath`" -Stage start"
    $script = "`$a=New-ScheduledTaskAction -Execute 'powershell.exe' -Argument '" +
              $argument.Replace("'", "''") + "';" +
              "`$t=New-ScheduledTaskTrigger -Once -At (Get-Date);" +
              "Register-ScheduledTask -TaskName 'SakuraRemoteRestartStart' " +
              "-Action `$a -Trigger `$t -Force | Out-Null;" +
              "Start-ScheduledTask -TaskName 'SakuraRemoteRestartStart';" +
              "Start-Sleep -Seconds 2;" +
              "Unregister-ScheduledTask -TaskName 'SakuraRemoteRestartStart' -Confirm:`$false"
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -Command $script 2>&1 | Out-Null
    Write-Log "queued stage start (exit $LASTEXITCODE)"
}

if ($Stage -eq "start") {
    # --- start stage: bring Sakura back and report ---
    Write-Log "stage start: launching $ExePath"
    for ($attempt = 0; $attempt -lt 2; $attempt++) {
        try {
            Start-Sakura
        } catch {
            Write-Log ("start attempt " + $attempt + " failed: " + $_.Exception.Message)
        }
        for ($i = 0; $i -lt 90; $i++) {
            Start-Sleep -Seconds 1
            if (Test-Port $Port) {
                Write-Log "SUCCESS: port $Port is listening again"
                exit 0
            }
        }
        Write-Log ("attempt " + $attempt + " did not bring the port up")
    }
    Write-Log "CRITICAL: Sakura did not come back; needs manual start"
    exit 1
}

Write-Log "restart requested: oldPid=$OldPid port=$Port exe=$ExePath"

# Let the HTTP response reach the phone before we tear anything down.
Start-Sleep -Seconds 3

if (-not (Test-Path $ExePath)) {
    Write-Log "FAILED: exe not found at $ExePath"
    exit 1
}

# --- 1) stop every Sakura process, not just $OldPid ---
# Why not just $OldPid: the packaged sakura.exe is a bootloader that spawns a
# child, and the HTTP server may live in either one. Killing a single stale pid
# leaves the other half running, and the restart silently does nothing.
# Match on the executable path so we never touch an unrelated sakura.exe.
function Get-SakuraProcesses {
    Get-CimInstance Win32_Process -Filter "name='sakura.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.ExecutablePath -eq $ExePath } |
        ForEach-Object { $_.ProcessId }
}

$targets = @(Get-SakuraProcesses)
Write-Log ("sakura processes before stop: " + ($targets -join ","))

# Prefer a graceful close on the process that owns the main window.
foreach ($targetPid in $targets) {
    $proc = Get-Process -Id $targetPid -ErrorAction SilentlyContinue
    if (-not $proc -or $proc.MainWindowHandle -eq 0) { continue }
    try {
        $signature = "[DllImport(`"user32.dll`")] public static extern bool PostMessage(IntPtr hWnd, uint Msg, IntPtr wParam, IntPtr lParam);"
        $api = Add-Type -MemberDefinition $signature -Name Win32Close -Namespace SakuraRestart -PassThru
        $api::PostMessage($proc.MainWindowHandle, 0x0010, [IntPtr]::Zero, [IntPtr]::Zero) | Out-Null
        Write-Log "sent WM_CLOSE to pid $targetPid"
    } catch {
        Write-Log ("WM_CLOSE failed for pid ${targetPid}: " + $_.Exception.Message)
    }
}

# Give it a moment to exit on its own, then force whatever is left.
Start-Sleep -Seconds 5
foreach ($targetPid in $targets) {
    if (Get-Process -Id $targetPid -ErrorAction SilentlyContinue) {
        Write-Log "force stopping pid $targetPid"
        Stop-Process -Id $targetPid -Force -ErrorAction SilentlyContinue
    }
}

# --- 2) wait until the port is free and no process is left ---
$waited = 0
while ($waited -lt 60) {
    Start-Sleep -Seconds 1
    $waited++
    $left = @(Get-SakuraProcesses)
    if ($left.Count -eq 0 -and -not (Test-Port $Port)) { break }
}
$left = @(Get-SakuraProcesses)
if ($left.Count -gt 0) {
    Write-Log ("FAILED: processes still alive: " + ($left -join ",") + "; aborting so we do not double-start")
    exit 1
}
Write-Log "all sakura processes stopped after ${waited}s"

# Hand off to the start stage. This stage must not start Sakura itself:
# Task Scheduler kills this process tree when the action ends.
Start-SecondStage
exit 0

