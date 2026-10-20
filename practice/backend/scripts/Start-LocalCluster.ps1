[CmdletBinding()]
param(
    [string]$Distribution = 'Ubuntu',
    [string]$ComposePath = '/home/admini/workspace/dev-env/docker-compose.yml',
    [ValidateSet('kafka','dubbo')]
    [string]$EventBusMode,
    [switch]$Build,
    [switch]$SecondAccess,
    [switch]$Restart,
    [ValidateSet('management','access','worker','access-b')]
    [string[]]$Services = @('management','access','worker')
)
$ErrorActionPreference = 'Stop'
$utf8 = [System.Text.UTF8Encoding]::new($false, $true)
$backendRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$practiceRoot = [IO.Path]::GetFullPath((Join-Path $backendRoot '..'))
$runtimeRoot = Join-Path $practiceRoot '.run/services'
$previous = @{}

function Read-Text([string]$Path) {
    try { return [IO.File]::ReadAllText($Path, $utf8) }
    catch {
        $lines = & rg --no-line-number '^' $Path
        if ($LASTEXITCODE -ne 0) { throw "Cannot read configuration: $Path" }
        return $lines -join "`n"
    }
}
function Set-ChildEnvironment([string]$Name, [string]$Value) {
    if (-not $previous.ContainsKey($Name)) { $previous[$Name] = [Environment]::GetEnvironmentVariable($Name, 'Process') }
    [Environment]::SetEnvironmentVariable($Name, $Value, 'Process')
}
function Wait-Health([string]$Role, [int]$Port) {
    $deadline = [DateTime]::UtcNow.AddSeconds(90)
    do {
        try {
            $health = Invoke-RestMethod "http://127.0.0.1:$Port/actuator/health" -TimeoutSec 3
            if ($health.status -eq 'UP') { return }
        } catch { }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "$Role did not become healthy. Check $runtimeRoot/$Role/stdout.log and stderr.log."
}

try {
    $config = Join-Path $backendRoot '.env.local'
    if (-not (Test-Path -LiteralPath $config)) { throw 'Configure backend/.env.local first.' }
    $allowed = @('DATABASE_URL','DATABASE_USERNAME','DATABASE_PASSWORD','MQTT_WEBSOCKET_ENABLED',
        'EVENTBUS_MODE','EVENTBUS_NAMESPACE','EVENTBUS_DUBBO_CREDENTIAL','EVENTBUS_DUBBO_HOST','EVENTBUS_DUBBO_DISCOVERY',
        'NACOS_SERVER_ADDR','NACOS_NAMESPACE','NACOS_GROUP','NACOS_EVENTBUS_SERVICE','NACOS_USERNAME','NACOS_PASSWORD',
        'HISTORY_PROVIDER','HISTORY_MAXIMUM_RETENTION_DAYS','HISTORY_CLEANUP_INTERVAL_MS',
        'TDENGINE_URL','TDENGINE_USERNAME','TDENGINE_PASSWORD','TDENGINE_DATABASE',
        'NEBULA_MANAGEMENT_INITIAL_ACCOUNT_USERNAME','NEBULA_MANAGEMENT_INITIAL_ACCOUNT_PASSWORD',
        'NEBULA_MANAGEMENT_INITIAL_ACCOUNT_PLATFORM_ADMIN')
    foreach ($line in ((Read-Text $config) -split '\r?\n')) {
        if ($line -match '^\s*([A-Z_]+)=(.*)$' -and $Matches[1] -in $allowed) {
            Set-ChildEnvironment $Matches[1] $Matches[2]
        }
    }
    foreach ($name in @('DATABASE_URL','DATABASE_USERNAME','DATABASE_PASSWORD')) {
        if (-not [Environment]::GetEnvironmentVariable($name)) { throw "Missing $name in local configuration." }
    }

    if (-not $EventBusMode) { $EventBusMode = if ($env:EVENTBUS_MODE) { $env:EVENTBUS_MODE } else { 'kafka' } }
    if ($EventBusMode -notin @('kafka','dubbo')) { throw 'Local cluster requires Kafka or Dubbo EventBus.' }
    if ($EventBusMode -eq 'dubbo' -and (!$env:EVENTBUS_DUBBO_CREDENTIAL -or $env:EVENTBUS_DUBBO_CREDENTIAL.Length -lt 32)) {
        throw 'Set EVENTBUS_DUBBO_CREDENTIAL (at least 32 characters) in backend/.env.local.'
    }
    Write-Host 'Starting WSL development dependencies...'
    & wsl -d $Distribution -- sh -c 'systemctl is-active --quiet docker || sudo -n systemctl start docker'
    if ($LASTEXITCODE -ne 0) { throw 'Docker could not start in WSL. Start Docker there and retry.' }
    & wsl -d $Distribution -- docker compose -f $ComposePath up -d
    if ($LASTEXITCODE -ne 0) { throw 'WSL Compose startup failed.' }
    if ($EventBusMode -eq 'kafka') {
    & wsl -d $Distribution -- docker wait kafka-init-dev | Out-Null
    $initExit = & wsl -d $Distribution -- docker inspect kafka-init-dev --format '{{.State.ExitCode}}'
    if ($LASTEXITCODE -ne 0 -or ($initExit -join '').Trim() -ne '0') { throw 'Kafka topic initialization failed.' }

    }

    # WSL localhost forwarding can expose Nacos HTTP without its required client gRPC port.
    # Resolve only the unconfigured local default; never replace an explicitly configured registry.
    if ($EventBusMode -eq 'dubbo' -and $env:EVENTBUS_DUBBO_DISCOVERY -ne 'jdbc' -and !$env:NACOS_SERVER_ADDR) {
        function Test-NacosPort([string]$Address, [int]$Port) {
            $socket = [Net.Sockets.TcpClient]::new()
            try { return $socket.ConnectAsync($Address,$Port).Wait(1000) -and $socket.Connected }
            catch { return $false }
            finally { $socket.Dispose() }
        }
        if (!(Test-NacosPort '127.0.0.1' 8848) -or !(Test-NacosPort '127.0.0.1' 9848)) {
            $addresses = ((& wsl -d $Distribution -- hostname -I) -join ' ') -split '\s+'
            $selected = $addresses | Where-Object { $_ -match '^\d+\.\d+\.\d+\.\d+$' -and (Test-NacosPort $_ 8848) -and (Test-NacosPort $_ 9848) } | Select-Object -First 1
            if (!$selected) { throw 'Nacos requires reachable HTTP 8848 and client gRPC 9848. Configure NACOS_SERVER_ADDR or restore WSL port forwarding.' }
            Set-ChildEnvironment 'NACOS_SERVER_ADDR' "${selected}:8848"
            Write-Host "Nacos localhost gRPC unavailable; using WSL registry ${selected}:8848 for this run."
        }
    }
    # Reuse the existing local Redis password without writing it into scripts or logs.
    $raw = (& wsl -d $Distribution -- docker inspect redis-dev) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect local Redis.' }
    $redis = ($raw | ConvertFrom-Json)[0]
    $redisArgs = @($redis.Config.Cmd)
    $passwordIndex = [Array]::IndexOf($redisArgs, '--requirepass')
    $redisUrl = 'redis://127.0.0.1:6379'
    if ($passwordIndex -ge 0 -and $passwordIndex + 1 -lt $redisArgs.Count) {
        $redisUrl = 'redis://default:' + [Uri]::EscapeDataString($redisArgs[$passwordIndex + 1]) + '@127.0.0.1:6379'
    }
    Set-ChildEnvironment 'REDIS_URL' $redisUrl
    Set-ChildEnvironment 'REDIS_ENABLED' 'true'
    Set-ChildEnvironment 'KAFKA_BOOTSTRAP_SERVERS' '127.0.0.1:9092'
    Set-ChildEnvironment 'EVENTBUS_MODE' $EventBusMode
    Set-ChildEnvironment 'NEBULA_RUNTIME_ROLE' ''

    $version = ([xml](Read-Text (Join-Path $backendRoot 'pom.xml'))).project.version
    if ($Build) {
        Push-Location $backendRoot
        try {
            & mvn '-pl' 'platform-services/service-verification' '-am' 'package' '-DskipTests'
            if ($LASTEXITCODE -ne 0) { throw 'Service build failed.' }
        } finally { Pop-Location }
    }
    $javaCommand = (Get-Command java -CommandType Application | Select-Object -First 1).Source
    # Oracle's javapath shim can spawn a child JVM; use the real binary for stable PID records.
    $savedErrorAction = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue' # Windows PowerShell wraps native stderr in ErrorRecord.
        $javaHomeLine = (& $javaCommand '-XshowSettings:properties' '-version' 2>&1 |
            ForEach-Object { $_.ToString() } | Where-Object { $_ -match '^\s*java.home\s*=' } | Select-Object -First 1)
    } finally { $ErrorActionPreference = $savedErrorAction }
    $java = if ($javaHomeLine) { Join-Path (($javaHomeLine -split '=',2)[1].Trim()) 'bin/java.exe' } else { $javaCommand }

    ${instances} = @($Services | Select-Object -Unique)
    if ($SecondAccess) { $instances += 'access-b' }
    foreach ($instance in $instances) {
        $role = if ($instance -eq 'access-b') { 'access' } else { $instance }
        $nodeName = if ($instance -eq 'access-b') { 'access-b' } else { "$role-a" }
        $port = @{ management=8080; access=8081; worker=8082; 'access-b'=8083 }[$instance]
        $directory = Join-Path $runtimeRoot $instance
        $statePath = Join-Path $directory 'process.json'
        $jar = Join-Path $directory "$role-service.jar"
        $existing = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($existing) {
            if (-not (Test-Path -LiteralPath $statePath)) { throw "Port $port is already in use by an untracked process." }
            $state = (Read-Text $statePath) | ConvertFrom-Json
            $process = Get-Process -Id $existing.OwningProcess
            if ($process.Id -ne $state.pid -or $process.StartTime.ToUniversalTime().Ticks -ne [long]$state.startTicks) {
                throw "Port $port belongs to another process; it was left running."
            }
            if (-not $Restart -and $state.eventBusMode -and $state.eventBusMode -ne $EventBusMode) {
                throw "$instance is running in $($state.eventBusMode) mode. Use -Restart to switch to $EventBusMode."
            }
            if (-not $Restart) {
                Wait-Health $role $port
                Write-Host "$role already running: http://127.0.0.1:$port"
                continue
            }
            $artifact = Join-Path $backendRoot "platform-services/$role-service/target/$role-service-$version-exec.jar"
            if (-not (Test-Path -LiteralPath $artifact)) { throw "Build $role before restarting it." }
            Stop-Process -InputObject $process
            if (-not $process.WaitForExit(15000)) { throw "$role did not stop within 15 seconds." }
        }
        $source = Join-Path $backendRoot "platform-services/$role-service/target/$role-service-$version-exec.jar"
        if (-not (Test-Path -LiteralPath $source)) { throw 'Service artifacts missing. Run again with -Build.' }
        [IO.Directory]::CreateDirectory($directory) | Out-Null
        Copy-Item -LiteralPath $source -Destination $jar
        $arguments = @('-Xmx512m','-jar',('"'+$jar+'"'),'--server.address=127.0.0.1',"--server.port=$port",
            "--nebula.runtime.role=$role","--nebula.eventbus.mode=$EventBusMode","--nebula.eventbus.node-id=$nodeName",
            '--nebula.redis.enabled=true',"--nebula.access.node-name=$nodeName")
        $dubboPort = $null
        if ($EventBusMode -eq 'dubbo') {
            $dubboPort = @{ management=20880; access=20881; worker=20882; 'access-b'=20883 }[$instance]
            if (Get-NetTCPConnection -State Listen -LocalPort $dubboPort -ErrorAction SilentlyContinue) {
                throw "Dubbo port $dubboPort is already in use."
            }
            $arguments += "--nebula.eventbus.dubbo.port=$dubboPort"
        }
        if ($role -eq 'access') {
            $mqttPort = if ($instance -eq 'access-b') { 1884 } else { 1883 }
            if (Get-NetTCPConnection -State Listen -LocalPort $mqttPort -ErrorAction SilentlyContinue) {
                throw "MQTT port $mqttPort is already in use."
            }
            $websocketPort = if ($instance -eq 'access-b') { 8085 } else { 8084 }
            if ($env:MQTT_WEBSOCKET_ENABLED -ne 'false' -and (Get-NetTCPConnection -State Listen -LocalPort $websocketPort -ErrorAction SilentlyContinue)) { throw "MQTT WebSocket port $websocketPort is already in use." }
            $arguments += '--nebula.mqtt.host=127.0.0.1'; $arguments += "--nebula.mqtt.port=$mqttPort"; $arguments += "--nebula.mqtt.websocket-port=$(if ($instance -eq 'access-b') { 8085 } else { 8084 })"
        }
        $process = Start-Process -FilePath $java -ArgumentList $arguments -WindowStyle Hidden -WorkingDirectory $practiceRoot `
            -RedirectStandardOutput (Join-Path $directory 'stdout.log') -RedirectStandardError (Join-Path $directory 'stderr.log') -PassThru
        $state = @{ role=$role;pid=$process.Id;startTicks=$process.StartTime.ToUniversalTime().Ticks;httpPort=$port;jar=$jar;eventBusMode=$EventBusMode;dubboPort=$dubboPort }
        [IO.File]::WriteAllText($statePath,($state | ConvertTo-Json),$utf8)
        Wait-Health $role $port
        Write-Host "$role UP: http://127.0.0.1:$port"
    }
    Write-Host "Cluster ready ($EventBusMode). MQTT: 127.0.0.1:1883. Logs: practice/.run/services/"
} finally {
    foreach ($name in $previous.Keys) { [Environment]::SetEnvironmentVariable($name,$previous[$name],'Process') }
}