using System.Security.Cryptography;
namespace Linqavo.Core;

/// <summary>Legacy entry points fail closed. No channel or publisher has been provisioned.</summary>
public sealed class UpdateInstaller
{
    public UpdateInstaller(HttpClient? http = null) { }
    public enum Outcome { Started, Unavailable, ChecksumMismatch, Unverifiable }
    public Task<Outcome> InstallAsync(UpdateCheck.Available update, string? downloadDirectory = null,
        Action<string>? run = null, CancellationToken token = default) => Task.FromResult(Outcome.Unverifiable);
    public Task<(Outcome Outcome, string? Path, string? Sha256)> DownloadAsync(UpdateCheck.Available update,
        string? downloadDirectory = null, CancellationToken token = default) =>
        Task.FromResult<(Outcome, string?, string?)>((Outcome.Unverifiable, null, null));
    public Outcome Run(string path, Action<string>? run = null, bool relaunch = true) => Outcome.Unverifiable;

    public static async Task<string> HashFileAsync(string path, CancellationToken token = default)
    {
        using var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        await using var file = new FileStream(
            path, FileMode.Open, FileAccess.Read, FileShare.Read,
            bufferSize: 128 * 1024, useAsync: true);

        var buffer = new byte[128 * 1024];
        int read;
        while ((read = await file.ReadAsync(buffer, token).ConfigureAwait(false)) > 0)
        {
            hash.AppendData(buffer, 0, read);
        }
        return Convert.ToHexString(hash.GetHashAndReset()).ToLowerInvariant();
    }

    /// <summary>
    /// Constant-time comparison of two hex hashes, false for anything that is
    /// not a pair of well-formed hex strings of the same length.
    /// </summary>
    public static bool Matches(string? actual, string? expected)
    {
        if (actual is null || expected is null) return false;
        if (actual.Length != expected.Length || actual.Length == 0) return false;
        try
        {
            return CryptographicOperations.FixedTimeEquals(
                Convert.FromHexString(actual), Convert.FromHexString(expected));
        }
        catch (FormatException)
        {
            return false;
        }
    }


    public static string InstallDirectory => AppContext.BaseDirectory.TrimEnd(Path.DirectorySeparatorChar);
    public static string ArgumentsFor(string directory, bool relaunch) => string.Empty;
    public const string Relaunch = "/relaunch=1";
    public static string? HashFor(string checksums, string fileName)
    {
        foreach (var line in checksums.Split('\n'))
        {
            var trimmed = line.Trim();
            if (trimmed.Length < 66) continue;

            var hash = trimmed[..64];
            if (!IsHex(hash)) continue;

            var rest = trimmed[64..].TrimStart(' ', '*', '\t');
            if (Path.GetFileName(rest).Equals(fileName, StringComparison.OrdinalIgnoreCase))
            {
                return hash.ToLowerInvariant();
            }
        }
        return null;
    }

    private static bool IsHex(ReadOnlySpan<char> text)
    {
        foreach (var c in text)
        {
            var hex = c is >= '0' and <= '9' or >= 'a' and <= 'f' or >= 'A' and <= 'F';
            if (!hex) return false;
        }
        return true;
    }

    /// <summary>
    /// The last path segment of a download URL, with any query dropped. Refuses
    /// anything with a separator in it so a hostile URL cannot steer the write
    /// out of the download directory.
    /// </summary>
    public static string FileName(string url)
    {
        var text = url.Split('?')[0].Split('#')[0];
        var slash = text.LastIndexOf('/');
        var name = slash >= 0 ? text[(slash + 1)..] : text;
        return name.Contains('\\') || name.Contains("..") ? string.Empty : name;
    }
}
