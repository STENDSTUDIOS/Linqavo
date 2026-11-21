using Linqavo.Core;
using Xunit;

namespace Linqavo.App.Tests;

public class GitHubReleaseTests
{
    private const string Project = "https://github.com/STENDSTUDIOS/Linqavo/releases";
    private const string Name = "Linqavo-Setup-x64-1.10.0.exe";
    private const string Url = Project + "/download/v1.10.0/" + Name;
    private static readonly string Release = System.Text.Json.JsonSerializer.Serialize(new {
        tag_name = "v1.10.0", html_url = Project + "/tag/v1.10.0", draft = false, prerelease = false,
        assets = new[] { new { name = Name, state = "uploaded", browser_download_url = Url } }
    });

    [Fact] public void OffersOnlyANewerWindowsSetup()
    {
        Assert.Equal(Url, GitHubRelease.Find(Release, "1.9.0", true)?.Download.AbsoluteUri);
        Assert.Null(GitHubRelease.Find(Release, "1.10.0", true));
        Assert.Null(GitHubRelease.Find(Release, "2.0.0", true));
        Assert.Throws<InvalidDataException>(() => GitHubRelease.Find(Release, "1.0.0", false));
    }

    [Theory]
    [InlineData(Url, "https://example.org/setup.exe")]
    [InlineData("STENDSTUDIOS", "another-owner")]
    [InlineData("false", "true")]
    [InlineData("uploaded", "new")]
    [InlineData(Name, "Linqavo-1.10.0-windows-x64.zip")]
    public void RejectsIncompatibleRelease(string from, string to) =>
        Assert.Throws<InvalidDataException>(() => GitHubRelease.Find(Release.Replace(from, to), "1.0.0", true));
}
