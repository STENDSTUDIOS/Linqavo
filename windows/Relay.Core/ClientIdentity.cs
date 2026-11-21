using System.Diagnostics;
using System.Text.Json;
namespace Linqavo.Core;
public sealed record ClientIdentity(string PrivateKey,string PublicKey) {
    public override string ToString() => "Client identity (private key redacted)";
    public string Fingerprint => PairingClient.Fingerprint(Convert.FromBase64String(PublicKey));
    public static async Task<ClientIdentity> GenerateAsync(string helperPath) {
        using var timeout=new CancellationTokenSource(TimeSpan.FromSeconds(10));
        var info=new ProcessStartInfo(helperPath){UseShellExecute=false,RedirectStandardOutput=true,RedirectStandardError=true,CreateNoWindow=true};
        info.ArgumentList.Add("-generate-keypair");
        using var process=Process.Start(info)??throw new IOException("key generator unavailable");
        try {
            var line=await PairingClient.ReadBoundedLineAsync(process.StandardOutput.BaseStream,timeout.Token);
            await process.WaitForExitAsync(timeout.Token);
            if(process.ExitCode!=0||line is null)throw new IOException("key generation failed");
            using var json=JsonDocument.Parse(line);
            var pair=new ClientIdentity(json.RootElement.GetProperty("privateKey").GetString()!,json.RootElement.GetProperty("publicKey").GetString()!);
            WgClientConfig.ToHex(pair.PrivateKey);WgClientConfig.ToHex(pair.PublicKey);return pair;
        } catch {
            try {if(!process.HasExited)process.Kill();}catch { }
            throw new IOException("client identity generation failed");
        }
    }
}
