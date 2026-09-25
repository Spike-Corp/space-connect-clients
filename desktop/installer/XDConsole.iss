; XD Console - instalador Windows (Inno Setup)
;
; App da EQUIPE SpaceCloud (suporte/ops): mesmo binario-base do Space Connect,
; build xdconsole (TARGET=XDConsole). AppId proprio — NUNCA remove nem toca a
; instalacao do Space Connect do usuario (sem RemoveLegacyInstalls aqui).
;
; Compilar:
;   iscc /DMyAppVersion=0.3.11 XDConsole.iss

#ifndef MyAppVersion
  #define MyAppVersion "0.0.0"
#endif

#define MyAppName "XD Console"
#define MyAppFullName "XD Console - SpaceCloud Staff"
#define MyAppPublisher "SpaceCloud"
#define MyAppURL "https://spacecloud.gg"
#define MyAppExeName "XDConsole.exe"

#define DeployDir AddBackslash(SourcePath) + "..\deploy-xd"
#define IconFile AddBackslash(SourcePath) + "..\app\moonlight.ico"
#define VcRedistExe AddBackslash(SourcePath) + "..\vcredist\VC_redist.x64.exe"

[Setup]
AppId={{9D4C2B18-3E7A-4F52-B6D1-8A0E5C9F2B63}
AppName={#MyAppFullName}
AppVersion={#MyAppVersion}
AppVerName={#MyAppName} {#MyAppVersion}
VersionInfoVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppPublisherURL={#MyAppURL}
AppSupportURL={#MyAppURL}
AppUpdatesURL={#MyAppURL}
DefaultDirName={autopf}\{#MyAppName}
DefaultGroupName={#MyAppName}
UninstallDisplayName={#MyAppFullName}
UninstallDisplayIcon={app}\{#MyAppExeName}
OutputDir=..
OutputBaseFilename=XDConsole-Setup-{#MyAppVersion}-windows-x64
SetupIconFile={#IconFile}
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=admin
MinVersion=10.0
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
DisableDirPage=no
DisableProgramGroupPage=yes
DisableWelcomePage=no
CloseApplications=yes
RestartApplications=no
SetupLogging=yes

[Languages]
Name: "brazilianportuguese"; MessagesFile: "compiler:Languages\BrazilianPortuguese.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"

[CustomMessages]
brazilianportuguese.CreateDesktopIcon=Criar um atalho na &Area de Trabalho
brazilianportuguese.InstallingVcRedist=Instalando o Microsoft Visual C++ Runtime...
english.CreateDesktopIcon=Create a &desktop shortcut
english.InstallingVcRedist=Installing the Microsoft Visual C++ Runtime...

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[Files]
Source: "{#DeployDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "{#VcRedistExe}"; DestDir: "{tmp}"; Flags: deleteafterinstall; Check: VcRedistNeeded

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; WorkingDir: "{app}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; WorkingDir: "{app}"; Tasks: desktopicon

[Run]
Filename: "{tmp}\VC_redist.x64.exe"; Parameters: "/install /quiet /norestart"; StatusMsg: "{cm:InstallingVcRedist}"; Flags: waituntilterminated; Check: VcRedistNeeded
Filename: "{app}\{#MyAppExeName}"; Description: "{cm:LaunchProgram,{#MyAppName}}"; WorkingDir: "{app}"; Flags: nowait postinstall skipifsilent

[UninstallDelete]
Type: filesandordirs; Name: "{localappdata}\{#MyAppName}"

[Code]
{ O VC++ 2015-2022 x64 registra Installed/Major/Minor aqui. As DLLs do CRT ja
  vao dentro do pacote, entao o redist so roda quando realmente falta. }
function VcRedistNeeded(): Boolean;
var
  Installed, Major, Minor: Cardinal;
begin
  Result := True;
  if RegQueryDWordValue(HKLM64, 'SOFTWARE\Microsoft\VisualStudio\14.0\VC\Runtimes\x64', 'Installed', Installed) then
    if Installed = 1 then
      if RegQueryDWordValue(HKLM64, 'SOFTWARE\Microsoft\VisualStudio\14.0\VC\Runtimes\x64', 'Major', Major) then
        if RegQueryDWordValue(HKLM64, 'SOFTWARE\Microsoft\VisualStudio\14.0\VC\Runtimes\x64', 'Minor', Minor) then
          if (Major > 14) or ((Major = 14) and (Minor >= 40)) then
            Result := False;
end;
