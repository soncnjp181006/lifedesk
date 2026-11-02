param(
    [ValidateSet('status', 'build', 'smoke', 'tests', 'all')]
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

function Invoke-Smoke {
    # Java writes its start-up notice to stderr; with ErrorActionPreference=Stop a
    # 2>&1 redirect turns that harmless line into a terminating error and kills the
    # gate before it reads the log. Run it under Continue and judge the log.
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & $java '-Dstdout.encoding=UTF-8' '-Dfile.encoding=UTF-8' -Xmx1024m `
        -cp "target\test-classes;target\classes;target\lib\*" `
        com.lifedesk.app.SmokeMain services 1> target\smoke-services.log 2> target\smoke-services.err.log
    $code = $LASTEXITCODE
    $ErrorActionPreference = $prev

    # The log is UTF-16 (PowerShell redirect default), so a plain grep for the check
    # marks finds nothing and a red run looks clean. Read it through .NET, which
    # honours the BOM, and fail the gate on any ✗ — exit 0 alone proves nothing.
    $log = [System.IO.File]::ReadAllLines("$root\target\smoke-services.log")
    $fail = @($log | Where-Object { $_.Contains([char]0x2717) })
    $pass = @($log | Where-Object { $_.Contains([char]0x2713) })
    $log | Where-Object { $_ -match '^SMOKE RESULT' } | ForEach-Object { $_.Substring(0, [Math]::Min(220, $_.Length)) }
    Write-Host "SMOKE_CHECKS: $($pass.Count) passed, $($fail.Count) failed (java exit $code)"
    $fail | ForEach-Object { Write-Host "  FAILED: $($_.Trim())" }
    if ($fail.Count -gt 0 -or $code -ne 0) {
        Write-Host 'SMOKE=RED'
        exit 1
    }
    Write-Host 'SMOKE=GREEN'
}

if ($Kill) { Kill-Instances }

function Invoke-CompileWitness {
    # A class file carrying "Unresolved compilation problem" was written by a compiler
    # that gave up on the source and left a method that throws when it runs. Maven then
    # sees a class newer than its .java, compiles nothing, and reports exit 0, so the
    # exit code cannot be trusted as proof that the code compiles. Read the artefacts.
    $bad = @(Get-ChildItem "$root\target\classes", "$root\target\test-classes" -Recurse `
            -Filter *.class -ErrorAction SilentlyContinue |
        Where-Object {
            Select-String -Path $_.FullName -SimpleMatch 'Unresolved compilation' -Quiet
        })
    if ($bad.Count -gt 0) {
        $bad | Select-Object -First 8 | ForEach-Object {
            Write-Host "  NOT COMPILED: $($_.FullName)"
        }
        # ASCII only in this file: Windows PowerShell reads a BOM-less script as ANSI,
        # and a UTF-8 byte of Vietnamese text can decode as a smart quote, which it then
        # treats as an open string.
        Write-Host "COMPILE_WITNESS: $($bad.Count) class with unresolved compile errors"
        Write-Host 'COMPILE_WITNESS=RED'
        exit 1
    }
    Write-Host 'COMPILE_WITNESS=GREEN'
}

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
        if ($LASTEXITCODE -ne 0) { exit 1 }
        Invoke-CompileWitness
    }
    'tests' {
        & $mvn -o test -q 2>&1 | Select-Object -Last 40
        Write-Host "TEST_EXIT=$LASTEXITCODE"
    }
    'smoke' {
        & $mvn -o test-compile -q
        Write-Host "COMPILE_EXIT=$LASTEXITCODE"
        if ($LASTEXITCODE -ne 0) { exit 1 }
        Invoke-CompileWitness
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
        Invoke-CompileWitness
        Invoke-Smoke
    }
}
