# Android client contract

Android uses enrollment v2 and presence v2 as documented in this directory.
Discovery beacons select a host and pairing port only. Trust comes from a v2
invitation scanned from the sharing phone or entry of its complete certificate
fingerprint. The certificate is checked before sending the public key. The
invitation's WireGuard public key, when present, must match the enrollment reply.

An IPv4 lease is one of 10.13.37.2/32 through 10.13.37.4/32. Both Android's
VPN route and WireGuard's peer route cover 0.0.0.0/0. The resolver is 1.1.1.1
and the MTU is 1420. IPv6 is blocked by leaving that family unconfigured and
never allowing bypass. Only the WireGuard UDP transport is protected and
bound to an on-link Wi-Fi network while the VPN is active. The presence stream
and DNS reachability check use the VPN network.

A running interface, fresh authenticated presence and usable IPv4 forwarding
are distinct. Recent incoming forwarded IP traffic (including a solicited
DNS response) is evidence of forwarding. Loss of the presence stream alone
does not negate recent forwarding. Stop and Revoke are terminal for an active
attempt. Disable preserves the identity and may recover after the phone enables
it. Keep the interface and its routes while transport recovers or access is
blocked. Deliberate disconnect, VPN revocation and process termination end that
protection under Android's service lifecycle.

Protect a separate client identity per server certificate with Android Keystore.
Reuse it when the same verified phone restarts sharing. Remove it only after authenticated revocation.
A subsequent explicit connection may generate a fresh key and requires approval.
A sharing restart rotates its WireGuard key and rebuilds the authorized peers from the phone’s local trust list. A saved target authenticates the current WireGuard key through pinned TLS. A new installation has a new certificate and requires a fresh invitation or full fingerprint comparison.
