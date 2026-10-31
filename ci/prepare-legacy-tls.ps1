param([Parameter(Mandatory = $true)][string]$BuildDirectory)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

if (!(Get-Command cl.exe -ErrorAction SilentlyContinue)) { throw 'Run inside the x64 MSVC build environment' }
New-Item -ItemType Directory -Force $BuildDirectory | Out-Null
$BuildDirectory = (Resolve-Path -LiteralPath $BuildDirectory).Path

function Get-VerifiedArchive([string]$Url, [string]$File, [string]$Sha256) {
    if (!(Test-Path -LiteralPath $File)) { Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $File }
    if ((Get-FileHash -LiteralPath $File -Algorithm SHA256).Hash.ToLowerInvariant() -ne $Sha256) {
        throw "Dependency checksum mismatch: $File"
    }
}

# Qt 5.15.2 requires the 1.1 ABI; OpenSSL 3 is still used by the stream engine.
# 1.1.1 is EOL. This runtime is exclusively for the existing Win7/8 legacy build.
$sourceArchive = Join-Path $BuildDirectory 'openssl-1.1.1w.tar.gz'
Get-VerifiedArchive `
    'https://github.com/openssl/openssl/releases/download/OpenSSL_1_1_1w/openssl-1.1.1w.tar.gz' `
    $sourceArchive 'cf3098950cb4d853ad95c0841f1f9c6d3dc102dccfcacd521d93925208b76ac8'

$perlArchive = Join-Path $BuildDirectory 'strawberry-perl-5.42.3.1-64bit-portable.zip'
Get-VerifiedArchive `
    'https://github.com/StrawberryPerl/Perl-Dist-Strawberry/releases/download/SP_54231_64bit/strawberry-perl-5.42.3.1-64bit-portable.zip' `
    $perlArchive '6a081a811781c30aca51dbc036afd93092af91e3297901f02c17043795a10690'
$perlDirectory = Join-Path $BuildDirectory 'perl'
& 7z.exe x -y "-o$perlDirectory" $perlArchive 'perl\*' 'c\bin\*.dll' > "$BuildDirectory\perl-extract.log"
if ($LASTEXITCODE -ne 0) { throw 'Portable build-time Perl extraction failed' }
$perl = Join-Path $perlDirectory 'perl\bin\perl.exe'
if (!(Test-Path -LiteralPath $perl)) { throw 'Native Perl is missing from its verified archive' }

$source = Join-Path $BuildDirectory 'openssl-1.1.1w'
if (!(Test-Path -LiteralPath $source)) {
    & tar.exe -xzf $sourceArchive -C $BuildDirectory
    if ($LASTEXITCODE -ne 0) { throw 'OpenSSL source extraction failed' }
}
$runtime = Join-Path $BuildDirectory 'runtime'
New-Item -ItemType Directory -Force $runtime | Out-Null
$originalPath = $env:PATH
$env:PATH = "$perlDirectory\perl\bin;$perlDirectory\c\bin;$env:PATH"
Push-Location $source
try {
    & cmd.exe /d /c "call `"$perl`" Configure VC-WIN64A shared no-asm no-tests --prefix=`"$runtime`" --openssldir=`"$runtime\ssl`" 2>&1" > "$BuildDirectory\configure.log"
    if ($LASTEXITCODE -ne 0) { Get-Content "$BuildDirectory\configure.log" -Tail 30; throw 'Legacy TLS configure failed' }
    & cmd.exe /d /c 'call nmake /NOLOGO build_libs 2>&1' > "$BuildDirectory\build.log"
    if ($LASTEXITCODE -ne 0) { Get-Content "$BuildDirectory\build.log" -Tail 30; throw 'Legacy TLS build failed' }
    Copy-Item 'libcrypto-1_1-x64.dll', 'libssl-1_1-x64.dll' -Destination $runtime -Force
    Copy-Item 'LICENSE' -Destination "$runtime\OpenSSL-1.1.1-LICENSE.txt" -Force
} finally {
    Pop-Location
    $env:PATH = $originalPath
}
$env:SPACE_CONNECT_LEGACY_TLS_DIR = $runtime
Get-ChildItem -LiteralPath $runtime -Filter '*.dll' | Get-FileHash -Algorithm SHA256 | Format-List
