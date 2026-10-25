# LifeDesk Windows packaging via jpackage.
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts\package.ps1              # app-image (no WiX needed)
#   powershell -ExecutionPolicy Bypass -File scripts\package.ps1 -Type msi    # installer (requires WiX Toolset 3.x)
param(
    [string]$Type = "app-image",
    [string]$Jdk = $env:JAVA_HOME,
    [string]$Mvn = "mvn"
)

$ErrorActionPreference = "Stop"
if (-not $Jdk) {
    # Fall back to the JDK found on PATH
    $javaExe = (Get-Command java -ErrorAction SilentlyContinue)
    if (-not $javaExe) { throw "JAVA_HOME is not set and java.exe is not on PATH." }
    $Jdk = Split-Path (Split-Path $javaExe.Source)
}
$env:JAVA_HOME = $Jdk

Write-Host "== Building LifeDesk jar (mvn package)..."
& $Mvn -q package -DskipTests
if ($LASTEXITCODE -ne 0) { throw "Maven build failed" }

Write-Host "== Assembling target/dist (jar + runtime libs)..."
New-Item -ItemType Directory -Force -Path target\dist | Out-Null
Copy-Item -Force target\lifedesk.jar target\dist\lifedesk.jar
Remove-Item -Recurse -Force target\dist\lib -ErrorAction SilentlyContinue
Copy-Item -Recurse -Force target\lib target\dist\lib

Write-Host "== Running jpackage ($Type)..."
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
if ($LASTEXITCODE -ne 0) { throw "jpackage failed" }

Write-Host "== Done. Output in target\jpackage\"
if ($Type -eq "app-image") {
    Write-Host "Run: target\jpackage\LifeDesk\LifeDesk.exe"
} else {
    Write-Host "Installer: target\jpackage\LifeDesk-1.0.0.exe"
}
