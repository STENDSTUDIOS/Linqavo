using System.Net;
using System.Net.Sockets;
using System.Net.Security;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace Linqavo.Core;

/// <summary>Version 2 enrollment. No client private key is ever sent to the phone.</summary>
public sealed class PairingClient
{
    public const int Version = 2;
    public const int MaximumLineBytes = 4096;
    public sealed record Result(WgParams? Wg, string? Host, int Port, string? ErrorCode) {
        public bool Authenticated {get;init;}
        public bool Ok => Wg is not null && ErrorCode is null;
        public static Result Fail(string code) => new(null,null,0,code);
    }
    public static bool ValidHost(string host) => IPAddress.TryParse(host,out var ip) &&
        ip.AddressFamily == AddressFamily.InterNetwork && ip.ToString()==host;
    public static bool ValidFingerprint(string? pin) => pin is {Length:64} && pin.All(c=>c is >= '0' and <= '9' or >= 'a' and <= 'f');
    public static string Fingerprint(byte[] der) => Convert.ToHexString(SHA256.HashData(der)).ToLowerInvariant();

    // The returned fingerprint is UNAUTHENTICATED. UI must compare it with the phone
    // before passing it to FetchPinnedAsync; this connection transmits no enrollment data.
    public static async Task<string> ProbeUntrustedFingerprintAsync(string host,int port) {
        if(!ValidHost(host)||port is <1 or >65535) throw new ArgumentException("invalid endpoint");
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        using var tcp=new TcpClient(); await tcp.ConnectAsync(host,port,timeout.Token);
        string? pin=null;
        using var tls=new SslStream(tcp.GetStream(),false,(_,cert,_,_)=>{pin=cert is null?null:Fingerprint(cert.GetRawCertData());return pin is not null;});
        await tls.AuthenticateAsClientAsync(Options(host),timeout.Token);
        return pin ?? throw new AuthenticationException("missing certificate");
    }
    private static SslClientAuthenticationOptions Options(string host)=>new(){TargetHost=host,
        EnabledSslProtocols=SslProtocols.Tls12|SslProtocols.Tls13,AllowRenegotiation=false};

    public async Task<Result> FetchPinnedAsync(string host,int port,string pin,string publicKey,string privateKey,string? name=null) {
        if(!ValidHost(host)||port is <1 or >65535||!ValidFingerprint(pin))return Result.Fail("ERR_QR_INVALID");
        bool pinMismatch=false;
        try {
            WgClientConfig.ToHex(publicKey); WgClientConfig.ToHex(privateKey);
            using var timeout=new CancellationTokenSource(TimeSpan.FromSeconds(75));
            using var tcp=new TcpClient(); await tcp.ConnectAsync(host,port,timeout.Token);
            using var tls=new SslStream(tcp.GetStream(),false,(_,cert,_,_)=> {
                var matches=cert is not null && CryptographicOperations.FixedTimeEquals(Convert.FromHexString(pin),SHA256.HashData(cert.GetRawCertData()));
                pinMismatch=!matches;return matches;
            });
            using(var handshake=CancellationTokenSource.CreateLinkedTokenSource(timeout.Token)) {
                handshake.CancelAfter(TimeSpan.FromSeconds(10));
                await tls.AuthenticateAsClientAsync(Options(host),handshake.Token);
            }
            var request=Encoding.UTF8.GetBytes(Request(publicKey,name)+"\n");
            await tls.WriteAsync(request,timeout.Token);
            var line=await ReadBoundedLineAsync(tls,timeout.Token);
            return line is null?Result.Fail("ERR_TRANSPORT_FAILURE"):Parse(line,privateKey) with {Authenticated=true};
        } catch(Exception ex) when(ex is IOException or SocketException or AuthenticationException or OperationCanceledException or ArgumentException) {
            return Result.Fail(pinMismatch?"ERR_FINGERPRINT_MISMATCH":ex is SocketException?"ERR_SERVER_UNAVAILABLE":"ERR_TRANSPORT_FAILURE");
        }
    }
    public static string Request(string publicKey,string? name=null) {
        var clean=new string((name??"").Where(c=>!char.IsControl(c)&&char.GetUnicodeCategory(c)!=System.Globalization.UnicodeCategory.Format).Take(32).ToArray());
        return JsonSerializer.Serialize(new{v=Version,pair=1,name=clean,clientPublicKey=publicKey});
    }
    public static async Task<string?> ReadBoundedLineAsync(Stream stream,CancellationToken token) {
        var bytes=new byte[MaximumLineBytes];int count=0;
        while(count<bytes.Length){
            if(await stream.ReadAsync(bytes.AsMemory(count,1),token)==0)return null;
            if(bytes[count]==10)return new UTF8Encoding(false,true).GetString(bytes,0,count);
            if(bytes[count] is 0 or 13)return null;
            count++;
        }
        return null;
    }
    public static Result Parse(string reply,string privateKey="") {
        if(Encoding.UTF8.GetByteCount(reply)>MaximumLineBytes)return Result.Fail("ERR_QR_INVALID");
        try {
            using var json=JsonDocument.Parse(reply); var root=json.RootElement;
            if(root.GetProperty("v").GetInt32()!=Version)return Result.Fail("ERR_PAIRING_VERSION");
            if(root.TryGetProperty("error",out var error)) {
                var code=error.GetString();
                return Result.Fail(code is "ERR_PAIRING_DENIED" or "ERR_CLIENT_DISABLED" or "ERR_ENROLLMENT_REVOKED" or "ERR_CAPACITY_REACHED" or "ERR_PEER_BUSY" or "ERR_SERVER_UNAVAILABLE" or "ERR_FORWARDING_UNAVAILABLE" ? code : "ERR_PAIRING_VERSION");
            }
            if(root.GetProperty("ok").GetInt32()!=1)return Result.Fail("ERR_QR_INVALID");
            var wg=root.GetProperty("wg");
            if(wg.TryGetProperty("clientPrivateKey",out _))return Result.Fail("ERR_QR_INVALID");
            var parameters=new WgParams { ServerPublicKey=wg.GetProperty("serverPublicKey").GetString()!,ClientPrivateKey=privateKey,
                AllowedIps=wg.GetProperty("allowedIps").GetString()!,EndpointPort=wg.GetProperty("endpointPort").GetInt32(),
                Dns=wg.GetProperty("dns").GetString()!,ClientAddress=wg.GetProperty("clientAddress").GetString()!};
            var host=root.GetProperty("host").GetString()!;var port=root.GetProperty("port").GetInt32();
            if(!ValidHost(host)||port is <1 or >65535)return Result.Fail("ERR_QR_INVALID");
            WgClientConfig.ToIpc(parameters,host);
            return new(parameters,host,port,null);
        }catch(Exception ex) when(ex is JsonException or InvalidOperationException or KeyNotFoundException or FormatException or ArgumentException or OverflowException){return Result.Fail("ERR_QR_INVALID");}
    }
}
