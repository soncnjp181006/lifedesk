param(
    [ValidateSet('status', 'build', 'smoke', 'tests', 'contrast', 'css', 'wiring', 'icons', 'all')]
    [string]$Mode = 'build',
    [switch]$Kill
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $root
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

# Tool paths come from the environment first; the literals below are only the
# fallbacks for the machine this project was set up on.
$mvn = if (Get-Command mvn -ErrorAction SilentlyContinue) {
    (Get-Command mvn).Source
} else {
    'C:\Users\Nexus\.lifedesk-tools\apache-maven-3.9.16\bin\mvn.cmd'
}
$java = if (Get-Command java -ErrorAction SilentlyContinue) {
    (Get-Command java).Source
} elseif ($env:JAVA_HOME) {
    Join-Path $env:JAVA_HOME 'bin\java.exe'
} else {
    'C:\Program Files\Java\jdk-25.0.4.1\bin\java.exe'
}
$py = if (Get-Command python -ErrorAction SilentlyContinue) { 'python' } else { 'python3' }

function Kill-Instances {
    $old = Get-CimInstance Win32_Process | Where-Object {
        $_.CommandLine -match 'com\.lifedesk\.app\.Launcher|com\.lifedesk\.app\.SmokeMain'
    }
    foreach ($p in $old) {
        Write-Host "Closing PID $($p.ProcessId)"
        Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
    }
    if ($old) { Start-Sleep -Milliseconds 800 }
}

function Invoke-Contrast {
    & $py "$root\scripts\contrast_check.py"
    Write-Host "CONTRAST_EXIT=$LASTEXITCODE"
    # Printing the code and carrying on let a red contrast run end in a green gate.
    if ($LASTEXITCODE -ne 0) { Write-Host 'CONTRAST=RED'; exit 1 }
    Write-Host 'CONTRAST=GREEN'
}

function Invoke-Css {
    & $py "$root\scripts\css_audit.py"
    if ($LASTEXITCODE -ne 0) { Write-Host 'CSS_GATE=RED'; exit 1 }
}

function Invoke-Wiring {
    # "Select the row, then find the button" is two steps for one intention. This
    # gate fails when an edit button is the only way to reach a table's record.
    & $py "$root\scripts\ui_wiring_audit.py"
    if ($LASTEXITCODE -ne 0) { Write-Host 'WIRING_GATE=RED'; exit 1 }
}

function Invoke-Icons {
    # A colour emoji cannot follow the palette and a name without a .svg file paints
    # nothing. Both are silent in the app, so they are measured here.
    & $py "$root\scripts\ui_icon_audit.py"
    if ($LASTEXITCODE -ne 0) { Write-Host 'ICON_GATE=RED'; exit 1 }
}

function Invoke-Smoke {
    # Java writes its start-up notice to stderr; with ErrorActionPreference=Stop a
    # 2>&1 redirect turns that harmless line into a terminating error and kills the
    # gate before it reads the log. Run it under Continue and judge the log.
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & $java '-Dstdout.encoding=UTF-8' '-Dfile.encoding=UTF-8' -Xmx1024m `
        -cp "target\test-classes;target\classes;target\lib\*" `
        com.lifedesk.app.SmokeMain ui 1> target\smoke-ui.log 2> target\smoke-ui.err.log
    $code = $LASTEXITCODE
    $ErrorActionPreference = $prev

    # The log is UTF-16 (PowerShell redirect default), so a plain grep for the check
    # marks finds nothing and a red run looks clean. Read it through .NET, which
    # honours the BOM, and fail the gate on any ✗ — exit 0 alone proves nothing.
    $log = [System.IO.File]::ReadAllLines("$root\target\smoke-ui.log")
    $fail = @($log | Where-Object { $_.Contains([char]0x2717) })
    $pass = @($log | Where-Object { $_.Contains([char]0x2713) })
    $log | Where-Object { $_ -match '^(SWITCH|PERF|SMOKE RESULT)' } | ForEach-Object { $_.Substring(0, [Math]::Min(220, $_.Length)) }
    Write-Host "SMOKE_CHECKS: $($pass.Count) passed, $($fail.Count) failed (java exit $code)"
    $fail | ForEach-Object { Write-Host "  FAILED: $($_.Trim())" }
    if ($fail.Count -gt 0 -or $code -ne 0) {
        Write-Host 'SMOKE=RED'
        exit 1
    }
    Write-Host 'SMOKE=GREEN'
}

if ($Kill) { Kill-Instances }

switch ($Mode) {
    'status' {
        $running = Get-CimInstance Win32_Process | Where-Object {
            $_.CommandLine -match 'com\.lifedesk\.app\.Launcher|com\.lifedesk\.app\.SmokeMain'
        }
        if ($running) { $running | ForEach-Object { Write-Host "RUNNING PID=$($_.ProcessId)" } }
        else { Write-Host 'NO LifeDesk process running' }
    }
    'build' {
        & $mvn -o clean package -DskipTests -q
        Write-Host "BUILD_EXIT=$LASTEXITCODE"
    }
    'tests' {
        & $mvn -o test -q 2>&1 | Select-Object -Last 40
        Write-Host "TEST_EXIT=$LASTEXITCODE"
    }
    'contrast' { Invoke-Contrast }
    'css' { Invoke-Css }
    'wiring' { Invoke-Wiring }
    'icons' { Invoke-Icons }
    'smoke' {
        & $mvn -o test-compile -q
        Write-Host "COMPILE_EXIT=$LASTEXITCODE"
        if ($LASTEXITCODE -ne 0) { exit 1 }
        Invoke-Smoke
    }
    'all' {
        & $mvn -o package -DskipTests -q
        Write-Host "BUILD_EXIT=$LASTEXITCODE"
        if ($LASTEXITCODE -ne 0) { exit 1 }
        # The JUnit suite used to be skipped here, so 'gate all' could report a
        # green build while no unit test had run at all.
        & $mvn -o test -q 2>&1 | Select-Object -Last 25
        Write-Host "TEST_EXIT=$LASTEXITCODE"
        if ($LASTEXITCODE -ne 0) { exit 1 }
        & $mvn -o test-compile -q
        Invoke-Css
        Invoke-Wiring
        Invoke-Icons
        Invoke-Contrast
        Invoke-Smoke
    }
}
