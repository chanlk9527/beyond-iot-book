[CmdletBinding()]
param(
    [string]$ConfigPath = (Join-Path $PSScriptRoot '../.env.local'),
    [string]$JarPath = (Join-Path $PSScriptRoot '../platform-bootstrap/target/platform-bootstrap-0.1.0-SNAPSHOT.jar'),
    [switch]$ValidateOnly
)

$ErrorActionPreference = 'Stop'
$utf8 = [System.Text.UTF8Encoding]::new($false, $true)
if (![System.IO.File]::Exists($ConfigPath)) {
    throw 'Local configuration is missing. Copy backend/.env.example to backend/.env.local and fill it in.'
}

$allowedKeys = @(
    'DATABASE_URL', 'DATABASE_USERNAME', 'DATABASE_PASSWORD',
    'REDIS_ENABLED', 'REDIS_URL', 'REDIS_COMMAND_TIMEOUT_MS',
    'HISTORY_PROVIDER', 'HISTORY_MAXIMUM_RETENTION_DAYS', 'HISTORY_CLEANUP_INTERVAL_MS',
    'TDENGINE_URL', 'TDENGINE_USERNAME', 'TDENGINE_PASSWORD', 'TDENGINE_DATABASE',
    'MQTT_ENABLED', 'MQTT_PORT', 'MQTT_WEBSOCKET_ENABLED', 'MQTT_WEBSOCKET_PORT',
    'EVENTBUS_MODE', 'EVENTBUS_NODE_ID', 'EVENTBUS_NAMESPACE', 'EVENTBUS_TOPIC', 'EVENTBUS_FAILURE_TOPIC',
    'KAFKA_BOOTSTRAP_SERVERS', 'EVENTBUS_DUBBO_HOST', 'EVENTBUS_DUBBO_PORT', 'EVENTBUS_DUBBO_CREDENTIAL', 'EVENTBUS_DUBBO_DISCOVERY',
    'NACOS_SERVER_ADDR', 'NACOS_NAMESPACE', 'NACOS_GROUP', 'NACOS_EVENTBUS_SERVICE', 'NACOS_USERNAME', 'NACOS_PASSWORD',
    'NEBULA_MANAGEMENT_INITIAL_ACCOUNT_USERNAME', 'NEBULA_MANAGEMENT_INITIAL_ACCOUNT_PASSWORD',
    'NEBULA_MANAGEMENT_INITIAL_ACCOUNT_PLATFORM_ADMIN'
)
$settings = @{}
# Treat values literally. Never dot-source or execute configuration as PowerShell code.
$content = [System.IO.File]::ReadAllText([System.IO.Path]::GetFullPath($ConfigPath), $utf8)
foreach ($line in ($content -split '\r?\n')) {
    if ([string]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith('#')) { continue }
    $separator = $line.IndexOf('=')
    if ($separator -lt 1) { throw 'Invalid local configuration: expected KEY=value. Values are not printed.' }
    $key = $line.Substring(0, $separator).Trim()
    if ($key -cnotin $allowedKeys) { throw 'Unsupported configuration key. Only documented database, Redis, history, MQTT, EventBus, Nacos and initial account settings are allowed.' }
    if ($settings.ContainsKey($key)) { throw 'Duplicate local configuration key. Values are not printed.' }
    $value = $line.Substring($separator + 1)
    if ([string]::IsNullOrWhiteSpace($value) -or $value.Contains('__FILL_')) {
        throw "Fill in $key in the local configuration file before starting."
    }
    $settings[$key] = $value
}
foreach ($required in @('DATABASE_URL', 'DATABASE_USERNAME', 'DATABASE_PASSWORD')) {
    if (!$settings.ContainsKey($required)) { throw "Missing $required in the local configuration file." }
}
if (!$settings['DATABASE_URL'].StartsWith('jdbc:postgresql://') -and !$settings['DATABASE_URL'].StartsWith('jdbc:mysql://')) {
    throw 'DATABASE_URL must be a PostgreSQL or MySQL JDBC URL.'
}
$initialName = $settings.ContainsKey('NEBULA_MANAGEMENT_INITIAL_ACCOUNT_USERNAME')
$initialPassword = $settings.ContainsKey('NEBULA_MANAGEMENT_INITIAL_ACCOUNT_PASSWORD')
if ($initialName -ne $initialPassword) { throw 'Supply both initial management account settings, or omit both.' }
if ($initialName) {
    if ($settings['NEBULA_MANAGEMENT_INITIAL_ACCOUNT_USERNAME'].Length -gt 100) {
        throw 'Initial management username must not exceed 100 characters.'
    }
    $initialSecret = $settings['NEBULA_MANAGEMENT_INITIAL_ACCOUNT_PASSWORD']
    if ($utf8.GetByteCount($initialSecret) -gt 72) {
        throw 'Initial management password must not exceed 72 UTF-8 bytes.'
    }
}
if ($settings.ContainsKey('NEBULA_MANAGEMENT_INITIAL_ACCOUNT_PLATFORM_ADMIN')) {
    if (!$initialName -or $settings['NEBULA_MANAGEMENT_INITIAL_ACCOUNT_PLATFORM_ADMIN'] -cnotin @('true','false')) {
        throw 'Platform-admin initialization requires both initial account settings and an explicit true/false value.'
    }
}
if ($settings.ContainsKey('REDIS_ENABLED') -and $settings['REDIS_ENABLED'] -cnotin @('true','false')) {
    throw 'REDIS_ENABLED must be true or false.'
}
$redisEnabled = if ($settings.ContainsKey('REDIS_ENABLED')) { $settings['REDIS_ENABLED'] } else { $env:REDIS_ENABLED }
$redisUrl = if ($settings.ContainsKey('REDIS_URL')) { $settings['REDIS_URL'] } else { $env:REDIS_URL }
if ($redisEnabled -eq 'true' -and $redisUrl -notmatch '^rediss?://[^/]+') {
    throw 'Redis requires REDIS_URL using redis:// or rediss:// (not the Upstash REST URL).'
}
if ($settings.ContainsKey('MQTT_ENABLED') -and $settings['MQTT_ENABLED'] -cnotin @('true','false')) { throw 'MQTT_ENABLED must be true or false.' }
if ($settings.ContainsKey('MQTT_PORT')) {
    $mqttPort = 0
    if (![int]::TryParse($settings['MQTT_PORT'], [ref]$mqttPort) -or $mqttPort -lt 1 -or $mqttPort -gt 65535) { throw 'MQTT_PORT must be between 1 and 65535.' }
}
if ($settings.ContainsKey('MQTT_WEBSOCKET_ENABLED') -and $settings['MQTT_WEBSOCKET_ENABLED'] -cnotin @('true','false')) { throw 'MQTT_WEBSOCKET_ENABLED must be true or false.' }
if ($settings.ContainsKey('MQTT_WEBSOCKET_PORT')) {
    $websocketPort = 0
    if (![int]::TryParse($settings['MQTT_WEBSOCKET_PORT'], [ref]$websocketPort) -or $websocketPort -lt 1 -or $websocketPort -gt 65535) { throw 'MQTT_WEBSOCKET_PORT must be between 1 and 65535.' }
}
if ($ValidateOnly) {
    Write-Output 'Local configuration is valid. No database connection was attempted.'
    return
}
if (![System.IO.File]::Exists($JarPath)) {
    throw 'Application JAR is missing. Build backend with Maven before starting.'
}
$java = Get-Command java -CommandType Application -ErrorAction Stop | Select-Object -First 1
# Spring Boot loads nested dependencies lazily. Keep the running archive separate
# from Maven target so a later package/repackage cannot break active requests.
$runtimeDirectory = Join-Path $PSScriptRoot ('../../.run/backend-runtime/' + [Guid]::NewGuid().ToString('N'))
$runtimeJar = Join-Path $runtimeDirectory 'platform-bootstrap.jar'
$previous = @{}
try {
    $null = New-Item -ItemType Directory -Path $runtimeDirectory -Force
    Copy-Item -LiteralPath ([IO.Path]::GetFullPath($JarPath)) -Destination $runtimeJar -ErrorAction Stop
    foreach ($key in $settings.Keys) {
        $previous[$key] = [System.Environment]::GetEnvironmentVariable($key, 'Process')
        [System.Environment]::SetEnvironmentVariable($key, $settings[$key], 'Process')
    }
    Write-Output 'Starting management backend. Flyway will apply pending database migrations.'
    & $java.Source '-jar' ([System.IO.Path]::GetFullPath($runtimeJar)) '--spring.profiles.active=management'
    if ($LASTEXITCODE -ne 0) { throw "Management backend exited with code $LASTEXITCODE." }
} finally {
    foreach ($key in $previous.Keys) {
        [System.Environment]::SetEnvironmentVariable($key, $previous[$key], 'Process')
    }
    if (Test-Path -LiteralPath $runtimeJar) { Remove-Item -LiteralPath $runtimeJar -Force }
    if (Test-Path -LiteralPath $runtimeDirectory) { Remove-Item -LiteralPath $runtimeDirectory -Force }
}
