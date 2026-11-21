package linqavowg

import (
	"linqavo.local/wg/internal/presence"
	"net"
	"strings"
	"time"
)

type presenceSession struct {
	conn       net.Conn
	seen       time.Time
	started    bool
	generation uint64
	control    *presence.Control
}

// State uses monotonic elapsed time, never wall-clock subtraction from the
// historical WireGuard handshake. TX retries cannot renew a dead peer's lease.
func peerPresenceState(peer *peerRecord, now time.Time) string {
	if !peer.Active {
		return "disabled"
	}
	if peer.goodbye {
		return "offline"
	}
	if peer.managedPresence &&
		(peer.presence == nil || now.Sub(peer.presence.seen) >= presence.ReconnectingAfter) &&
		!peer.lastForwarded.IsZero() && now.Sub(peer.lastForwarded) < presence.OfflineAfter {
		return "degraded"
	}
	if session := peer.presence; session != nil {
		age := now.Sub(session.seen)
		if age >= presence.OfflineAfter {
			return "offline"
		}
		if age >= presence.ReconnectingAfter {
			return "reconnecting"
		}
		return "connected"
	}
	if peer.managedPresence {
		return "offline"
	}
	if peer.lastInbound.IsZero() {
		if peer.LastHandshake > 0 {
			return "unknown"
		}
		return "authorized"
	}
	if now.Sub(peer.lastInbound) >= presence.OfflineAfter {
		return "offline"
	}
	return "connected"
}

func (e *Endpoint) servePresence(ip string, c net.Conn) {
	e.mu.Lock()
	var peer *peerRecord
	for _, candidate := range e.peers {
		if candidate.Active && strings.TrimSuffix(candidate.Address, "/32") == ip {
			peer = candidate
			break
		}
	}
	var session *presenceSession
	if peer != nil {
		peer.nextPresence++
		session = &presenceSession{conn: c, generation: peer.nextPresence}
	}
	e.mu.Unlock()
	if peer == nil {
		c.Close()
		return
	}
	presence.ServeManaged(c, func() bool {
		e.mu.Lock()
		if e.closed || !peer.Active || e.peers[peer.PublicKey] != peer ||
			(!session.started && session.generation <= peer.activePresence) ||
			(session.started && (peer.presence != session || time.Since(session.seen) >= presence.OfflineAfter)) {
			e.mu.Unlock()
			return false
		}
		old := peer.presence
		peer.presence = session
		peer.managedPresence = true
		peer.goodbye = false
		peer.activePresence = session.generation
		session.started = true
		session.seen = time.Now()
		peer.Status = "connected"
		e.mu.Unlock()
		if old != nil && old != session {
			old.conn.Close()
		}
		return true
	}, func(goodbye bool) {
		e.mu.Lock()
		defer e.mu.Unlock()
		if e.peers[peer.PublicKey] == peer && peer.presence == session {
			peer.presence = nil
			peer.goodbye = goodbye
			if peer.Active {
				peer.Status = peerPresenceState(peer, time.Now())
			}
		}
	}, func(control *presence.Control) { e.mu.Lock(); session.control = control; e.mu.Unlock() })
}
