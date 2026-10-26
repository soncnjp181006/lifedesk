# Launch LifeDesk UI from the built classes (dev run).
# Usage: powershell -ExecutionPolicy Bypass -File scripts\run.ps1
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $root

$BS = [char]92
$java = 'C:' + $BS + 'Program Files' + $BS + 'Java' + $BS + 'jdk-25.0.4.1' + $BS + 'bin' + $BS + 'javaw.exe'
if (-not (Test-Path $java)) {
    $cmd = Get-Command javaw -ErrorAction SilentlyContinue
    if (-not $cmd) { throw "javaw.exe not found - set JAVA_HOME or install a JDK." }
    $java = $cmd.Source
}

Start-Process -FilePath $java -ArgumentList @(
    '-Dstdout.encoding=UTF-8',
    '-cp', "target\classes;target\lib\*",
    'com.lifedesk.app.Launcher'
) -WorkingDirectory $root

Write-Host "LifeDesk launching (data: %APPDATA%\LifeDesk\data)"
