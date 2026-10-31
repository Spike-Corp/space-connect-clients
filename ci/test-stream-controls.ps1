param([string]$SourceDirectory = (Get-Location).Path)
$ErrorActionPreference = 'Stop'
$desktop = (Get-Location).Path
$jom = Join-Path $SourceDirectory 'scripts\jom.exe'
$env:PATH = "$SourceDirectory\libs\windows\lib\x64;$desktop\AntiHooking\release;$env:PATH"
$env:QT_QPA_PLATFORM = 'offscreen'
$env:QT_QUICK_BACKEND = 'software'
$env:QT_QUICK_CONTROLS_STYLE = 'Material'
$env:QT_QPA_FONTDIR = "$env:WINDIR\Fonts"
foreach ($suite in @('stream_guests', 'stream_menu', 'session_orchestrator')) {
    $build = Join-Path $desktop "$suite-tests"
    New-Item -ItemType Directory -Force -Path $build | Out-Null
    Push-Location $build
    try {
        & cmd.exe /d /c "call qmake `"$SourceDirectory/app/tests/${suite}_test.pro`" CONFIG+=release 2>&1"
        if ($LASTEXITCODE -ne 0) { throw "$suite qmake failed" }
        & cmd.exe /d /c "call `"$jom`" release 2>&1"
        if ($LASTEXITCODE -ne 0) { throw "$suite build failed" }
        & cmd.exe /d /c ".\release\${suite}_test.exe 2>&1"
        if ($LASTEXITCODE -ne 0) { throw "$suite tests failed" }
    }
    finally { Pop-Location }
}
& cmd.exe /d /c "call qmltestrunner -input `"$SourceDirectory\app\tests\qml`" -import `"$SourceDirectory\app\tests\qml`" 2>&1"
if ($LASTEXITCODE -ne 0) { throw 'QML tests failed' }
