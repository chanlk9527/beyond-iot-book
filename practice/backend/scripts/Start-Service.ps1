[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('management', 'access', 'worker')]
    [string]$Role,
    [string]$InstanceId,
    [ValidateRange(1, 65535)]
    [int]$HttpPort,
    [ValidateRange(1, 65535)]
    [int]$MqttPort = 1883,
    [switch]$Build
)

$ErrorActionPreference = 'Stop'
$backendRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $InstanceId) { $InstanceId = "$Role-a" }
if ($InstanceId -notmatch '^[a-zA-Z0-9_-][a-zA-Z0-9._-]{0,149}$') {
    throw 'InstanceId must be a Kafka-compatible node identifier of at most 150 characters.'
}
if (-not $HttpPort) {
    $HttpPort = @{ management = 8080; access = 8081; worker = 8082 }[$Role]
}
foreach ($variable in @('DATABASE_PASSWORD', 'REDIS_URL', 'KAFKA_BOOTSTRAP_SERVERS')) {
    if (-not [Environment]::GetEnvironmentVariable($variable)) {
        throw "Set $variable in this terminal before starting an independent service. See platform-services/README.md."
    }
}
if ($Build) {
    Push-Location $backendRoot
    try {
        & mvn '-pl' "platform-services/$Role-service" '-am' 'package' '-DskipTests'
        if ($LASTEXITCODE -ne 0) { throw "Build failed: $LASTEXITCODE" }
    } finally { Pop-Location }
}
$serviceJar = Join-Path $backendRoot "platform-services/$Role-service/target/$Role-service-0.1.0-SNAPSHOT-exec.jar"
if (-not (Test-Path -LiteralPath $serviceJar -PathType Leaf)) {
    throw "Service JAR missing. Run with -Build or build platform-services/service-verification with -am package."
}
$options = @(
    '-jar', $serviceJar,
    '--nebula.eventbus.mode=kafka',
    '--nebula.redis.enabled=true',
    "--nebula.eventbus.node-id=$InstanceId",
    "--nebula.access.node-name=$InstanceId",
    "--server.port=$HttpPort"
)
if ($Role -eq 'access') { $options += "--nebula.mqtt.port=$MqttPort" }
Write-Host "Starting $Role ($InstanceId), HTTP $HttpPort"
& java @options
exit $LASTEXITCODE
