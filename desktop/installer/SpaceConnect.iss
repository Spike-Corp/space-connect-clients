; Space Connect - instalador Windows (Inno Setup)
;
; Substitui o bootstrapper WiX Burn (MoonlightSetup.exe). O Burn extrai um
; segundo executavel (wixstdba.exe) pro %TEMP% e o executa: como o binario nao
; e assinado, o SmartScreen/Defender matava esse processo filho sem nenhuma
; mensagem — o instalador simplesmente "sumia" ao clicar em "Executar assim
; mesmo". O Inno Setup desenha a propria UI dentro do processo que o usuario
; ja autorizou, entao nao existe esse segundo salto.
;
; Compilar:
;   iscc /DMyAppVersion=0.3.8 SpaceConnect.iss

#ifndef MyAppVersion
  #define MyAppVersion "0.0.0"
#endif

#define MyAppName "Space Connect"
#define MyAppFullName "Space Connect Streaming Client"
#define MyAppPublisher "Space Connect"
#define MyAppURL "https://spacecloud.gg"
#define MyAppExeName "SpaceConnect.exe"

#define DeployDir AddBackslash(SourcePath) + "..\deploy"
#define IconFile AddBackslash(SourcePath) + "..\app\moonlight.ico"
#define LicenseRtf AddBackslash(SourcePath) + "..\wix\MoonlightSetup\license.rtf"
#define VcRedistExe AddBackslash(SourcePath) + "..\vcredist\VC_redist.x64.exe"

[Setup]
; AppId novo e estavel: identifica a linha de instalacao Inno para sempre.
; Nao reaproveita o UpgradeCode do MSI/bundle de proposito — as instalacoes
; antigas sao removidas explicitamente em CurStepChanged/ssInstall.
AppId={{7F2A1C64-5B3E-4D89-9E1A-2C8F6B0D4A71}
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
OutputBaseFilename=SpaceConnect-Setup-{#MyAppVersion}-windows-x64
SetupIconFile={#IconFile}
LicenseFile={#LicenseRtf}
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=admin
; Windows 10 ou superior, 64 bits — mesmas condicoes que o bundle WiX exigia.
MinVersion=10.0
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
; A pagina de escolha de pasta e obrigatoria aqui: o instalador anterior nunca
; perguntava onde instalar.
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
brazilianportuguese.ConfiguringFirewall=Liberando o Space Connect no Firewall do Windows...
english.CreateDesktopIcon=Create a &desktop shortcut
english.InstallingVcRedist=Installing the Microsoft Visual C++ Runtime...
english.ConfiguringFirewall=Allowing Space Connect through Windows Firewall...

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
; Libera o app no firewall (o MSI fazia isso via WixFirewallExtension).
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""{#MyAppFullName}"" dir=in action=allow program=""{app}\{#MyAppExeName}"" enable=yes profile=any"; Flags: runhidden; StatusMsg: "{cm:ConfiguringFirewall}"
Filename: "{app}\{#MyAppExeName}"; Description: "{cm:LaunchProgram,{#MyAppName}}"; WorkingDir: "{app}"; Flags: nowait postinstall skipifsilent

[UninstallRun]
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""{#MyAppFullName}"""; Flags: runhidden; RunOnceId: "DelFwRule"

[UninstallDelete]
Type: filesandordirs; Name: "{localappdata}\{#MyAppName}"

[Code]
const
  UninstallKey = 'SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall';

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

function IsLegacySpaceConnect(const DisplayName: String): Boolean;
begin
  Result := (Pos('space connect', Lowercase(DisplayName)) > 0) or
            (Pos('moonlight game streaming', Lowercase(DisplayName)) > 0);
end;

{ Desinstala as versoes antigas entregues como .msi e como bundle WiX Burn.
  Sem isso o painel "Aplicativos" ficaria com duas entradas e os arquivos
  antigos continuariam em Program Files. }
procedure RemoveLegacyInstalls(RootKey: Integer);
var
  Names: TArrayOfString;
  I, ResultCode: Integer;
  DisplayName, QuietCmd: String;
  IsMsi: Cardinal;
begin
  if not RegGetSubkeyNames(RootKey, UninstallKey, Names) then
    exit;

  for I := 0 to GetArrayLength(Names) - 1 do
  begin
    { Entradas do proprio Inno terminam em _is1 e sao tratadas pelo AppId. }
    if Pos('_is1', Names[I]) > 0 then
      continue;
    if not RegQueryStringValue(RootKey, UninstallKey + '\' + Names[I], 'DisplayName', DisplayName) then
      continue;
    if not IsLegacySpaceConnect(DisplayName) then
      continue;

    IsMsi := 0;
    RegQueryDWordValue(RootKey, UninstallKey + '\' + Names[I], 'WindowsInstaller', IsMsi);

    if (IsMsi = 1) and (Copy(Names[I], 1, 1) = '{') then
      Exec(ExpandConstant('{sys}\msiexec.exe'), '/x ' + Names[I] + ' /qn /norestart',
           '', SW_HIDE, ewWaitUntilTerminated, ResultCode)
    else if RegQueryStringValue(RootKey, UninstallKey + '\' + Names[I], 'QuietUninstallString', QuietCmd) then
      Exec(ExpandConstant('{cmd}'), '/c "' + QuietCmd + '"', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  end;
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep = ssInstall then
  begin
    RemoveLegacyInstalls(HKLM64);
    RemoveLegacyInstalls(HKLM32);
  end;
end;
