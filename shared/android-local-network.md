# Android local network access

Relay targets SDK 37. On Android 17 (API 37) and later, sharing requires
`android.permission.ACCESS_LOCAL_NETWORK` before discovery, TCP pairing or the
native WireGuard endpoint starts. Earlier Android releases do not request it.

All user start actions (including retry, shortcut, widget and Quick Settings)
use the Activity permission flow. Notification actions may stop sharing or
approve a pending client; they cannot bypass the service permission guard.
Denial leaves sharing stopped and offers app Settings. Returning from Settings
rechecks the grant; it resumes a start only when the user explicitly chose that
continuation. Revocation stops the endpoint, pairing server and beacon and
forgets session keys and approvals. A sticky restart must recheck the grant.
Granting permission alone must not silently start a new session.

The shared five-state machine and wire protocol are unchanged. The permission
is an Android prerequisite, not a new connection state.

Reference: https://developer.android.com/privacy-and-security/local-network-permission
