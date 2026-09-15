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
    # Upload via curl.exe (nativo no Win10+): o Invoke-RestMethod do PS 5.1 fecha
    # a conexao em uploads grandes (~50MB+) com "Erro inesperado em um envio".
    $url = "https://uploads.github.com/repos/$repo/releases/$($release.id)/assets?name=$name"
    $ok = $false
    for ($attempt = 1; $attempt -le 3 -and -not $ok; $attempt++) {
        if ($attempt -gt 1) { Write-Host "retry $attempt para $name"; Start-Sleep -Seconds 5 }
        $out = & curl.exe -sS -X POST -H "Authorization: Bearer $env:GH_TOKEN" -H "Content-Type: application/octet-stream" `
            --data-binary "@$f" --retry 2 --retry-delay 5 $url 2>&1
        $ok = ($LASTEXITCODE -eq 0)
        if (-not $ok) { Write-Host "curl erro ($LASTEXITCODE): $out" }
    }
    if (-not $ok) { throw "Falha no upload de $name apos 3 tentativas" }
    Write-Host "uploaded: $name"
}
