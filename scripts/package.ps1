# =====================================================================
# LifeDesk - build & package for Windows via jpackage.
#
# Quick use (from the project root folder "lifedesk"):
#   powershell -ExecutionPolicy Bypass -File scripts\package.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\package.ps1 -RunTests
#   powershell -ExecutionPolicy Bypass -File scripts\package.ps1 -Type msi
#
# If JAVA_HOME / mvn are not on PATH, pass explicit paths:
#   powershell -ExecutionPolicy Bypass -File scripts\package.ps1 `
#       -Jdk "C:\Program Files\Java\jdk-25.0.4.1" `
#       -Mvn  "C:\tools\apache-maven-3.9.16\bin\mvn.cmd"
#
# Output:
#   -Type app-image -> target\jpackage\LifeDesk\LifeDesk.exe  (bundled runtime, no install)
#   -Type msi       -> target\jpackage\LifeDesk-1.0.0.msi     (requires WiX Toolset 3.x)
#
# User data lives in %APPDATA%\LifeDesk\data (never inside the install folder).
# =====================================================================
param(
    [ValidateSet("app-image", "msi")]
    [string]$Type = "app-image",
    [string]$Jdk = $env:JAVA_HOME,
    [string]$Mvn = "mvn",
    [switch]$RunTests
)

$ErrorActionPreference = "Stop"

# --- locate project root (folder that contains scripts\package.ps1) ------
# Lets you run this script from ANY working directory.
$ProjectRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $ProjectRoot
Write-Host "== Project: $ProjectRoot"

# --- locate JDK ----------------------------------------------------------
if (-not $Jdk) {
    $javaExe = Get-Command java -ErrorAction SilentlyContinue
    if (-not $javaExe) { throw "JAVA_HOME is not set and java.exe is not on PATH. Pass -Jdk <jdk path>." }
    $Jdk = Split-Path (Split-Path $javaExe.Source)
}
$env:JAVA_HOME = $Jdk
Write-Host "== JDK : $Jdk"

# --- optional: run the full test suite ----------------------------------
if ($RunTests) {
    Write-Host "== Running tests (mvn test)..."
    & $Mvn test
    if ($LASTEXITCODE -ne 0) { throw "Tests failed - packaging aborted." }
}

# --- build jar -----------------------------------------------------------
Write-Host "== Building jar (mvn package)..."
& $Mvn -q package -DskipTests
if ($LASTEXITCODE -ne 0) { throw "Maven build failed." }

# --- assemble jar + runtime libraries -----------------------------------
Write-Host "== Assembling target\dist (lifedesk.jar + lib)..."
New-Item -ItemType Directory -Force -Path target\dist | Out-Null
Copy-Item -Force target\lifedesk.jar target\dist\lifedesk.jar
Remove-Item -Recurse -Force target\dist\lib -ErrorAction SilentlyContinue
Copy-Item -Recurse -Force target\lib target\dist\lib

# --- jpackage ------------------------------------------------------------
# jpackage refuses to write into an existing app dir - clean it first.
$dest = "target\jpackage\LifeDesk"
if (Test-Path $dest) {
    Write-Host "== Removing previous app image..."
    Remove-Item -Recurse -Force $dest
    if (Test-Path $dest) { throw "Could not delete $dest - close any running LifeDesk.exe and retry." }
}
Write-Host "== jpackage ($Type)..."
& "$Jdk\bin\jpackage.exe" `
    --type $Type `
    --name LifeDesk `
    --app-version 1.0.0 `
    --vendor "LifeDesk" `
    --description "Offline-first personal productivity suite" `
    --input target\dist `
    --main-jar lifedesk.jar `
    --main-class com.lifedesk.app.Launcher `
    --dest target\jpackage `
    --java-options "-Dstdout.encoding=UTF-8" `
    --java-options "-Dstderr.encoding=UTF-8" `
    --java-options "--enable-native-access=ALL-UNNAMED"
if ($LASTEXITCODE -ne 0) { throw "jpackage failed." }

Write-Host ""
Write-Host "== DONE. Output in target\jpackage\"
if ($Type -eq "app-image") {
    Write-Host "Run app : target\jpackage\LifeDesk\LifeDesk.exe"
} else {
    Write-Host "Installer: target\jpackage\LifeDesk-1.0.0.msi"
}
Write-Host "User data: %APPDATA%\LifeDesk\data (never inside the install folder)."
