using System.Xml.Linq;
using Xunit;

namespace Linqavo.App.Tests;

public class WindowMarkupTests
{
    [Fact]
    public void WindowPathsDoNotAttachSharedGeometry()
    {
        var app = Path.Combine(SharedContracts.RepoRoot, "windows", "Relay.App");
        XNamespace xaml = "http://schemas.microsoft.com/winfx/2006/xaml";
        var dictionary = XDocument.Load(Path.Combine(app, "Styles", "Tokens.xaml"));
        var sharedGeometry = dictionary.Descendants()
            .Where(e => e.Name.LocalName.EndsWith("Geometry", StringComparison.Ordinal))
            .Select(e => (string?)e.Attribute(xaml + "Key"))
            .Where(key => key != null)
            .Select(key => "{StaticResource " + key + "}")
            .ToHashSet();

        foreach (var file in new[] { "MainWindow.xaml", "StatusWindow.xaml" })
        {
            var window = XDocument.Load(Path.Combine(app, file));
            foreach (var path in window.Descendants().Where(e => e.Name.LocalName == "Path"))
                Assert.DoesNotContain((string?)path.Attribute("Data") ?? "", sharedGeometry);
        }
    }
}
