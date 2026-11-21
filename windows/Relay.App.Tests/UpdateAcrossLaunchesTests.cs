using Linqavo.Core;
using Xunit;
namespace Linqavo.App.Tests;
public class UpdateAcrossLaunchesTests {
    [Fact] public void StartupAndShutdownHaveNoAutomaticUpdateHooks() {
        var source=File.ReadAllText(Path.Combine(SharedContracts.RepoRoot,"windows/Relay.App/App.xaml.cs"));
        Assert.DoesNotContain("new UpdateService",source);
        Assert.DoesNotContain("InstallPendingAsync",source);
        Assert.Contains("ShowUpdateSettings",source);
    }
}
