namespace Linqavo.Core;

/// <summary>Session-scoped client identities; only an authenticated revocation rotates a key.</summary>
public sealed class EnrollmentRecovery
{
    private readonly Dictionary<string,ClientIdentity> _identities = new();
    private readonly SemaphoreSlim _gate = new(1,1);

    public async Task<PairingClient.Result> EnrollAsync(string pin, Func<Task<ClientIdentity>> generate,
        Func<ClientIdentity,Task<PairingClient.Result>> fetch, Action<ClientIdentity>? display = null,
        Action? retire = null)
    {
        await _gate.WaitAsync();
        try {
            string? retiredKey=null;
            for(var attempt=0;attempt<2;attempt++) {
                if(!_identities.TryGetValue(pin,out var identity)) {
                    try { identity=await generate(); } catch { return PairingClient.Result.Fail("ERR_IDENTITY_FAILED"); }
                    if(identity.PublicKey==retiredKey)return PairingClient.Result.Fail("ERR_IDENTITY_FAILED");
                    if(_identities.Count>=16)_identities.Remove(_identities.Keys.First());
                    _identities[pin]=identity;
                }
                display?.Invoke(identity);
                var result=await fetch(identity);
                if(result.ErrorCode!="ERR_ENROLLMENT_REVOKED" || !result.Authenticated)return result;
                retiredKey=identity.PublicKey;
                _identities.Remove(pin); // never reuse revoked credentials or transfer approval
                retire?.Invoke();
                if (attempt != 0) return result;
            }
            return PairingClient.Result.Fail("ERR_ENROLLMENT_REVOKED");
        } finally { _gate.Release(); }
    }
}
