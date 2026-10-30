# Launch LifeDesk desktop app (PowerShell)
#
# The script now does the full loop so the window you see is ALWAYS the
# current code:
#   1. Closes any running LifeDesk instance (it holds the single-instance
#      lock AND locks target\lib jars, which would block the rebuild).
#   2. Rebuilds target\classes + target\lib with a clean Maven build (an IDE
#      can leave stale/invalid classes there; -NoBuild skips this).
#   3. Launches the app.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File .\scripts\run-app.ps1
#   powershell -ExecutionPolicy Bypass -File .\scripts\run-app.ps1 -NoBuild     # launch only
#   powershell -ExecutionPolicy Bypass -File .\scripts\run-app.ps1 -DataDir "D:\LifeDeskData"

param(
    [string]$DataDir = "",
    [switch]$NoBuild
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $root

# ---- 1. close any running LifeDesk (single-instance lock + jar locks) ----
$old = Get-CimInstance Win32_Process |
    Where-Object { $_.CommandLine -match 'com\.lifedesk\.app\.Launcher' }
foreach ($proc in $old) {
    Write-Host "Closing running LifeDesk (PID $($proc.ProcessId))..."
    try {
        Stop-Process -Id $proc.ProcessId -Force -ErrorAction Stop
    } catch {
        Write-Host "  (already exited)"
    }
}
if ($old) {
    Start-Sleep -Milliseconds 800
}

# ---- 2. rebuild so the launched classes are the current code ----
$mvnCandidates = @(
    'C:\Users\Nexus\.lifedesk-tools\apache-maven-3.9.16\bin\mvn.cmd',
    (Get-Command mvn -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source -ErrorAction SilentlyContinue)
)
$mvn = $mvnCandidates | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1

if (-not $NoBuild) {
    if (-not $mvn) {
        throw 'mvn not found. Install Maven or pass -NoBuild to launch without rebuilding.'
    }
    Write-Host "Building (mvn clean package -DskipTests)..."
    & $mvn clean package -DskipTests -q
    if ($LASTEXITCODE -ne 0) {
        throw "Build failed (exit $LASTEXITCODE). Fix compile errors and retry."
    }
    Write-Host 'Build OK.'
}

# ---- 3. locate java ----
$javaHomeCandidates = @(
    $env:JAVA_HOME,
    'C:\Program Files\Java\jdk-25.0.4.1',
    'C:\Program Files\Java\jdk-25'
)

$javaExe = $null
foreach ($candidate in $javaHomeCandidates) {
    if (-not [string]::IsNullOrWhiteSpace($candidate)) {
        $p = Join-Path $candidate 'bin\javaw.exe'
        if (Test-Path $p) {
            $javaExe = $p
            break
        }
    }
}

if (-not $javaExe) {
    $cmd = Get-Command javaw -ErrorAction SilentlyContinue
    if ($cmd) {
        $javaExe = $cmd.Source
    }
}

if (-not $javaExe) {
    throw 'Could not find javaw.exe. Install JDK 25 or set JAVA_HOME correctly.'
}

$classDir = Join-Path $root 'target\classes'
$libDir = Join-Path $root 'target\lib'
if (-not (Test-Path $classDir) -or -not (Test-Path $libDir)) {
    throw "Project is not built yet. Run the script without -NoBuild."
}

# ---- 4. launch ----
# Bounded heap: the default is 1/4 of RAM (~8 GB reserved on a 32 GB machine),
# which lets a session grow until Windows' commit limit is gone — the JVM then
# dies with a native malloc failure ("insufficient memory ... Chunk::new") and
# the window simply disappears. 1 GB is ample for this app and keeps the
# process well inside the system's commit budget.
$argsList = @(
    '-Dstdout.encoding=UTF-8',
    '-Xms64m',
    '-Xmx1024m',
    '-XX:MaxMetaspaceSize=256m',
    '-XX:ReservedCodeCacheSize=128m',
    '-XX:MaxDirectMemorySize=256m'
)

if (-not [string]::IsNullOrWhiteSpace($DataDir)) {
    $argsList += "-Dlifedesk.data=$DataDir"
}

$argsList += '-cp'
$argsList += "target\classes;target\lib\*"
$argsList += 'com.lifedesk.app.Launcher'

Start-Process -FilePath $javaExe -ArgumentList $argsList -WorkingDirectory $root
Write-Host 'LifeDesk is starting...'
Write-Host "Data dir: $([System.Environment]::GetFolderPath('ApplicationData'))\LifeDesk\data"
