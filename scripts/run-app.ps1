# Launch LifeDesk desktop app (PowerShell)
# Usage:
#   powershell -ExecutionPolicy Bypass -File .\scripts\run-app.ps1
# Optional custom data folder:
#   powershell -ExecutionPolicy Bypass -File .\scripts\run-app.ps1 -DataDir "D:\LifeDeskData"

param(
    [string]$DataDir = ""
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $root

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
    throw "Project is not built yet. Run: mvn package -DskipTests"
}

$argsList = @(
    '-Dstdout.encoding=UTF-8'
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
