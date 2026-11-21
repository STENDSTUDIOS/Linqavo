using Linqavo.Core;
using Xunit;

namespace Linqavo.App.Tests;

public class LifecycleRecoveryTests
{
    private static ClientIdentity Identity(string id)=>new("private-"+id,"public-"+id);
    [Fact] public async Task AuthenticatedRevocationCreatesFreshIdentityWithBoundedRetry() {
        var recovery=new EnrollmentRecovery();var generated=0;var requested=new List<string>();
        var result=await recovery.EnrollAsync("pin",()=>Task.FromResult(Identity((++generated).ToString())),identity=>{
            requested.Add(identity.PublicKey);
            return Task.FromResult(PairingClient.Result.Fail(requested.Count==1?"ERR_ENROLLMENT_REVOKED":"ERR_PAIRING_DENIED") with {Authenticated=true});
        });
        Assert.Equal(2,generated);Assert.Equal(2,requested.Distinct().Count());Assert.Equal("ERR_PAIRING_DENIED",result.ErrorCode);
    }
    [Theory] [InlineData("ERR_CLIENT_DISABLED",true)] [InlineData("ERR_ENROLLMENT_REVOKED",false)] [InlineData("ERR_FINGERPRINT_MISMATCH",false)]
    public async Task DisabledOrUnauthenticatedErrorsCannotRotateIdentity(string code,bool trusted) {
        var recovery=new EnrollmentRecovery();var generated=0;
        for(var attempt=0;attempt<2;attempt++) await recovery.EnrollAsync("pin",()=>{generated++;return Task.FromResult(Identity("same"));},identity=>Task.FromResult(PairingClient.Result.Fail(code) with {Authenticated=trusted}));
        Assert.Equal(1,generated);
    }
    [Fact] public async Task RevokedIdentityCannotBeReissuedByBrokenGenerator() {
        var recovery=new EnrollmentRecovery();var fetches=0;
        var result=await recovery.EnrollAsync("pin",()=>Task.FromResult(Identity("same")),identity=>{fetches++;return Task.FromResult(PairingClient.Result.Fail("ERR_ENROLLMENT_REVOKED") with{Authenticated=true});});
        Assert.Equal("ERR_IDENTITY_FAILED",result.ErrorCode);Assert.Equal(1,fetches);
    }
    [Fact] public async Task RetiresPersistedKeyBeforeRetryingWithNewIdentity()
    {
        ClientIdentity? saved = Identity("old");
        var result = await new EnrollmentRecovery().EnrollAsync("phone",
            () => Task.FromResult(saved ?? Identity("new")),
            key => Task.FromResult(PairingClient.Result.Fail(key == saved ? "ERR_ENROLLMENT_REVOKED" : "ERR_PAIRING_DENIED") with { Authenticated = true }),
            retire: () => saved = null);
        Assert.Null(saved);
        Assert.Equal("ERR_PAIRING_DENIED", result.ErrorCode);
    }

    [Theory]
    [InlineData("SERVER-STOPPED","ERR_SERVER_STOPPED","ErrServerStopped")]
    [InlineData("CLIENT-DISABLED","ERR_CLIENT_DISABLED","ErrClientDisabled")]
    [InlineData("ENROLLMENT-REVOKED","ERR_ENROLLMENT_REVOKED","ErrEnrollmentRevoked")]
    [InlineData("SERVER-UNAVAILABLE","ERR_SERVER_UNAVAILABLE","ErrServerUnavailable")]
    [InlineData("ROUTING-FAILED","ERR_ROUTING_ERROR","ErrRoutingError")]
    public void HelperReasonsReachDistinctUiDiagnostics(string line,string code,string body) {
        Assert.Equal(code,WgTunnelSession.ClassifyLifecycleLine(line));Assert.Equal(body,ConnectionErrors.For(code).Body);
    }
    [Fact] public void DiscoveryReplyWarningDoesNotReplaceAuthenticatedTransportFailure() {
        Assert.NotEqual(ConnectionErrors.For("ERR_PHONE_REPLIES_BLOCKED").Body,ConnectionErrors.For("ERR_TRANSPORT_FAILURE").Body);
        Assert.Null(WgTunnelSession.ClassifyLifecycleLine("SERVER-STOPPED injected"));
    }
}
