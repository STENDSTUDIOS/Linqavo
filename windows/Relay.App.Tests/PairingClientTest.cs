using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
using Linqavo.Core;
using Xunit;
namespace Linqavo.App.Tests;
public class PairingClientTest {
    const string Key="hSDwCYkwp1R0i33ctD73Wg2/Og0mOBr066SpjqqbTmo=";
    static string Reply=>JsonDocument.Parse(File.ReadAllText(Path.Combine(SharedContracts.Dir,"enrollment-v2-vectors.json"))).RootElement.GetProperty("reply").GetRawText();
    [Fact] public void PrivateKeyRemainsLocalAndLegacyKeyDeliveryIsRejected() {
        var result=PairingClient.Parse(Reply,Key);Assert.True(result.Ok);Assert.Equal(Key,result.Wg!.ClientPrivateKey);
        Assert.DoesNotContain("clientPrivateKey",PairingClient.Request(Key,"PC"));
        Assert.DoesNotContain("clientPrivateKey",JsonSerializer.Serialize(result.Wg));
        Assert.Equal("ERR_PAIRING_VERSION",PairingClient.Parse("{\"v\":1}").ErrorCode);
        Assert.False(PairingClient.Parse(Reply.Replace("\"serverPublicKey\"","\"clientPrivateKey\":\"stolen\",\"serverPublicKey\""),Key).Ok);
    }
    [Theory][InlineData("[]")][InlineData("{\"v\":1e99}")][InlineData("{\"v\":[]}")][InlineData("{\"v\":2,\"ok\":1,\"wg\":42}")]
    public void MalformedResponsesFailClosed(string json)=>Assert.False(PairingClient.Parse(json,Key).Ok);
    [Fact] public async Task OversizedLinesAreBounded() {
        using var stream=new MemoryStream(Encoding.UTF8.GetBytes(new string('x',8192)+"\n"));
        Assert.Null(await PairingClient.ReadBoundedLineAsync(stream,CancellationToken.None));
        Assert.Equal(4096,stream.Position);
    }
    [Theory][InlineData(true)][InlineData(false)]
    public async Task RealTlsOnlyDeliversEnrollmentToPinnedCertificate(bool correctPin) {
        using var storedKey=OperatingSystem.IsWindows()?CngKey.Create(CngAlgorithm.ECDsaP256,"Linqavo-test-"+Guid.NewGuid()):null;
        using var key=OperatingSystem.IsWindows()?new ECDsaCng(storedKey!):ECDsa.Create(ECCurve.NamedCurves.nistP256);
        try {
        var request=new CertificateRequest("CN=Relay Fork test",key,HashAlgorithmName.SHA256);
        using var certificate=request.CreateSelfSigned(DateTimeOffset.UtcNow.AddMinutes(-1),DateTimeOffset.UtcNow.AddHours(1));
        using var listener=new TcpListener(IPAddress.Loopback,0);listener.Start();
        var received=false; string? serverError=null;
        var server=Task.Run(async()=>{
            using var tcp=await listener.AcceptTcpClientAsync();using var tls=new SslStream(tcp.GetStream());
            using var timeout=new CancellationTokenSource(TimeSpan.FromSeconds(10));
            try {
                await tls.AuthenticateAsServerAsync(new SslServerAuthenticationOptions{ServerCertificate=certificate,EnabledSslProtocols=SslProtocols.Tls12|SslProtocols.Tls13},timeout.Token);
                var line=await PairingClient.ReadBoundedLineAsync(tls,timeout.Token);
                if(line is null)return;
                received=true;Assert.DoesNotContain("privateKey",line,StringComparison.OrdinalIgnoreCase);
                await tls.WriteAsync(Encoding.UTF8.GetBytes(Reply+"\n"),timeout.Token);
            }catch(Exception ex) when(ex is IOException or AuthenticationException or OperationCanceledException) {serverError=ex.ToString();}
        });
        var pin=correctPin?PairingClient.Fingerprint(certificate.RawData):new string('0',64);
        var result=await new PairingClient().FetchPinnedAsync("127.0.0.1",((IPEndPoint)listener.LocalEndpoint).Port,pin,Key,Key,"PC");
        await server;Assert.True(result.Ok==correctPin, $"{result.ErrorCode}; server: {serverError}");Assert.Equal(correctPin,received);
        Assert.Equal(correctPin,result.Authenticated);
        if(!correctPin)Assert.Equal("ERR_FINGERPRINT_MISMATCH",result.ErrorCode);
        } finally { storedKey?.Delete(); }
    }
}
