namespace Linqavo.Core;
public enum UpdateNotice { Available, Installing, Refused }
public enum UpdateCycle { Checked, Failed }

/// <summary>Compatibility facade: lifecycle events never check, download or run updates.</summary>
public sealed class UpdateService
{
    public UpdateService(string currentVersion, Func<string> currentState, Action<UpdateNotice,string> notify,
        Func<UpdateCheck>? checkFactory = null, UpdateInstaller? installer = null, TimeSpan? idleWait = null,
        string? downloadDirectory = null, Action<string>? runInstaller = null, Action<string>? log = null) { }
    public void Start() { }
    public void Stop() { }
    public bool Nudge() => false;
    public Task<UpdateCycle> CheckAndMaybeInstallAsync(CancellationToken token = default) => Task.FromResult(UpdateCycle.Checked);
    public Task<bool> InstallPendingAsync(bool relaunch, CancellationToken token = default) => Task.FromResult(false);
}
