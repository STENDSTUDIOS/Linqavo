using System.Text.Json;
using Linqavo.Core;
using Windows.Security.Cryptography;
using Windows.Security.Cryptography.DataProtection;

namespace Linqavo.App.Services;

internal sealed class SavedClientIdentity(string directory)
{
    internal static SavedClientIdentity CurrentUser { get; } = new(Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Linqavo", "identities"));

    private string FileName(string pin)
    {
        if (pin.Length != 64 || !pin.All(Uri.IsHexDigit)) throw new ArgumentException("Invalid certificate pin");
        return Path.Combine(directory, pin.ToLowerInvariant());
    }

    internal async Task<ClientIdentity?> ReadAsync(string pin)
    {
        byte[] ciphertext;
        try { ciphertext = await File.ReadAllBytesAsync(FileName(pin)); }
        catch (FileNotFoundException) { return null; }
        catch (DirectoryNotFoundException) { return null; }
        var plain = await new DataProtectionProvider().UnprotectAsync(CryptographicBuffer.CreateFromByteArray(ciphertext));
        CryptographicBuffer.CopyToByteArray(plain, out var bytes);
        try
        {
            var saved = JsonSerializer.Deserialize<Record>(bytes) ?? throw new InvalidDataException();
            if (!StringComparer.OrdinalIgnoreCase.Equals(pin, saved.Pin)) throw new InvalidDataException();
            WgClientConfig.ToHex(saved.Identity.PrivateKey);
            WgClientConfig.ToHex(saved.Identity.PublicKey);
            return saved.Identity;
        }
        finally { System.Security.Cryptography.CryptographicOperations.ZeroMemory(bytes); }
    }

    internal async Task SaveAsync(string pin, ClientIdentity identity)
    {
        var path = FileName(pin);
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var bytes = JsonSerializer.SerializeToUtf8Bytes(new Record(pin, identity));
        var tmp = path + "." + Guid.NewGuid().ToString("N");
        try
        {
            var encrypted = await new DataProtectionProvider("LOCAL=user").ProtectAsync(CryptographicBuffer.CreateFromByteArray(bytes));
            CryptographicBuffer.CopyToByteArray(encrypted, out var output);
            await File.WriteAllBytesAsync(tmp, output);
            File.Move(tmp, path, overwrite: true);
        }
        finally { System.Security.Cryptography.CryptographicOperations.ZeroMemory(bytes); File.Delete(tmp); }
    }

    internal void Forget(string pin)
    {
        var path = FileName(pin);
        if (Directory.Exists(Path.GetDirectoryName(path))) File.Delete(path);
    }

    private sealed record Record(string Pin, ClientIdentity Identity);
}
