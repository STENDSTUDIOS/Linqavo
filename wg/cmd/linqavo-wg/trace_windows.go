package main

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"strings"
	"time"

	"golang.org/x/crypto/curve25519"
)

func traceIDs(config string) (session, peer string) {
	keyID := func(key []byte) string {
		if len(key) != 32 {
			return "unknown"
		}
		hash := sha256.Sum256(key)
		return hex.EncodeToString(hash[:6])
	}
	public, _ := hex.DecodeString(peerPublicKey(config))
	peer = "unknown"
	for _, line := range strings.Split(config, "\n") {
		if key, ok := strings.CutPrefix(line, "private_key="); ok {
			private, _ := hex.DecodeString(key)
			if derived, err := curve25519.X25519(private, curve25519.Basepoint); err == nil {
				peer = keyID(derived)
			}
			clear(private)
			break
		}
	}
	return keyID(public), peer
}

func traceLine(started time.Time, session, peer, state, control string, hsAge, rx, tx, dnsOK, dnsFail int64) string {
	return fmt.Sprintf("TRACE role=windows session=%s peer=%s protocol=RLYP0002 utcMs=%d monoMs=%d "+
		"event=helper state=%s control=%s hsAgeSec=%d rxDelta=%d txDelta=%d dnsOK=%d dnsFail=%d",
		session, peer, time.Now().UnixMilli(), time.Since(started).Milliseconds(),
		state, control, hsAge, rx, tx, dnsOK, dnsFail)
}
