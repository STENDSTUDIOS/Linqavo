namespace Linqavo.Core;

/// <summary>Presentation only; never changes tunnel or recovery policy.</summary>
public static class ConnectionPresentation
{
    public static string LossLabelKey(string? reason) =>
        reason is "ERR_SERVER_STOPPED" or "ERR_ENROLLMENT_REVOKED" or "ERR_SERVER_UNAVAILABLE"
            ? "StatusSuspended"
            : "Reconnecting";
}
