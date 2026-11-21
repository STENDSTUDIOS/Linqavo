using Linqavo.Core;
using Xunit;
namespace Linqavo.App.Tests;
public class UpdateServiceTests {
    private sealed class RejectNetwork : HttpMessageHandler {
        public int Calls;
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken token) {
            Calls++; throw new InvalidOperationException("No update request is authorized");
        }
    }
    [Theory]
    [InlineData("Idle")][InlineData("Connected")][InlineData("Reconnecting")]
    public async Task LifecycleAndManualLegacyEntryPointsNeverRequestOrExecute(string state) {
        var transport = new RejectNetwork(); using var http = new HttpClient(transport);
        int executions = 0, factories = 0;
        var dir = Path.Combine(Path.GetTempPath(), "relay-fork-test-"+Guid.NewGuid());
        try {
            Directory.CreateDirectory(dir);
            PendingUpdate.Save(dir,new PendingUpdate("99.0.0", "Relay-Setup-x64.exe", new string('a',64)));
            File.WriteAllText(Path.Combine(dir,"Relay-Setup-x64.exe"),"stale upstream installer");
            var service = new UpdateService("2.11.1-fork.1",()=>state,(_,_)=>executions++,
                ()=>{factories++;return new UpdateCheck("0.0.0",http);},new UpdateInstaller(http),
                downloadDirectory:dir,runInstaller:_=>executions++);
            service.Start(); Assert.False(service.Nudge());
            await service.CheckAndMaybeInstallAsync();
            Assert.False(await service.InstallPendingAsync(true));
            Assert.False(await service.InstallPendingAsync(false)); service.Stop();
            Assert.Null(await new UpdateCheck("0.0.0",http).CheckAsync());
            var installer = new UpdateInstaller(http);
            var upstream = new UpdateCheck.Available("99.0.0","https://github.com/Mahdi-mortazavi/relay/releases/download/v99/Relay-Setup-x64.exe",null,"https://x/sums");
            Assert.Equal(UpdateInstaller.Outcome.Unverifiable,await installer.InstallAsync(upstream,dir,_=>executions++));
            Assert.Null((await installer.DownloadAsync(upstream,dir)).Path);
            Assert.Equal(UpdateInstaller.Outcome.Unverifiable,installer.Run(Path.Combine(dir,"Relay-Setup-x64.exe"),_=>executions++));
            Assert.Equal(0,transport.Calls); Assert.Equal(0,executions); Assert.Equal(0,factories);
        } finally { if(Directory.Exists(dir)) Directory.Delete(dir,true); }
    }
}
