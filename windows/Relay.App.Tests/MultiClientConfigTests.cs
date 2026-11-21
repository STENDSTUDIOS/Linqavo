using System.Text.Json;
using Linqavo.Core;
using Xunit;
namespace Linqavo.App.Tests;

public class MultiClientConfigTests {
    private sealed class Host : WgTunnelSession.IProcessHost {
        public string? Arguments;
        public WgTunnelSession.IProcessHandle Start(string arguments) { Arguments=arguments;return new Handle(); }
        private sealed class Handle : WgTunnelSession.IProcessHandle {
            public bool HasExited {get;private set;}
            public void WriteLine(string line) { }
            public string? ReadLine()=>WgTunnelSession.ReadyLine;
            public void CloseInput()=>HasExited=true;
            public bool WaitForExit(TimeSpan timeout)=>true;
            public void Kill()=>HasExited=true;
            public void Dispose() { }
        }
    }
    private const string Key="hSDwCYkwp1R0i33ctD73Wg2/Og0mOBr066SpjqqbTmo=";
    private static WgParams Parameters(string address)=>new(){ServerPublicKey=Key,ClientPrivateKey=Key,
        AllowedIps="0.0.0.0/0",Dns="1.1.1.1",EndpointPort=51821,ClientAddress=address};
    [Fact] public void EveryAssignedLeaseReachesConfigurationAndHelperArguments() {
        using var vectors=JsonDocument.Parse(File.ReadAllText(Path.Combine(SharedContracts.Dir,"multi-peer-vectors.json")));
        foreach(var lease in vectors.RootElement.GetProperty("leases").EnumerateArray()) {
            var address=lease.GetProperty("address").GetString()!;var parameters=Parameters(address);
            Assert.Contains("Address = "+address,WgClientConfig.ToIni(parameters,"192.168.1.1"));
            var host=new Host();var session=new WgTunnelSession(host);
            Assert.True(session.Connect(parameters,"192.168.1.1").Ok);
            Assert.Contains("-address "+address,host.Arguments!);session.Disconnect();
        }
        foreach(var invalid in vectors.RootElement.GetProperty("invalidLeases").EnumerateArray())
            Assert.Throws<ArgumentException>(()=>WgClientConfig.ToIpc(Parameters(invalid.GetString()!),"192.168.1.1"));
    }
    [Fact] public void EnrollmentResponseSuppliesDynamicLeaseWhilePrivateKeyStaysLocal() {
        var response=File.ReadAllText(Path.Combine(SharedContracts.Dir,"enrollment-v2-vectors.json"));
        using var json=JsonDocument.Parse(response);
        var reply=json.RootElement.GetProperty("reply").GetRawText().Replace("10.13.37.2/32","10.13.37.4/32");
        var result=PairingClient.Parse(reply,Key);
        Assert.True(result.Ok);Assert.Equal("10.13.37.4/32",result.Wg!.ClientAddress);Assert.Equal(Key,result.Wg.ClientPrivateKey);
    }
}
