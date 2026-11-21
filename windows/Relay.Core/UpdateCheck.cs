namespace Linqavo.Core;

/// <summary>Manual update availability is unavailable until a trusted fork channel exists.</summary>
public sealed class UpdateCheck
{
    public const string Status = "NoTrustedForkSource";
    public sealed record Available(string Version, string Url, string? Notes, string? ChecksumsUrl = null);
    public UpdateCheck(string currentVersion, HttpClient? http = null) { }
    public Task<Available?> CheckAsync(CancellationToken token = default) => Task.FromResult<Available?>(null);

    public static int[]? Parse(string? version)
    {
        if (string.IsNullOrWhiteSpace(version)) return null;
        var text = version.Trim().TrimStart('v', 'V');
        var fork=System.Text.RegularExpressions.Regex.Match(text,@"^([0-9]+)\.([0-9]+)\.([0-9]+)-fork\.([1-9][0-9]{0,2})$");
        if(fork.Success) {
            var result=new int[4];for(var i=0;i<4;i++)if(!int.TryParse(fork.Groups[i+1].Value,out result[i]))return null;
            return result;
        }
        if(text.Contains("-fork",StringComparison.Ordinal))return null;
        // Drop any pre-release suffix: 1.4.0-rc1 compares as 1.4.0.
        var dash = text.IndexOf('-');
        if (dash >= 0) text = text[..dash];
        var parts = text.Split('.');
        if (parts.Length is < 2 or > 4) return null;
        var numbers = new int[parts.Length];
        for (var i = 0; i < parts.Length; i++)
        {
            if (!int.TryParse(parts[i], out numbers[i]) || numbers[i] < 0) return null;
        }
        return numbers;
    }

    /// <summary>Positive when <paramref name="a"/> is newer.</summary>
    public static int Compare(int[] a, int[] b)
    {
        for (var i = 0; i < Math.Max(a.Length, b.Length); i++)
        {
            var left = i < a.Length ? a[i] : 0;
            var right = i < b.Length ? b[i] : 0;
            if (left != right) return left.CompareTo(right);
        }
        return 0;
    }

}
