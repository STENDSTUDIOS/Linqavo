using System.Text.Json;
using Linqavo.Core.Proxy;

namespace Linqavo.App.Services;

/// <summary>
/// Durable pre-connect snapshot at %LOCALAPPDATA%\Relay\proxy-backup.json —
/// what <see cref="ProxySession.RecoverIfCrashed"/> reads after a crash.
/// </summary>
public sealed class FileBackupStore : IBackupStore
{
    private static string Path
    {
        get
        {
            var appData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
            var legacy = System.IO.Path.Combine(appData, "RelayFork", "proxy-backup.json");
            return File.Exists(legacy) ? legacy : System.IO.Path.Combine(appData, "Linqavo", "proxy-backup.json");
        }
    }

    public ProxyBackup? Load()
    {
        try
        {
            return File.Exists(Path)
                ? JsonSerializer.Deserialize<ProxyBackup>(File.ReadAllText(Path))
                : null;
        }
        catch (Exception)
        {
            // An unreadable backup must never block startup; worst case the
            // user's proxy stays as-is and they fix it in Windows settings.
            return null;
        }
    }

    public void Save(ProxyBackup backup)
    {
        Directory.CreateDirectory(System.IO.Path.GetDirectoryName(Path)!);
        // Atomic write: a crash mid-write must not strand a truncated backup that
        // fails to parse, which would skip crash-recovery and leave a dead proxy.
        var tmp = Path + ".tmp";
        File.WriteAllText(tmp, JsonSerializer.Serialize(backup));
        File.Move(tmp, Path, overwrite: true);
    }

    public void Delete()
    {
        try
        {
            File.Delete(Path);
        }
        catch (IOException)
        {
            // Stale file is re-examined (and discarded) on next start.
        }
    }
}
