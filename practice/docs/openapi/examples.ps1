param(
    [string]$BaseUrl = $env:NEBULA_OPENAPI_BASE_URL,
    [string]$AppId = $env:NEBULA_OPENAPI_APP_ID,
    [string[]]$Scopes = @('device:read'),
    [switch]$RevokeAfterCheck
)

$ErrorActionPreference = 'Stop'
$clientSecret = $env:NEBULA_OPENAPI_CLIENT_SECRET
$tokenResponse = $null
$headers = $null
try {
    if ([string]::IsNullOrWhiteSpace($BaseUrl) -or [string]::IsNullOrWhiteSpace($AppId) -or [string]::IsNullOrWhiteSpace($clientSecret)) {
        throw 'Configure NEBULA_OPENAPI_BASE_URL, NEBULA_OPENAPI_APP_ID and NEBULA_OPENAPI_CLIENT_SECRET.'
    }
    $uri = [Uri]$BaseUrl
    if (-not $uri.IsAbsoluteUri -or $uri.Scheme -notin @('http', 'https') -or $uri.UserInfo -or $uri.Query -or $uri.Fragment) {
        throw 'Use an absolute HTTP(S) base URL without embedded credentials.'
    }
    if ($uri.Scheme -ne 'https' -and -not $uri.IsLoopback) {
        throw 'HTTP is allowed only for local loopback development.'
    }
    $base = $BaseUrl.TrimEnd('/')
    $body = @{ appId = $AppId; clientSecret = $clientSecret; scopes = @($Scopes) } | ConvertTo-Json -Compress
    $tokenResponse = Invoke-RestMethod -Method Post -Uri "$base/api/open/v1/oauth/token" -ContentType 'application/json' -Body $body -MaximumRedirection 0 -TimeoutSec 15
    $headers = @{ Authorization = "Bearer $($tokenResponse.accessToken)" }
    $identity = Invoke-RestMethod -Method Get -Uri "$base/api/open/v1/me" -Headers $headers -MaximumRedirection 0 -TimeoutSec 15
    [pscustomobject]@{ AppId = $identity.appId; TenantId = $identity.tenantId; Scopes = $identity.scopes -join ', '; ExpiresAt = $identity.expiresAt }
    if ($RevokeAfterCheck) {
        $null = Invoke-RestMethod -Method Post -Uri "$base/api/open/v1/oauth/revoke" -Headers $headers -MaximumRedirection 0 -TimeoutSec 15
        Write-Output 'Token revoked.'
    }
} catch {
    # Do not echo HTTP request bodies, credentials or access tokens on failure.
    throw 'OpenAPI example failed. Check configuration, approved scopes, application status and HTTP responses in the server logs.'
} finally {
    $clientSecret = $null
    $body = $null
    $headers = $null
    $tokenResponse = $null
}
