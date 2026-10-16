#Requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('Start', 'Stop', 'Restart', 'Status')]
    [string]$Action = 'Start',
    [switch]$Build,
    [switch]$Install,
    [switch]$BackendOnly,
    [switch]$FrontendOnly,
    [switch]$ValidateOnly,
    [ValidateRange(5, 600)][int]$TimeoutSeconds = 90,
    [string]$ConfigPath = (Join-Path $PSScriptRoot 'backend/.env.local')
)

$ErrorActionPreference = 'Stop'
$utf8 = [Text.UTF8Encoding]::new($false, $true)
$runtime = Join-Path $PSScriptRoot '.run'
$backend = Join-Path $PSScriptRoot 'backend'
$console = Join-Path $PSScriptRoot 'console'
$jar = Join-Path $backend 'platform-bootstrap/target/platform-bootstrap-0.1.0-SNAPSHOT.jar'
$backendScript = Join-Path $backend 'scripts/Start-Management.ps1'
$vite = Join-Path $console 'node_modules/vite/bin/vite.js'
if ($BackendOnly -and $FrontendOnly) { throw 'Choose BackendOnly or FrontendOnly, not both.' }
if ($Build -and $FrontendOnly) { throw 'Build applies to backend; remove FrontendOnly.' }
if ($Install -and $BackendOnly) { throw 'Install applies to frontend; remove BackendOnly.' }
if (($Build -or $Install) -and $Action -in @('Stop', 'Status')) { throw 'Build/Install requires Start or Restart.' }
$services = @()
if (!$FrontendOnly) { $services += 'backend' }
if (!$BackendOnly) { $services += 'frontend' }

function Get-ServiceState([string]$Name) {
    $path = Join-Path $runtime "$Name.json"
    if (Test-Path -LiteralPath $path) {
        return ([IO.File]::ReadAllText($path, $utf8) | ConvertFrom-Json)
    }
}

function Get-OwnedProcess($State) {
    if ($null -eq $State) { return $null }
    $process = Get-Process -Id $State.ProcessId -ErrorAction SilentlyContinue
    if ($process -and $process.StartTime.ToUniversalTime().Ticks.ToString() -eq $State.StartTicks) {
        return $process
    }
    return $null
}

function Test-Port([int]$Port) {
    $client = [Net.Sockets.TcpClient]::new()
    try {
        $task = $client.ConnectAsync('127.0.0.1', $Port)
        return ($task.Wait(200) -and $client.Connected)
    } catch { return $false } finally { $client.Dispose() }
}

function Test-Ready([string]$Name) {
    $url = if ($Name -eq 'backend') { 'http://127.0.0.1:8080/actuator/health' } else { 'http://127.0.0.1:5173/' }
    try {
        $response = Invoke-WebRequest -Uri $url -TimeoutSec 2 -NoProxy
        if ($Name -eq 'backend') {
            $json = if ($response.Content -is [byte[]]) { [Text.Encoding]::UTF8.GetString($response.Content) } else { $response.Content }
            return (($json | ConvertFrom-Json).status -eq 'UP')
        }
        return $response.StatusCode -eq 200
    } catch { return $false }
}

function Stop-ServiceProcess([string]$Name) {
    $state = Get-ServiceState $Name
    $process = Get-OwnedProcess $state
    if (!$process) { Write-Host "$Name is not running (no owned process)."; return }
    # Take one process snapshot. Only stop this recorded process and its descendants.
    $snapshot = @(Get-CimInstance Win32_Process)
    $ids = [Collections.Generic.List[int]]::new()
    $ids.Add($process.Id)
    for ($i = 0; $i -lt $ids.Count; $i++) {
        foreach ($child in $snapshot) {
            if ($child.ParentProcessId -eq $ids[$i] -and !$ids.Contains([int]$child.ProcessId)) {
                $ids.Add([int]$child.ProcessId)
            }
        }
    }
    for ($i = $ids.Count - 1; $i -ge 0; $i--) {
        $candidate = Get-Process -Id $ids[$i] -ErrorAction SilentlyContinue
        if ($candidate -and $candidate.StartTime -ge $process.StartTime) {
            Stop-Process -InputObject $candidate -Force -ErrorAction Stop
        }
    }
    Remove-Item -LiteralPath (Join-Path $runtime "$Name.json") -Force
    Write-Host "$Name stopped."
}

if ($Action -eq 'Status') {
    foreach ($name in $services) {
        $process = Get-OwnedProcess (Get-ServiceState $name)
        $status = if (!$process) { 'not managed/running' } elseif (Test-Ready $name) { 'ready' } else { 'running, not ready (check logs)' }
        Write-Host "${name}: $status"
    }
    return
}
if ($Action -in @('Stop', 'Restart') -and !$ValidateOnly) {
    foreach ($name in $services) { Stop-ServiceProcess $name }
    if ($Action -eq 'Stop') { return }
}

# Check all requirements before launching either service. Never install or build implicitly.
if (!$FrontendOnly) {
    $null = Get-Command java -CommandType Application -ErrorAction Stop
    & $backendScript -ConfigPath $ConfigPath -ValidateOnly
    if (!$Build -and !(Test-Path -LiteralPath $jar)) { throw 'Backend JAR missing. Run ./Manage.ps1 -Build first.' }
    if ($Build) {
        $maven = (Get-Command mvn.cmd -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
        if (Get-OwnedProcess (Get-ServiceState 'backend')) { throw 'Stop backend before building, or use -Action Restart -Build.' }
    }
}
if (!$BackendOnly) {
    $node = (Get-Command node -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
    if ($Install) {
        $npm = (Get-Command npm.cmd -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
        if (Get-OwnedProcess (Get-ServiceState 'frontend')) { throw 'Stop frontend before installing, or use -Action Restart -Install.' }
    } elseif (!(Test-Path -LiteralPath $vite)) { throw 'Frontend dependencies missing. Run ./Manage.ps1 -Install first.' }
}
foreach ($name in $services) {
    $port = if ($name -eq 'backend') { 8080 } else { 5173 }
    if (!(Get-OwnedProcess (Get-ServiceState $name)) -and (Test-Port $port)) {
        throw "Port $port is occupied by an unmanaged process. Stop it manually; this script will not terminate it."
    }
}
if ($ValidateOnly) { Write-Host 'Preflight passed. No build, install, start or stop was performed.'; return }

if ($Build) {
    Push-Location $backend
    try {
        & $maven -B -ntp -s .mvn/settings-central.xml -pl platform-bootstrap -am '-DskipTests' package
        if ($LASTEXITCODE -ne 0) { throw 'Backend build failed. No new services were started.' }
    } finally { Pop-Location }
}
if ($Install) {
    Push-Location $console
    try {
        & $npm ci --no-audit --no-fund
        if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed.' }
    } finally { Pop-Location }
}

$null = New-Item -ItemType Directory -Path $runtime -Force
$shell = (Get-Process -Id $PID).Path
$started = [Collections.Generic.List[string]]::new()
$clock = [Diagnostics.Stopwatch]::StartNew()
try {
    foreach ($name in $services) {
        if (Get-OwnedProcess (Get-ServiceState $name)) { Write-Host "$name already running; reusing."; continue }
        $log = Join-Path $runtime "$name.log"
        $errorLog = Join-Path $runtime "$name.err.log"
        if ($name -eq 'backend') {
            $exe = $shell
            $arguments = @('-NoLogo', '-NoProfile', '-File', "`"$backendScript`"", '-ConfigPath', "`"$([IO.Path]::GetFullPath($ConfigPath))`"", '-JarPath', "`"$jar`"")
            $directory = $backend
        } else {
            $exe = $node
            $arguments = @("`"$vite`"", '--host', '127.0.0.1', '--port', '5173', '--strictPort')
            $directory = $console
        }
        $process = Start-Process -FilePath $exe -ArgumentList $arguments -WorkingDirectory $directory -WindowStyle Hidden -RedirectStandardOutput $log -RedirectStandardError $errorLog -PassThru
        $state = @{ ProcessId = $process.Id; StartTicks = $process.StartTime.ToUniversalTime().Ticks.ToString() }
        [IO.File]::WriteAllText((Join-Path $runtime "$name.json"), ($state | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
        $started.Add($name)
        Write-Host "$name launched (PID $($process.Id))."
    }
    # Both processes are started before polling readiness.
    $pending = [Collections.Generic.List[string]]::new()
    foreach ($name in $services) { $pending.Add($name) }
    while ($pending.Count -gt 0) {
        foreach ($name in @($pending.ToArray())) {
            if (!(Get-OwnedProcess (Get-ServiceState $name))) { throw "$name exited. See $runtime/$name.err.log and $name.log." }
            if (Test-Ready $name) { $null = $pending.Remove($name); Write-Host "$name ready." }
        }
        if ($pending.Count -eq 0) { break }
        if ($clock.Elapsed.TotalSeconds -ge $TimeoutSeconds) { throw "Startup timed out. Check logs in $runtime (database/Redis connectivity may be unavailable)." }
        Start-Sleep -Milliseconds 400
    }
    Write-Host "Ready in $([Math]::Round($clock.Elapsed.TotalSeconds, 1))s. Console: http://127.0.0.1:5173 ; backend: http://127.0.0.1:8080"
    Write-Host "Logs: $runtime ; stop: ./Manage.ps1 -Action Stop"
} catch {
    foreach ($name in $started) { Stop-ServiceProcess $name }
    throw
}
