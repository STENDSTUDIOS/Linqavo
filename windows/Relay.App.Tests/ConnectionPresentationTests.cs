using Linqavo.Core;
using Xunit;

namespace Linqavo.App.Tests;

public class ConnectionPresentationTests
{
    [Theory]
    [InlineData("ERR_SERVER_UNAVAILABLE", "StatusSuspended")]
    [InlineData("ERR_SERVER_STOPPED", "StatusSuspended")]
    [InlineData("ERR_ENROLLMENT_REVOKED", "StatusSuspended")]
    [InlineData("ERR_FORWARDING_UNAVAILABLE", "Reconnecting")]
    [InlineData(null, "Reconnecting")]
    [InlineData("unknown", "Reconnecting")]
    public void LossReasonSelectsTheExpectedExistingLabel(string? reason, string expected)
    {
        Assert.Equal(expected, ConnectionPresentation.LossLabelKey(reason));
    }
}
