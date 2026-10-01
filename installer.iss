; Inno Setup script: turns dist\KlineLogger into a one-file installer.
; Build with build_windows.bat, or open this file in Inno Setup 6 and press Compile.

#define AppName "K-line Logger"
#define AppVersion "1.0.0"
#define AppPublisher "Rowland Restorations"
#define AppExe "KlineLogger.exe"

[Setup]
AppId={{525830C1-E2FC-417D-A583-D60922CAD27C}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion}
AppPublisher={#AppPublisher}
DefaultDirName={autopf}\{#AppName}
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
; Per-user install: no admin prompt, installs to %LOCALAPPDATA%\Programs
PrivilegesRequired=lowest
OutputDir=installer
OutputBaseFilename=KlineLogger-Setup-{#AppVersion}
SetupIconFile=assets\icon.ico
UninstallDisplayIcon={app}\{#AppExe}
UninstallDisplayName={#AppName}
Compression=lzma2
SolidCompression=yes
WizardStyle=modern

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[Files]
Source: "dist\KlineLogger\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\{#AppExe}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExe}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#AppExe}"; Description: "{cm:LaunchProgram,{#AppName}}"; Flags: nowait postinstall skipifsilent
