using System.Text;
using Linqavo.Core;
using Xunit;
namespace Linqavo.App.Tests;
public class DiscoverySecurityTests {
 [Theory]
 [InlineData("{\"v\":1,\"code\":[]}")]
 [InlineData("{\"v\":1e99}")]
 [InlineData("{\"v\":1,\"code\":\"42\",\"host\":true}")]
 [InlineData("{\"v\":1,\"code\":\"42\",\"host\":\"192.168.1.1\",\"port\":1e99}")]
 public void MalformedDiscoveryNeverEscapesReceiveLoop(string json) => Assert.False(LanDiscovery.TryParseBeacon(Encoding.UTF8.GetBytes(json),DateTimeOffset.UtcNow,out _,out _));
 [Fact] public void OversizedDiscoveryIsRejected() => Assert.False(LanDiscovery.TryParseBeacon(new byte[4097],DateTimeOffset.UtcNow,out _,out _));
 [Theory][InlineData("0.0.0.0/0\nprivate_key=evil")][InlineData("0.0.0.0/0 \" -name evil")]
 public void ConfigInjectionIsRejected(string ranges) {
 const string key="hSDwCYkwp1R0i33ctD73Wg2/Og0mOBr066SpjqqbTmo=";
 var wg=new WgParams{ServerPublicKey=key,ClientPrivateKey=key,AllowedIps=ranges,Dns="1.1.1.1",EndpointPort=51821};
 Assert.Throws<ArgumentException>(()=>WgClientConfig.ToIpc(wg,"192.168.1.1"));
 }
}
