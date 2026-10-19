[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$utf8 = [Text.UTF8Encoding]::new($false,$true)
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../.run/services'))
foreach ($role in @('access-b','access','worker','management')) {
    $path = Join-Path $root "$role/process.json"
    if (-not (Test-Path -LiteralPath $path)) { continue }
    try { $text = [IO.File]::ReadAllText($path,$utf8) }
    catch { $text = (& rg --no-line-number '^' $path) -join "`n"; if ($LASTEXITCODE -ne 0) { throw "Cannot read $role process record" } }
    $state = $text | ConvertFrom-Json
    $process = Get-Process -Id $state.pid -ErrorAction SilentlyContinue
    if (-not $process) { Write-Host "$role already stopped"; continue }
    if ($process.ProcessName -notmatch '^java(w)?$' -or $process.StartTime.ToUniversalTime().Ticks -ne [long]$state.startTicks) {
        throw "Process identity changed for $role; no unrelated process was stopped."
    }
    Stop-Process -Id $process.Id
    $process.WaitForExit(10000) | Out-Null
    Write-Host "$role stopped"
}
Write-Host 'Database, Redis and Kafka remain running.'