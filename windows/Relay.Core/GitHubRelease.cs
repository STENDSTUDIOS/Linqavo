using System.Text.Json;
using System.Text.RegularExpressions;

namespace Linqavo.Core;

public sealed record GitHubRelease(string Version, Uri Download)
{
    public const string Endpoint = "https://api.github.com/repos/STENDSTUDIOS/Linqavo/releases/latest";
    private const string Releases = "https://github.com/STENDSTUDIOS/Linqavo/releases";
    private static readonly HttpClient Client = new(new HttpClientHandler { AllowAutoRedirect = false })
    {
        Timeout = TimeSpan.FromSeconds(15),
        MaxResponseContentBufferSize = 256 * 1024,
    };

    public static async Task<GitHubRelease?> LatestAsync(string installed)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, Endpoint);
        request.Headers.UserAgent.ParseAdd("Linqavo");
        request.Headers.Accept.ParseAdd("application/vnd.github+json");
        using var response = await Client.SendAsync(request).ConfigureAwait(false);
        if (response.StatusCode != System.Net.HttpStatusCode.OK) throw new HttpRequestException("Release unavailable");
        return Find(await response.Content.ReadAsStringAsync().ConfigureAwait(false), installed,
            System.Runtime.InteropServices.RuntimeInformation.ProcessArchitecture == System.Runtime.InteropServices.Architecture.X64);
    }

    public static GitHubRelease? Find(string json, string installed, bool x64)
    {
        using var document = JsonDocument.Parse(json);
        var release = document.RootElement;
        var tag = release.GetProperty("tag_name").GetString() ?? "";
        if (release.GetProperty("draft").GetBoolean() || release.GetProperty("prerelease").GetBoolean() ||
            !Regex.IsMatch(tag, @"\Av(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\z") ||
            release.GetProperty("html_url").GetString() != $"{Releases}/tag/{tag}")
            throw new InvalidDataException("Invalid stable release");
        var latest = UpdateCheck.Parse(tag) ?? throw new InvalidDataException("Invalid version");
        var current = UpdateCheck.Parse(installed) ?? throw new InvalidDataException("Unknown installed version");
        if (UpdateCheck.Compare(latest, current) <= 0) return null;
        var version = tag[1..];
        if (!x64) throw new InvalidDataException("Unsupported architecture");
        foreach (var name in new[] { $"Linqavo-Setup-x64-{version}.exe", "Linqavo-Setup-x64.exe" })
        {
            var url = $"{Releases}/download/{tag}/{name}";
            if (release.GetProperty("assets").EnumerateArray().Any(asset =>
                asset.GetProperty("name").GetString() == name && asset.GetProperty("state").GetString() == "uploaded" &&
                asset.GetProperty("browser_download_url").GetString() == url))
                return new GitHubRelease(version, new Uri(url));
        }
        throw new InvalidDataException("Compatible installer unavailable");
    }
}
