package linqavowg

import (
	"context"
	"encoding/json"
	"fmt"
	"linqavo.local/wg/internal/presence"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
	"io"
	"net"
	"testing"
	"time"
)

type presenceFixture struct {
	endpoint      *Endpoint
	public        string
	port          int
	keys, private [3]string
	clients       [3]*device.Device
	networks      [3]*netstack.Net
	workers       [3]*presence.Client
	links         [3]*unreliableLink
}

func newPresenceFixture(t *testing.T) *presenceFixture {
	t.Helper()
	private, public := keyPair(t)
	f := &presenceFixture{port: freeUDPPort(t), public: public}
	var err error
	f.endpoint, err = Start(fmt.Sprintf("private_key=%s\nlisten_port=%d\n", private, f.port))
	if err != nil {
		t.Fatal(err)
	}
	f.endpoint.tun.allowPrivateForTest = true
	t.Cleanup(func() {
		for _, w := range f.workers {
			if w != nil {
				w.Stop()
			}
		}
		for _, c := range f.clients {
			if c != nil {
				c.Close()
			}
		}
		f.endpoint.Stop()
	})
	for i := range 3 {
		var public string
		f.private[i], public = keyPair(t)
		f.keys[i] = publicBase64(t, public)
		lease, err := f.endpoint.EnrollPeer(f.keys[i], fmt.Sprintf("PC-%d", i))
		if err != nil {
			t.Fatal(err)
		}
		f.links[i] = newUnreliableLink(t, f.port)
		f.clients[i], f.networks[i] = multiClient(t, f.private[i], f.public, lease, f.links[i].port())
		// Match Windows' keepalive, rather than the old fast fixture's one second.
		if err = f.clients[i].IpcSet("public_key=" + f.public + "\npersistent_keepalive_interval=25\n"); err != nil {
			t.Fatal(err)
		}
		f.startWorker(i)
	}
	f.waitState(t, 0, "connected", 5*time.Second)
	f.waitState(t, 1, "connected", 5*time.Second)
	f.waitState(t, 2, "connected", 5*time.Second)
	return f
}
func (f *presenceFixture) startWorker(i int) {
	network := f.networks[i]
	f.workers[i] = presence.Start(func(ctx context.Context, address string) (net.Conn, error) {
		return network.DialContext(ctx, "tcp", address)
	})
}
func (f *presenceFixture) snapshot(t *testing.T, i int) peerRecord {
	t.Helper()
	var peers []peerRecord
	if err := json.Unmarshal([]byte(f.endpoint.PeerSnapshotsJSON()), &peers); err != nil {
		t.Fatal(err)
	}
	for _, p := range peers {
		if p.PublicKey == f.keys[i] {
			return p
		}
	}
	t.Fatal("authorized record disappeared")
	return peerRecord{}
}
func (f *presenceFixture) waitState(t *testing.T, i int, state string, limit time.Duration) time.Duration {
	t.Helper()
	start := time.Now()
	for time.Since(start) < limit {
		p := f.snapshot(t, i)
		f.endpoint.mu.Lock()
		managed := f.endpoint.peers[f.keys[i]].managedPresence
		f.endpoint.mu.Unlock()
		if p.Status == state && managed {
			return time.Since(start)
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("PC-%d did not become %s: %+v", i, state, f.snapshot(t, i))
	return 0
}

func TestPresenceNormalDisconnectReconnectAndIndependentTunnels(t *testing.T) {
	f := newPresenceFixture(t)
	destination := echoServer(t)
	var streams [3]net.Conn
	for i := range 3 {
		streams[i] = multiTCP(t, f.networks[i], destination)
		defer streams[i].Close()
	}
	for i := range 3 {
		if err := multiExchange(streams[i], "initial"); err != nil {
			t.Fatal(err)
		}
	}
	started := time.Now()
	poll := time.NewTicker(time.Second)
	defer poll.Stop()
	f.workers[0].Stop()
	f.waitState(t, 0, "offline", time.Second)
	t.Logf("normal disconnect native detection including acknowledged goodbye: %s", time.Since(started))
	<-poll.C
	if f.snapshot(t, 0).Status != "offline" {
		t.Fatal("one-second Android polling simulation saw stale connected")
	}
	t.Logf("normal disconnect observed at Android polling cadence (host simulation): %s", time.Since(started))
	// Late WireGuard keepalives/counters must not undo the explicit goodbye.
	p := f.snapshot(t, 0)
	if !p.Active || p.Address != "10.13.37.2/32" {
		t.Fatal("disconnect revoked authorization")
	}
	_, fourth := keyPair(t)
	if _, err := f.endpoint.EnrollPeer(publicBase64(t, fourth), "fourth"); err == nil {
		t.Fatal("offline identity freed an authorization slot")
	}
	f.clients[0].Close()
	if err := multiExchange(streams[0], "must-not-forward"); err == nil {
		t.Fatal("closed client still forwards")
	}
	for _, i := range []int{1, 2} {
		if err := multiExchange(streams[i], "survives-other-disconnect"); err != nil {
			t.Fatal(err)
		}
	}
	// Reuse the same identity/address without calling EnrollPeer or any approval.
	f.clients[0], f.networks[0] = multiClient(t, f.private[0], f.public, p.Address, f.links[0].port())
	f.startWorker(0)
	f.waitState(t, 0, "connected", 5*time.Second)
	replacement := multiTCP(t, f.networks[0], destination)
	defer replacement.Close()
	if err := multiExchange(replacement, "reconnect-without-repairing"); err != nil {
		t.Fatal(err)
	}
	f.endpoint.Stop()
	if f.endpoint.PeerSnapshotsJSON() != "[]" {
		t.Fatal("stopped server retained presence")
	}
}

func TestControlClosureDoesNotHideEncryptedForwarding(t *testing.T) {
	f := newPresenceFixture(t)
	stream := multiTCP(t, f.networks[0], echoServer(t))
	defer stream.Close()
	f.endpoint.mu.Lock()
	control := f.endpoint.peers[f.keys[0]].presence.conn
	f.endpoint.mu.Unlock()
	control.Close() // close control TCP only, without a graceful goodbye
	f.waitState(t, 0, "offline", time.Second)
	if err := multiExchange(stream, "forwarding-after-control-closed"); err != nil {
		t.Fatal(err)
	}
	if peer := f.snapshot(t, 0); peer.Status != "degraded" {
		t.Fatalf("encrypted TCP echo succeeded with control closed, but peer status is %q", peer.Status)
	}
}

func TestCrashAndNetworkLossExpireWhileHealthyIdleClientStaysOnline(t *testing.T) {
	f := newPresenceFixture(t)
	f.endpoint.mu.Lock()
	last := f.endpoint.peers[f.keys[0]].presence.seen
	f.endpoint.mu.Unlock()
	started := time.Now()
	f.clients[0].Close()        // abrupt process loss: no goodbye or TCP shutdown
	f.links[1].down.Store(true) // whole encrypted local transport disappears
	f.waitState(t, 0, "reconnecting", presence.ReconnectingAfter+2*time.Second)
	f.waitState(t, 1, "reconnecting", time.Second)
	f.waitState(t, 0, "offline", presence.OfflineAfter-presence.ReconnectingAfter+2*time.Second)
	f.waitState(t, 1, "offline", time.Second)
	t.Logf("unexpected disconnect: since last proof=%s, since crash=%s", time.Since(last), time.Since(started))
	if p := f.snapshot(t, 2); p.Status != "connected" {
		t.Fatalf("healthy idle peer expired: %+v", p)
	}
	for i := range 3 {
		if !f.snapshot(t, i).Active {
			t.Fatal("liveness loss changed authorization")
		}
	}
	f.links[1].down.Store(false)
	f.waitState(t, 1, "connected", 20*time.Second)
}

// Keep the old stream open after replacement to exercise a delayed goodbye and
// its close callback, rather than relying only on TCP to discard duplicates.
type delayedClose struct{ net.Conn }

func (delayedClose) Close() error { return nil }
func TestSupersededAndReplayedDisconnectCannotOfflineCurrentGeneration(t *testing.T) {
	private, _ := keyPair(t)
	_, pub := keyPair(t)
	e, err := Start(fmt.Sprintf("private_key=%s\nlisten_port=%d\n", private, freeUDPPort(t)))
	if err != nil {
		t.Fatal(err)
	}
	defer e.Stop()
	key := publicBase64(t, pub)
	e.EnrollPeer(key, "PC")
	old, oldServer := net.Pipe()
	defer old.Close()
	defer oldServer.Close()
	go e.servePresence("10.13.37.2", delayedClose{oldServer})
	ticket, err := presence.Open(old)
	if err != nil {
		t.Fatal(err)
	}
	current, server := net.Pipe()
	defer current.Close()
	go e.servePresence("10.13.37.2", server)
	currentTicket, err := presence.Open(current)
	if err != nil {
		t.Fatal(err)
	}
	if ticket == currentTicket {
		t.Fatal("stream ticket reused")
	}
	if err = presence.Exchange(old, ticket, 'D', time.Second); err != nil {
		t.Fatal(err)
	}
	// Duplicate old disconnect fails/ends only its old stream.
	old.SetDeadline(time.Now().Add(50 * time.Millisecond))
	old.Write(append([]byte{'D'}, ticket[:]...))
	replay, replayServer := net.Pipe()
	defer replay.Close()
	go e.servePresence("10.13.37.2", replayServer)
	replay.SetDeadline(time.Now().Add(time.Second))
	replay.Write([]byte(presence.Preface))
	greeting := make([]byte, len(presence.Preface)+presence.TicketBytes)
	io.ReadFull(replay, greeting)
	replay.Write(append([]byte{'D'}, ticket[:]...)) // stale ticket on a fresh stream
	if err = presence.Exchange(current, currentTicket, 'H', time.Second); err != nil {
		t.Fatal(err)
	}
	var peers []peerRecord
	json.Unmarshal([]byte(e.PeerSnapshotsJSON()), &peers)
	if len(peers) != 1 || peers[0].Status != "connected" {
		t.Fatal("stale disconnect changed the current generation")
	}
	e.Stop()
	newPrivate, _ := keyPair(t)
	fresh, err := Start(fmt.Sprintf("private_key=%s\nlisten_port=%d\n", newPrivate, freeUDPPort(t)))
	if err != nil {
		t.Fatal(err)
	}
	defer fresh.Stop()
	if fresh.PeerSnapshotsJSON() != "[]" {
		t.Fatal("restart restored stale live state")
	}
	fresh.EnrollPeer(key, "fresh approved record")
	stale, staleServer := net.Pipe()
	defer stale.Close()
	go fresh.servePresence("10.13.37.2", staleServer)
	stale.SetDeadline(time.Now().Add(time.Second))
	stale.Write([]byte(presence.Preface))
	newGreeting := make([]byte, len(presence.Preface)+presence.TicketBytes)
	io.ReadFull(stale, newGreeting)
	stale.Write(append([]byte{'D'}, currentTicket[:]...))
	json.Unmarshal([]byte(fresh.PeerSnapshotsJSON()), &peers)
	if len(peers) != 1 || peers[0].Status != "authorized" || !peers[0].Active {
		t.Fatal("old server ticket changed fresh authorization")
	}
}

func TestControlAdmissionUsesExistingQuotaAndIsBoundedPerPeer(t *testing.T) {
	f := newPresenceFixture(t)
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	pending, err := f.networks[0].DialContext(ctx, "tcp", presence.Address)
	if err != nil {
		t.Fatal(err)
	}
	defer pending.Close()
	// One established heartbeat and one bounded pending replacement per peer.
	extra, err := f.networks[0].DialContext(ctx, "tcp", presence.Address)
	if err == nil {
		extra.Close()
		t.Fatal("third control stream admitted for the same peer")
	}
	f.endpoint.tun.budget.mu.Lock()
	total := f.endpoint.tun.budget.total
	f.endpoint.tun.budget.mu.Unlock()
	if total < 4 || total > maxGlobalFlows {
		t.Fatalf("control streams bypassed ordinary flow quota: %d", total)
	}
	for _, i := range []int{1, 2} {
		if f.snapshot(t, i).Status != "connected" {
			t.Fatal("one peer exhausted another peer's control capacity")
		}
	}
}

func TestHistoricalHandshakeCannotOverridePresenceLease(t *testing.T) {
	now := time.Now()
	p := peerRecord{Active: true, LastHandshake: now.Add(24 * time.Hour).Unix()}
	if state := peerPresenceState(&p, now); state != "unknown" {
		t.Fatal("historical/future wall timestamp was treated as online")
	}
	p.lastInbound = now
	if state := peerPresenceState(&p, now.Add(34*time.Second)); state != "connected" {
		t.Fatal("idle inbound keepalive lease lost")
	}
	if state := peerPresenceState(&p, now.Add(35*time.Second)); state != "offline" {
		t.Fatal("lease did not expire")
	}
	p.managedPresence = true
	if state := peerPresenceState(&p, now); state != "offline" {
		t.Fatal("traffic revived an explicitly disconnected session")
	}
}

func TestDelayedFirstHeartbeatFromOlderStreamCannotReplaceCurrentPresence(t *testing.T) {
	private, _ := keyPair(t)
	_, pub := keyPair(t)
	e, err := Start(fmt.Sprintf("private_key=%s\nlisten_port=%d\n", private, freeUDPPort(t)))
	if err != nil {
		t.Fatal(err)
	}
	defer e.Stop()
	e.EnrollPeer(publicBase64(t, pub), "PC")
	older, oldServer := net.Pipe()
	defer older.Close()
	go e.servePresence("10.13.37.2", oldServer)
	older.SetDeadline(time.Now().Add(time.Second))
	older.Write([]byte(presence.Preface))
	greeting := make([]byte, len(presence.Preface)+presence.TicketBytes)
	if _, err = io.ReadFull(older, greeting); err != nil {
		t.Fatal(err)
	}
	var oldTicket [presence.TicketBytes]byte
	copy(oldTicket[:], greeting[len(presence.Preface):])
	current, server := net.Pipe()
	defer current.Close()
	go e.servePresence("10.13.37.2", server)
	ticket, err := presence.Open(current)
	if err != nil {
		t.Fatal(err)
	}
	if presence.Exchange(older, oldTicket, 'H', time.Second) == nil {
		t.Fatal("delayed old opening replaced newer connection")
	}
	if err = presence.Exchange(current, ticket, 'H', time.Second); err != nil {
		t.Fatal(err)
	}
	var peers []peerRecord
	json.Unmarshal([]byte(e.PeerSnapshotsJSON()), &peers)
	if peers[0].Status != "connected" {
		t.Fatal("old stream completion changed current presence")
	}
}

func TestPresenceControlCannotSpoofAnotherPeersSourceAddress(t *testing.T) {
	serverPrivate, serverPublic := keyPair(t)
	private, public := keyPair(t)
	port := freeUDPPort(t)
	e, err := Start(fmt.Sprintf("private_key=%s\nlisten_port=%d\n", serverPrivate, port))
	if err != nil {
		t.Fatal(err)
	}
	defer e.Stop()
	e.EnrollPeer(publicBase64(t, public), "first")
	_, other := keyPair(t)
	e.EnrollPeer(publicBase64(t, other), "second")
	spoof, network := multiClient(t, private, serverPublic, "10.13.37.3/32", port)
	defer spoof.Close()
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	c, err := network.DialContext(ctx, "tcp", presence.Address)
	if err == nil {
		c.Close()
		t.Fatal("spoofed control source was accepted")
	}
	var peers []peerRecord
	json.Unmarshal([]byte(e.PeerSnapshotsJSON()), &peers)
	for _, p := range peers {
		if p.PublicKey == publicBase64(t, other) && p.Status == "connected" {
			t.Fatal("spoofed source made the other peer connected")
		}
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	for _, p := range e.peers {
		if p.managedPresence || p.presence != nil {
			t.Fatal("spoofed control stream installed")
		}
	}
}
