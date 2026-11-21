namespace Linqavo.Core;

public static class ConnectionErrors
{
        public static (string Title,string Body,string? Primary) For(string code) => code switch
        {
            "ERR_SERVER_STOPPED" => ("ErrTitleNoPhone", "ErrServerStopped", "TryAgain"),
            "ERR_SERVER_UNAVAILABLE" => ("ErrTitleNoPhone", "ErrServerUnavailable", "TryAgain"),
            "ERR_CLIENT_DISABLED" => ("ErrTitlePairing", "ErrClientDisabled", "TryAgain"),
            "ERR_ENROLLMENT_REVOKED" => ("ErrTitlePairing", "ErrEnrollmentRevoked", "TryAgain"),
            "ERR_FINGERPRINT_MISMATCH" => ("ErrTitlePairing", "ErrFingerprintMismatch", "ScanQr"),
            "ERR_TRANSPORT_FAILURE" => ("ErrTitleTunnel", "ErrTransportFailure", "TryAgain"),
            "ERR_ROUTING_ERROR" => ("ErrTitleTunnel", "ErrRoutingError", "TryAgain"),
            "ERR_FORWARDING_UNAVAILABLE" => ("ErrTitleTunnel", "ErrForwardingUnavailable", "TryAgain"),
            "ERR_IDENTITY_FAILED" => ("ErrTitlePairing", "ErrIdentityFailed", "TryAgain"),
            "ERR_CAPACITY_REACHED" => ("ErrTitlePairing", "ErrCapacityReached", "TryAgain"),
            "ERR_PEER_BUSY" => ("ErrTitlePairing", "ErrPeerBusy", "TryAgain"),
            "ERR_QR_NEWER_VERSION" => ("ErrTitleCode", "ErrQrNewer", (string?)null),
            "ERR_QR_INVALID" => ("ErrTitleCode", "ErrQrInvalid", "ScanQr"),
            "ERR_CODE_INVALID" => ("ErrTitleCode", "ErrCodeInvalid", "EnterCode"),
            "ERR_CODE_NOT_FOUND" => ("ErrTitleCode", "ErrCodeNotFound", "EnterCode"),
            "ERR_CODE_AMBIGUOUS" => ("ErrTitleCode", "ErrCodeAmbiguous", "EnterCode"),
            "ERR_FULL_MODE_NEEDS_QR" => ("ErrTitleCode", "ErrFullModeNeedsQr", "ScanQr"),
            "ERR_HOST_UNREACHABLE" => ("ErrTitleNoPhone", "ErrHostUnreachable", "TryAgain"),
            "ERR_WRONG_NETWORK" => ("ErrTitleNetwork", "ErrWrongNetwork", "TryAgain"),
            "ERR_CONNECTION_LOST" => ("ErrTitleLost", "ErrConnectionLost", "TryAgain"),
            "ERR_FIREWALL_BLOCKED" => ("ErrTitleBlocked", "ErrFirewall", "TryAgain"),
            "ERR_PROXY_APPLY_FAILED" => ("ErrTitleProxy", "ErrProxyApply", "TryAgain"),
            "ERR_ROLLBACK_INCOMPLETE" => ("ErrTitleRollback", "ErrRollback", "Disconnect"),
            "ERR_CAMERA_DENIED" => ("ErrTitleCamera", "ErrCameraDenied", "EnterCodeInstead"),
            // Full Mode (ADR-0008). A declined elevation prompt is its own
            // answer: "the tunnel failed" would send someone hunting their
            // network for something they chose two seconds earlier.
            "ERR_WG_ELEVATION_DECLINED" => ("ErrTitleElevation", "ErrWgElevationDeclined", "TryAgain"),
            "ERR_WG_START_FAILED" => ("ErrTitleTunnel", "ErrWgStartFailed", "TryAgain"),
            "ERR_WG_ELEVATION_UNAVAILABLE" =>
                ("ErrTitleElevationBlocked", "ErrWgElevationUnavailable", (string?)null),
            "ERR_WG_NO_HANDSHAKE" => ("ErrTitleTunnel", "ErrWgNoHandshake", "ScanQr"),
            // Compatibility diagnostic only. An unauthenticated discovery hint
            // must never prevent an attempt through the pinned TLS endpoint.
            "ERR_PHONE_REPLIES_BLOCKED" =>
                ("ErrTitlePhoneBlocked", "ErrPhoneRepliesBlocked", "TryAgain"),
            "ERR_PAIRING_DENIED" => ("ErrTitlePairing", "ErrPairingDenied", "EnterCode"),
            "ERR_PAIRING_VERSION" => ("ErrTitlePairing", "ErrPairingVersion", "ScanQr"),
            "ERR_WG_ALREADY_RUNNING" => ("ErrTitleTunnel", "ErrWgAlreadyRunning", "Disconnect"),
            "ERR_WG_STOP_FAILED" => ("ErrTitleTunnel", "ErrWgStopFailed", "Disconnect"),
            // A code this build has no words for. It used to borrow the
            // retired proxy's message, which told people about a system setting
            // Relay no longer touches (P-02).
            _ => ("ErrTitleGeneric", "ErrGeneric", "TryAgain"),
        };
}
