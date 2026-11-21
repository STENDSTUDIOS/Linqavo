package linqavowg

import (
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"golang.org/x/crypto/curve25519"
	"net"
	"sort"
	"strconv"
	"strings"
	"time"
	"unicode"

	"linqavo.local/wg/internal/presence"
)

const MaxPeerCapacity = 3

type peerRecord struct {
	PublicKey       string `json:"publicKey"`
	Name            string `json:"name"`
	Address         string `json:"address"`
	Active          bool   `json:"active"`
	Status          string `json:"status"`
	EnrolledAt      int64  `json:"enrolledAt"`
	LastHandshake   int64  `json:"lastHandshake"`
	LastActivity    int64  `json:"lastActivity"`
	BytesReceived   int64  `json:"bytesReceived"`
	BytesSent       int64  `json:"bytesSent"`
	ForwardingAgeMs int64  `json:"forwardingAgeMs"`
	ControlState    string `json:"controlState"`
	rx, tx          int64
	lastInbound     time.Time
	lastForwarded   time.Time
	goodbye         bool
	managedPresence bool
	presence        *presenceSession
	nextPresence    uint64
	activePresence  uint64
}

func peerKey(publicKey string) (string, error) {
	raw, err := base64.StdEncoding.DecodeString(publicKey)
	if err != nil || len(raw) != 32 || base64.StdEncoding.EncodeToString(raw) != publicKey {
		return "", errors.New("invalid public key")
	}
	// Reject low-order points through the reviewed X25519 implementation.
	if _, err = curve25519.X25519(make([]byte, 32), raw); err != nil {
		return "", errors.New("invalid public key point")
	}
	return hex.EncodeToString(raw), nil
}
func cleanPeerName(name string) string {
	var out []rune
	for _, r := range name {
		if !unicode.IsControl(r) && !unicode.Is(unicode.Cf, r) {
			out = append(out, r)
			if len(out) == 32 {
				break
			}
		}
	}
	return strings.TrimSpace(string(out))
}
func (e *Endpoint) SetCapacity(capacity int) error {
	if capacity < 1 || capacity > MaxPeerCapacity {
		return errors.New("capacity must be 1..3")
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.closed || e.stopping || e.dev == nil {
		return errors.New("endpoint stopped")
	}
	if len(e.peers) > capacity {
		return errors.New("remove peers before reducing capacity")
	}
	e.maxPeers = capacity
	return nil
}

// EnrollPeer installs only the approved key's /32 route. It never replaces peers.
func (e *Endpoint) EnrollPeer(publicKey, name string) (string, error) {
	key, err := peerKey(publicKey)
	if err != nil {
		return "", err
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.closed || e.stopping || e.dev == nil {
		return "", errors.New("endpoint stopped")
	}
	if e.revoked[publicKey] {
		return "", errors.New("key revoked for this session")
	}
	if existing := e.peers[publicKey]; existing != nil {
		if !existing.Active {
			return "", errors.New("peer disabled; Android must reactivate")
		}
		return existing.Address, nil
	}
	if len(e.revoked) >= 128 || len(e.peers) >= e.maxPeers {
		return "", errors.New("capacity reached")
	}
	address := ""
	for ordinal := 0; ordinal < MaxPeerCapacity; ordinal++ {
		candidate := fmt.Sprintf("10.13.37.%d/32", ordinal+2)
		used := false
		for _, peer := range e.peers {
			if peer.Address == candidate {
				used = true
				break
			}
		}
		if !used && e.tun.budget.available(strings.TrimSuffix(candidate, "/32")) {
			address = candidate
			break
		}
	}
	if address == "" {
		return "", errors.New("leases still draining")
	}
	if err = e.dev.IpcSet(fmt.Sprintf("public_key=%s\nreplace_allowed_ips=true\nallowed_ip=%s\n", key, address)); err != nil {
		return "", err
	}
	e.tun.budget.unblock(strings.TrimSuffix(address, "/32"))
	e.peers[publicKey] = &peerRecord{PublicKey: publicKey, Name: cleanPeerName(name), Address: address, Active: true, Status: "authorized", EnrolledAt: time.Now().Unix()}
	return address, nil
}

var errFlowsDraining = errors.New("old flows still draining")

func (e *Endpoint) SetPeerActive(publicKey string, active bool) error {
	deadline := time.Now().Add(2 * time.Second)
	for {
		err := e.setPeerActiveOnce(publicKey, active)
		if !active || !errors.Is(err, errFlowsDraining) || time.Now().After(deadline) {
			return err
		}
		time.Sleep(10 * time.Millisecond)
	}
}
func (e *Endpoint) setPeerActiveOnce(publicKey string, active bool) error {
	key, err := peerKey(publicKey)
	if err != nil {
		return err
	}
	e.managementMu.Lock()
	defer e.managementMu.Unlock()
	e.mu.Lock()
	if e.closed || e.stopping || e.dev == nil {
		e.mu.Unlock()
		return errors.New("endpoint stopped")
	}
	peer := e.peers[publicKey]
	if peer == nil || e.revoked[publicKey] {
		e.mu.Unlock()
		return errors.New("unknown or revoked peer")
	}
	if peer.Active == active {
		e.mu.Unlock()
		return nil
	}
	e.refreshPeersLocked()
	ip := strings.TrimSuffix(peer.Address, "/32")
	if active {
		defer e.mu.Unlock()
		if !e.tun.budget.available(ip) {
			return errFlowsDraining
		}
		if err = e.dev.IpcSet(fmt.Sprintf("public_key=%s\nreplace_allowed_ips=true\nallowed_ip=%s\n", key, peer.Address)); err != nil {
			return err
		}
		e.tun.budget.unblock(ip)
		peer.LastHandshake = 0
		peer.rx = 0
		peer.tx = 0
		peer.lastInbound = time.Time{}
		peer.presence = nil
		peer.managedPresence = false
		peer.goodbye = false
		peer.activePresence = peer.nextPresence
		peer.Active = true
		peer.Status = "authorized"
		return nil
	}
	session := peer.presence
	var except net.Conn
	if session != nil {
		except = session.conn
	}
	e.tun.budget.revokeExcept(ip, except)
	e.mu.Unlock()
	if session != nil && session.control != nil {
		session.control.Notify('X')
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	if err = e.dev.IpcSet("public_key=" + key + "\nremove=true\n"); err != nil {
		return err
	}
	e.tun.budget.revoke(ip)
	peer.Active = false
	peer.Status = "disabled"
	peer.presence = nil
	return nil
}
func (e *Endpoint) RemovePeer(publicKey string) error {
	key, err := peerKey(publicKey)
	if err != nil {
		return err
	}
	e.managementMu.Lock()
	defer e.managementMu.Unlock()
	e.mu.Lock()
	if e.closed || e.stopping || e.dev == nil {
		e.mu.Unlock()
		return errors.New("endpoint stopped")
	}
	peer := e.peers[publicKey]
	if peer == nil {
		e.mu.Unlock()
		return nil
	}
	ip := strings.TrimSuffix(peer.Address, "/32")
	session := peer.presence
	var except net.Conn
	if session != nil {
		except = session.conn
	}
	e.revoked[publicKey] = true
	e.tun.budget.revokeExcept(ip, except)
	e.mu.Unlock()
	if session != nil && session.control != nil {
		session.control.Notify('R')
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	if err = e.dev.IpcSet("public_key=" + key + "\nremove=true\n"); err != nil {
		return err
	}
	e.tun.budget.revoke(ip)
	delete(e.peers, publicKey)
	return nil
}

// Public error codes only; no secret or identity material is returned.
func (e *Endpoint) EnrollmentError(publicKey string) string {
	if _, err := peerKey(publicKey); err != nil {
		return "ERR_PAIRING_DENIED"
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.closed || e.stopping || e.dev == nil {
		return "ERR_SERVER_UNAVAILABLE"
	}
	if e.revoked[publicKey] {
		return "ERR_ENROLLMENT_REVOKED"
	}
	if peer := e.peers[publicKey]; peer != nil {
		if !peer.Active {
			return "ERR_CLIENT_DISABLED"
		}
		return ""
	}
	if len(e.peers) >= e.maxPeers || len(e.revoked) >= 128 {
		return "ERR_CAPACITY_REACHED"
	}
	return ""
}
func PeerEnrollmentError(publicKey string) string {
	activeMu.Lock()
	e := active
	activeMu.Unlock()
	if e == nil {
		return "ERR_SERVER_UNAVAILABLE"
	}
	return e.EnrollmentError(publicKey)
}

// Call with e.mu held. Only public identity/counters are copied out of IPC status.
func (e *Endpoint) refreshPeersLocked() {
	if e.dev == nil {
		return
	}
	status, err := e.dev.IpcGet()
	if err != nil {
		for _, peer := range e.peers {
			if peer.Active {
				peer.Status = "unknown"
			}
		}
		return
	}
	now := time.Now()
	var peer *peerRecord
	for _, line := range strings.Split(status, "\n") {
		k, v, ok := strings.Cut(line, "=")
		if !ok {
			continue
		}
		if k == "public_key" {
			raw, err := hex.DecodeString(v)
			if err != nil {
				peer = nil
				continue
			}
			peer = e.peers[base64.StdEncoding.EncodeToString(raw)]
			continue
		}
		if peer == nil || !peer.Active {
			continue
		}
		number, err := strconv.ParseInt(v, 10, 64)
		if err != nil || number < 0 {
			continue
		}
		switch k {
		case "last_handshake_time_sec":
			peer.LastHandshake = number
		case "rx_bytes":
			if number > peer.rx {
				peer.BytesReceived += number - peer.rx
				peer.LastActivity = now.Unix()
				peer.lastInbound = now
			}
			peer.rx = number
		case "tx_bytes":
			if number > peer.tx {
				peer.BytesSent += number - peer.tx
			}
			peer.tx = number
		}
	}
	for _, peer := range e.peers {
		peer.lastForwarded = e.tun.budget.lastForwarded(strings.TrimSuffix(peer.Address, "/32"))
		peer.ForwardingAgeMs = -1
		if !peer.lastForwarded.IsZero() {
			peer.ForwardingAgeMs = time.Since(peer.lastForwarded).Milliseconds()
		}
		peer.ControlState = "absent"
		if session := peer.presence; session != nil {
			peer.ControlState = session.control.Version()
			if now.Sub(session.seen) >= presence.ReconnectingAfter {
				peer.ControlState += "-stale"
			}
		}
		peer.Status = peerPresenceState(peer, now)
	}
}
func (e *Endpoint) PeerSnapshotsJSON() string {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.closed {
		return "[]"
	}
	e.refreshPeersLocked()
	peers := make([]peerRecord, 0, len(e.peers))
	for _, peer := range e.peers {
		peers = append(peers, *peer)
	}
	sort.Slice(peers, func(i, j int) bool { return peers[i].Address < peers[j].Address })
	raw, _ := json.Marshal(peers)
	return string(raw)
}
func EnrollEndpointPeer(publicKey, name string) (string, error) {
	activeMu.Lock()
	defer activeMu.Unlock()
	if active == nil {
		return "", errors.New("no endpoint")
	}
	return active.EnrollPeer(publicKey, name)
}
func SetEndpointCapacity(capacity int64) error {
	if capacity < 1 || capacity > MaxPeerCapacity {
		return errors.New("capacity must be 1..3")
	}
	activeMu.Lock()
	defer activeMu.Unlock()
	if active == nil {
		return errors.New("no endpoint")
	}
	return active.SetCapacity(int(capacity))
}
func SetEndpointPeerActive(publicKey string, enabled bool) error {
	activeMu.Lock()
	e := active
	activeMu.Unlock()
	if e == nil {
		return errors.New("no endpoint")
	}
	return e.SetPeerActive(publicKey, enabled)
}
func RemoveEndpointPeer(publicKey string) error {
	activeMu.Lock()
	defer activeMu.Unlock()
	if active == nil {
		return errors.New("no endpoint")
	}
	return active.RemovePeer(publicKey)
}
func EndpointPeersJSON() string {
	activeMu.Lock()
	defer activeMu.Unlock()
	if active == nil {
		return "[]"
	}
	return active.PeerSnapshotsJSON()
}
