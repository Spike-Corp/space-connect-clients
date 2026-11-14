@echo off
rem ============================================================================
rem  Space Connect - build local do beta Windows (sem GitHub Actions)
rem  Use quando o Actions estiver bloqueado (billing) e voce precisar testar um
rem  build na propria maquina self-hosted. Reproduz os passos do
rem  .github/workflows/build-desktop.yml (job Windows x64).
rem
rem  Pre-requisitos ja instalados nesta maquina:
rem    - Visual Studio 2022 / Build Tools (MSVC x64, toolset VC143)
rem    - Qt 6.7.3 msvc2019_64  (qmake, windeployqt, lrelease no PATH ou em QTDIR)
rem    - scripts\jom.exe (ja vem no repo)
rem
rem  Overrides opcionais (variaveis de ambiente):
rem    QTDIR  = caminho do Qt (ex: C:\Qt\6.7.3\msvc2019_64)
rem    VSDIR  = caminho da instalacao do VS (contendo VC\Auxiliary\Build\vcvars64.bat)
rem
rem  Resultado: desktop\deploy\  (pasta portatil, SpaceConnect.exe + todas as DLLs,
rem             incluindo VCRUNTIME) e um .zip na raiz do repo.
rem ============================================================================
setlocal EnableExtensions EnableDelayedExpansion

rem --- ir para a pasta desktop\ (este script esta em desktop\scripts\) ---
cd /d "%~dp0.." || exit /b 1
echo [*] Pasta de build: %CD%

rem --- 1) Ambiente MSVC (vcvars64) ---
where cl >nul 2>nul
if %ERRORLEVEL%==0 (
  echo [*] MSVC ja esta no ambiente.
) else (
  set "_VCVARS="
  if defined VSDIR if exist "%VSDIR%\VC\Auxiliary\Build\vcvars64.bat" set "_VCVARS=%VSDIR%\VC\Auxiliary\Build\vcvars64.bat"
  if not defined _VCVARS (
    set "_VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
    if exist "!_VSWHERE!" (
      for /f "usebackq delims=" %%I in (`"!_VSWHERE!" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set "_VSINSTALL=%%I"
      if defined _VSINSTALL if exist "!_VSINSTALL!\VC\Auxiliary\Build\vcvars64.bat" set "_VCVARS=!_VSINSTALL!\VC\Auxiliary\Build\vcvars64.bat"
    )
  )
  if not defined _VCVARS (
    echo [ERRO] vcvars64.bat nao encontrado. Instale o VS 2022 Build Tools ^(MSVC x64^) ou defina VSDIR.
    exit /b 1
  )
  echo [*] Carregando MSVC: !_VCVARS!
  call "!_VCVARS!" || exit /b 1
)

rem --- 2) Garantir Qt (qmake) no PATH ---
where qmake >nul 2>nul
if not %ERRORLEVEL%==0 (
  if defined QTDIR if exist "%QTDIR%\bin\qmake.exe" set "PATH=%QTDIR%\bin;%PATH%"
)
where qmake >nul 2>nul
if not %ERRORLEVEL%==0 (
  if exist "C:\Qt\6.7.3\msvc2019_64\bin\qmake.exe" set "PATH=C:\Qt\6.7.3\msvc2019_64\bin;%PATH%"
)
where qmake >nul 2>nul
if not %ERRORLEVEL%==0 (
  echo [ERRO] qmake nao encontrado. Instale o Qt 6.7.3 msvc2019_64 e defina QTDIR, ou adicione o bin do Qt ao PATH.
  echo        Rapido: pip install aqtinstall ^&^& aqt install-qt windows desktop 6.7.3 win64_msvc2019_64 -m qtimageformats
  exit /b 1
)
for /f "delims=" %%Q in ('where qmake') do echo [*] qmake: %%Q

rem --- 3) Traducoes + build Release x64 ---
echo [*] lrelease...
lrelease app\languages\qml_pt_BR.ts app\languages\qml_pt.ts || exit /b 1
echo [*] qmake...
qmake moonlight-qt.pro CONFIG+=release || exit /b 1
echo [*] jom (compilando, pode demorar)...
scripts\jom.exe release || exit /b 1
if not exist "app\release\SpaceConnect.exe" (
  echo [ERRO] build nao gerou app\release\SpaceConnect.exe
  exit /b 1
)

rem --- 4) Deploy (binario + DLLs + windeployqt) ---
echo [*] Montando deploy\ ...
if exist deploy rmdir /s /q deploy
mkdir deploy || exit /b 1
copy /Y app\release\SpaceConnect.exe deploy\ || exit /b 1
copy /Y libs\windows\lib\x64\*.dll deploy\ || exit /b 1
copy /Y AntiHooking\release\AntiHooking.dll deploy\ || exit /b 1
copy /Y libs\windows\lib\x64\discord-rpc.dll deploy\ >nul 2>nul
copy /Y app\SDL_GameControllerDB\gamecontrollerdb.txt deploy\ || exit /b 1
windeployqt --release --qmldir app\gui --no-opengl-sw --no-sql --no-system-d3d-compiler --no-system-dxc-compiler --skip-plugin-types qmltooling,generic --no-ffmpeg --no-quickcontrols2fusion --no-quickcontrols2imagine --no-quickcontrols2universal deploy\SpaceConnect.exe || exit /b 1

rem --- 5) Bundle das DLLs do VC++ CRT (corrige o erro VCRUNTIME140_1.dll) ---
echo [*] Copiando VC++ CRT (VC143)...
set "_CRT="
for /f "delims=" %%D in ('dir /b /ad /o-n "C:\Program Files (x86)\Microsoft Visual Studio\2022\*" 2^>nul') do (
  for /f "delims=" %%E in ('dir /b /ad /o-n "C:\Program Files (x86)\Microsoft Visual Studio\2022\%%D\VC\Redist\MSVC\*" 2^>nul') do (
    if not defined _CRT if exist "C:\Program Files (x86)\Microsoft Visual Studio\2022\%%D\VC\Redist\MSVC\%%E\x64\Microsoft.VC143.CRT" set "_CRT=C:\Program Files (x86)\Microsoft Visual Studio\2022\%%D\VC\Redist\MSVC\%%E\x64\Microsoft.VC143.CRT"
  )
)
if not defined _CRT (
  echo [AVISO] Pasta Microsoft.VC143.CRT x64 nao encontrada. O build pode pedir VCRUNTIME no PC do cliente.
  echo         Alternativa: instalar o VC_redist.x64.exe no PC de teste ^(https://aka.ms/vs/17/release/vc_redist.x64.exe^).
) else (
  echo [*] CRT: !_CRT!
  copy /Y "!_CRT!\*.dll" deploy\ >nul || exit /b 1
)

rem --- 6) Zip portatil ---
set /p VERSION=<app\version.txt
set "VERSION=%VERSION: =%"
set "ZIP=..\SpaceConnect-%VERSION%-windows-x64-beta.zip"
echo [*] Empacotando %ZIP% ...
powershell -NoProfile -Command "Compress-Archive -Path 'deploy/*' -DestinationPath '%ZIP%' -Force" || exit /b 1

echo.
echo [OK] Build pronto.
echo      Pasta portatil : %CD%\deploy  (rode SpaceConnect.exe)
echo      Zip            : %CD%\%ZIP%
echo.
echo  Teste do crash: recrie/ligue a VM e clique em "abrir maquina".
endlocal
