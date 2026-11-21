; Relay Windows installer (Inno Setup 6). Built headlessly in CI:
;   iscc /DAppVersion=x.y.z /DAppPlatform=x64 /DSourceDir=..\publish-x64 relay.iss
; Unsigned by design for now — SmartScreen trade-off documented in docs/release.md.

#ifndef AppVersion
  #error AppVersion must be supplied from shared/LinqavoVersion.props
#endif
#ifndef AppPlatform
  #define AppPlatform "x64"
#endif
#ifndef SourceDir
  #define SourceDir "publish"
#endif
; Numeric PE versions come from the compiled app, never a prerelease string.
#define ForkNumericVersion GetFileVersion(SourceDir + "\Linqavo.exe")
#if GetStringFileInfo(SourceDir + "\Linqavo.exe", "ProductVersion") != AppVersion
  #error Installer version must match the compiled fork application
#endif
#if !FileExists(SourceDir + "\linqavo-wg.exe") || !FileExists(SourceDir + "\wintun.dll") || !FileExists(SourceDir + "\resources.pri")
  #error Complete fork app, native helper, Wintun and resources.pri are required
#endif

[Setup]
AppId={{C1B8B487-84FD-4C72-9CC8-91B27D723D87}
AppName=Linqavo
AppVersion={#AppVersion}
VersionInfoVersion={#ForkNumericVersion}
VersionInfoProductVersion={#ForkNumericVersion}
VersionInfoProductTextVersion={#AppVersion}
AppPublisher=STEND STUDIOS
AppPublisherURL=https://github.com/STENDSTUDIOS/Linqavo
AppUpdatesURL=https://github.com/STENDSTUDIOS/Linqavo/releases
DefaultDirName={autopf}\Linqavo
DisableProgramGroupPage=yes
; Per-user install: no admin prompt, and the app only ever touches HKCU anyway.
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
OutputBaseFilename=Linqavo-Setup-{#AppPlatform}-{#AppVersion}
; Tell Setup a session may be running, so an upgrade asks to close Relay
; (and lets Restart Manager try) instead of overwriting files underneath a
; live proxy session. Matches the mutex App.xaml.cs takes at startup.
AppMutex=Local\RelayForkAppSingleton
OutputDir=output
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
; The installer's own icon, and the one Apps & Features shows. Without these
; both fall back to Inno's default, which is the first and last impression a
; person gets of whether this is finished software.
SetupIconFile={#SourceDir}\Assets\Linqavo.ico
UninstallDisplayIcon={app}\Linqavo.exe
#if AppPlatform == "x64"
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
#endif

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked
; A tray app is worth having at login: it is the thing you want already running
; when a laptop turns out to have no internet. Off by default -- deciding that
; for someone is exactly the behaviour that makes people distrust installers --
; and the same switch lives in Advanced, reading the same registry value.
Name: "startup"; Description: "Start Linqavo when I sign in"; GroupDescription: "Additional options"; Flags: unchecked

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Excludes: "*.pdb,BUILD-INFO.json"; Flags: ignoreversion recursesubdirs createallsubdirs

; Replace obsolete fork shortcuts on an in-place upgrade. The original Relay
; installation has its own GUID and different shortcut names.
[InstallDelete]
Type: files; Name: "{autoprograms}\Relay Fork.lnk"
Type: files; Name: "{autodesktop}\Relay Fork.lnk"
Type: files; Name: "{app}\relayfork-wg-client.exe"
Type: files; Name: "{app}\Relay.Core.dll"
Type: files; Name: "{app}\RelayFork.App.exe"
Type: files; Name: "{app}\RelayFork.App.dll"
Type: files; Name: "{app}\RelayFork.App.deps.json"
Type: files; Name: "{app}\RelayFork.App.runtimeconfig.json"

[Icons]
; IconFilename is explicit rather than inherited from the exe: Windows caches
; shortcut icons aggressively, and an upgrade that changes the exe's icon does
; not always repaint a shortcut that only points at the exe.
Name: "{autoprograms}\Linqavo"; Filename: "{app}\Linqavo.exe"; IconFilename: "{app}\Assets\Linqavo.ico"; Comment: "Share your phone's internet with this PC"
Name: "{autodesktop}\Linqavo"; Filename: "{app}\Linqavo.exe"; IconFilename: "{app}\Assets\Linqavo.ico"; Tasks: desktopicon

[Registry]
; The same value StartupRegistration.cs reads and writes, including the --tray
; argument, so the installer checkbox and the switch in Advanced are one setting
; rather than two that can disagree. uninsdeletevalue so uninstalling does not
; leave Windows trying to start something that is gone.
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "Linqavo"; ValueData: """{app}\Linqavo.exe"" --tray"; Flags: uninsdeletevalue; Tasks: startup; Check: CreateStartupEntry
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "RelayFork"; ValueData: """{app}\Linqavo.exe"" --tray"; Flags: uninsdeletevalue; Check: LegacyRunEntry('RelayFork', '--tray')
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "RelayForkProxyRecovery"; ValueData: """{app}\Linqavo.exe"" --restore-proxy"; Check: LegacyRunEntry('RelayForkProxyRecovery', '--restore-proxy')

[Run]
Filename: "{app}\Linqavo.exe"; Description: "{cm:LaunchProgram,Linqavo}"; Flags: nowait postinstall skipifsilent unchecked

[UninstallRun]
; If a session is active at uninstall time, stop the app and restore the system
; proxy so the user isn't left with a dangling SOCKS proxy and no app to undo it.
Filename: "{sys}\taskkill.exe"; Parameters: "/f /im Linqavo.exe"; Flags: runhidden; RunOnceId: "StopRelay"
Filename: "{app}\Linqavo.exe"; Parameters: "--restore-proxy"; Flags: runhidden waituntilterminated skipifdoesntexist; RunOnceId: "RestoreProxy"

[UninstallDelete]
; The startup log is disposable, so it goes.
;
; proxy-backup.json deliberately does NOT. If the --restore-proxy step above
; failed for any reason, that file is the only surviving record of the user's
; original proxy/PAC settings — deleting it would turn a recoverable problem
; ("reinstall Relay and it repairs itself") into a permanently broken machine
; with no way back except editing Internet Options by hand. A few hundred bytes
; left behind is the cheaper mistake. Relay deletes it itself the moment a
; rollback succeeds.
Type: files; Name: "{localappdata}\Linqavo\startup-error.log"
Type: files; Name: "{localappdata}\RelayFork\startup-error.log"

[Code]
function CreateStartupEntry(): Boolean;
begin
  Result := not RegValueExists(HKCU, 'Software\Microsoft\Windows\CurrentVersion\Run', 'RelayFork');
end;

function LegacyRunEntry(ValueName, Argument: String): Boolean;
var
  Existing: String;
begin
  Result := RegQueryStringValue(HKCU, 'Software\Microsoft\Windows\CurrentVersion\Run', ValueName, Existing)
    and (CompareText(Existing, ExpandConstant('"{app}\RelayFork.App.exe" ') + Argument) = 0);
end;
