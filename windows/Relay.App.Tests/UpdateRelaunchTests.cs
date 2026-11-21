using Xunit;
namespace Linqavo.App.Tests;
public class UpdateRelaunchTests {
    [Fact] public void UpdateTransportHasNoUpstreamOrProcessLaunchFallback() {
        foreach(var name in new[]{"UpdateCheck.cs","UpdateInstaller.cs","UpdateService.cs"}) {
            var source=File.ReadAllText(Path.Combine(SharedContracts.RepoRoot,"windows/Relay.Core",name));
            Assert.DoesNotContain("Mahdi-mortazavi",source);
            Assert.DoesNotContain("Process.Start",source);
            Assert.DoesNotContain("GetAsync(",source);
            Assert.DoesNotContain("GetStringAsync(",source);
        }
    }
}
