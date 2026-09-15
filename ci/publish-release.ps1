# Publishes build artifacts to the PUBLIC space-connect-releases repo.
# PowerShell twin of ci/publish-release.sh for the self-hosted Windows runner
# (no gh CLI dependency there). Same behavior: creates the release when
# missing, overwrites same-name assets.
#
# Usage: $env:GH_TOKEN set; ci/publish-release.ps1 -Tag v0.1.6 -Files a.msi,b.zip
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Tag,
    [Parameter(Mandatory = $true, ValueFromRemainingArguments = $true)][string[]]$Files
)
$ErrorActionPreference = 'Stop'
# Windows PowerShell 5.1 default TLS (1.0) is rejected by api.github.com.
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$repo = 'Spike-Corp/space-connect-releases'
$headers = @{
    Authorization          = "Bearer $env:GH_TOKEN"
    Accept                 = 'application/vnd.github+json'
    'X-GitHub-Api-Version' = '2022-11-28'
}
if (-not $env:GH_TOKEN) { throw 'GH_TOKEN env var is required' }

$release = $null
try {
    $release = Invoke-RestMethod -Headers $headers -Uri "https://api.github.com/repos/$repo/releases/tags/$Tag"
} catch { }
if (-not $release) {
    $body = @{ tag_name = $Tag; name = "Space Connect $Tag" } | ConvertTo-Json
    $release = Invoke-RestMethod -Method Post -Headers $headers -Uri "https://api.github.com/repos/$repo/releases" -Body $body
}
Write-Host "Release $Tag id=$($release.id)"

$assets = @(Invoke-RestMethod -Headers $headers -Uri "https://api.github.com/repos/$repo/releases/$($release.id)/assets?per_page=100")

foreach ($f in $Files) {
    if (-not (Test-Path $f)) { throw "missing artifact: $f" }
    $name = Split-Path $f -Leaf
    $old = $assets | Where-Object { $_.name -eq $name } | Select-Object -First 1
    if ($old) {
        Invoke-RestMethod -Method Delete -Headers $headers -Uri "https://api.github.com/repos/$repo/releases/assets/$($old.id)" | Out-Null
    }
    $uploadHeaders = @{
        Authorization          = $headers.Authorization
        Accept                 = $headers.Accept
        'X-GitHub-Api-Version' = $headers.'X-GitHub-Api-Version'
        'Content-Type'         = 'application/octet-stream'
    }
    try {
        Invoke-RestMethod -Method Post -Headers $uploadHeaders `
            -Uri "https://uploads.github.com/repos/$repo/releases/$($release.id)/assets?name=$name" `
            -InFile $f | Out-Null
        Write-Host "uploaded: $name"
    } catch {
        $resp = $_.Exception.Response
        if ($resp) {
            $stream = $resp.GetResponseStream()
            $reader = New-Object System.IO.StreamReader($stream)
            Write-Host "HTTP $([int]$resp.StatusCode) $($resp.StatusDescription): $($reader.ReadToEnd())"
        } else {
            Write-Host "Upload error: $($_.Exception.Message)"
        }
        throw
    }
}
