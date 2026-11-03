# Preview the embedded web frontend only.
# This is NOT the main LifeDesk app: the real app is a JavaFX desktop app.
# The Vite build is bundled into src/main/resources/web for JavaFX WebView.
# Usage:
#   powershell -ExecutionPolicy Bypass -File .\scripts\run-web.ps1
#   powershell -ExecutionPolicy Bypass -File .\scripts\run-web.ps1 -Port 4173

param(
    [string]$Port = '5173'
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$webDir = Join-Path $root 'web'

if (-not (Test-Path $webDir)) {
    throw "Web folder not found: $webDir"
}

Set-Location $webDir

if (-not (Test-Path 'node_modules')) {
    Write-Host 'Installing web dependencies...'
    npm install
    if ($LASTEXITCODE -ne 0) {
        throw 'npm install failed.'
    }
}

Write-Host "Starting LifeDesk web app on http://localhost:$Port"
npm start -- --host 0.0.0.0 --port $Port
