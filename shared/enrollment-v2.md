# Linqavo enrollment v2

The two-digit code is an unauthenticated discovery selector. Never use it as a
password. Legacy v1 Full Mode enrollment is refused; Fast Mode remains v1 and
retains its separate IP-based authorization limitations.

Full Mode invitation version 2 contains the phone IPv4 address, endpoint port,
pairingPort, server WireGuard public parameters, and tlsFingerprint (64 lowercase
hex SHA-256 of the installation-scoped, Android Keystore-backed X.509 DER certificate). It NEVER contains a private
key. Scan the QR on a trusted phone display, or compare the entire certificate
fingerprint with the phone before accepting a discovered server. Discovery may
advertise the fingerprint, but cannot authenticate it. No silent TOFU.

Use platform TLS 1.2 or later with pinned installation certificate; prefer TLS 1.3.
No plaintext enrollment or 0-RTT. The client generates its own WireGuard keypair
using the existing Go crypto library, keeps the private key locally, and sends
one bounded UTF-8 JSON line: {v:2,pair:1,name:...,clientPublicKey:...}.
Android approval identifies the client by the full SHA-256 of this public key,
which must match the PC display. Names and LAN addresses are display hints.
First approval remembers the canonical WireGuard public key on this server. A fresh identity always needs approval. Pending prompts and management actions still have unique session generations.
Server identity comes from the pinned TLS certificate; client enrollment comes
from physical explicit approval of the cryptographic public identity. WireGuard
then proves possession of the corresponding client private key.

TLS protects integrity, confidentiality and replay of the exchange. A recorded
request cannot install a new peer without approval. Saved public keys are restored by the server independently of enrollment requests. The returning client must still complete WireGuard authentication to use its lease. The approved
public key is enrolled with exactly one unique /32 tunnel lease. Response:
{v:2,ok:1,host:...,port:...,wg:{serverPublicKey:...,allowedIps:"0.0.0.0/0",
endpointPort:...,dns:"1.1.1.1",clientAddress:"10.13.37.2/32"}}.
Response NEVER contains a client private key. The client uses its local key.
Older versions and unexpected fields/types/addresses fail closed.

Limits: 8 accepted enrollment sockets, TCP backlog 8, 4096 bytes per line,
10 seconds for TLS/request, 75 seconds total socket lifetime, at most 8 queued
approvals, session decision cache bounded to 128. No secrets in logs.

Lifecycle errors are specified in `connection-lifecycle.json`. Disabled and
revoked identities receive distinct errors through pinned TLS. A disabled
identity keeps its key/address and must not rotate; a revoked identity must
never reactivate. Only an authenticated revocation response permits one fresh
key generation and a fresh explicit Android approval, not transfer of the old
approval. Discovery reply flags cannot gate a pinned TLS attempt. Presence v2
server events are separate from enrollment and defined in `peer-presence.json`.

Trusted devices are stored only on the sharing phone. The list holds at most three public keys and display names. Disabled records remain disabled after restarting sharing. Revocation removes the remembered record and retires the key before the live operation. Retired keys receive the authenticated rotation response across sharing sessions. Forgetting in Settings removes future automatic access; the current session can be revoked separately. A new server install has a new pin. Windows stores per-pin identities under current-user encryption; Android uses Android Keystore.
