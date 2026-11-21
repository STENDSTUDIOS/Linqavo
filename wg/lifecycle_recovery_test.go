package linqavowg

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"linqavo.local/wg/internal/presence"
	"golang.org/x/net/dns/dnsmessage"
	"golang.zx2c4.com/wireguard/tun/netstack"
	"io"
	"net"
	"testing"
	"time"
)

func TestServerStopNotifiesEncryptedClientsBeforeTeardown(t *testing.T) {
	f := newPresenceFixture(t)
	f.workers[0].Stop()
	events := make(chan string, 8)
	network := f.networks[0]
	f.workers[0] = presence.StartObserved(func(ctx context.Context, address string) (net.Conn, error) {
		return network.DialContext(ctx, "tcp", address)
	}, func(event string) { events <- event })
	for event := range events {
		if event == "back" {
			break
		}
	}
	started := time.Now()
	f.endpoint.Stop()
	select {
	case event := <-events:
		if event != "server-stopped" {
			t.Fatalf("unexpected server event: %s", event)
		}
	case <-time.After(time.Second):
		t.Fatal("server stop was not authenticated/delivered")
	}
	t.Logf("encrypted server Stop notification and teardown: %s", time.Since(started))
}

func TestUnacknowledgedEncryptedStopDoesNotDelayOtherPeers(t *testing.T) {
	f := newPresenceFixture(t)
	f.workers[0].Stop()
	conn, err := f.networks[0].DialContext(context.Background(), "tcp", presence.Address)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	if _, err = presence.Open(conn); err != nil {
		t.Fatal(err)
	}
	start := time.Now()
	result := f.endpoint.stop()
	if elapsed := time.Since(start); elapsed > 2*time.Second {
		t.Fatalf("missing Stop ACK delayed shutdown: %s", elapsed)
	}
	if result != "streams=3 acked=2" {
		t.Fatalf("expected two peers to ACK independently: %s", result)
	}
	conn.SetReadDeadline(time.Now().Add(time.Second))
	var frame [presence.FrameBytes]byte
	if _, err := io.ReadFull(conn, frame[:]); err != nil || frame[0] != 'S' {
		t.Fatalf("Stop did not reach the unacknowledging client: kind=%q err=%v", frame[0], err)
	}
}

func TestRevokedKeyRequiresFreshIdentityWhileServerAndOtherPeersContinue(t *testing.T) {
	f := newPresenceFixture(t)
	old := f.keys[0]
	keep := multiTCP(t, f.networks[1], echoServer(t))
	defer keep.Close()
	if err := f.endpoint.RemovePeer(old); err != nil {
		t.Fatal(err)
	}
	if f.endpoint.EnrollmentError(old) != "ERR_ENROLLMENT_REVOKED" {
		t.Fatal("revocation reason lost")
	}
	if _, err := f.endpoint.EnrollPeer(old, "reuse revoked"); err == nil {
		t.Fatal("revoked identity reused")
	}
	freshPrivate, freshPublic := keyPair(t)
	fresh := publicBase64(t, freshPublic)
	var address string
	var err error
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		address, err = f.endpoint.EnrollPeer(fresh, "fresh approval fixture")
		if err == nil {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	if err != nil {
		t.Fatal(err)
	}
	f.workers[0].Stop()
	f.clients[0].Close()
	f.keys[0] = fresh
	f.private[0] = freshPrivate
	f.clients[0], f.networks[0] = multiClient(t, freshPrivate, f.public, address, f.links[0].port())
	f.startWorker(0)
	f.waitState(t, 0, "connected", 5*time.Second)
	if err = multiExchange(keep, "other-session-preserved"); err != nil {
		t.Fatal(err)
	}
	for i := range 3 {
		testRecoveryTraffic(t, f, i)
	}
}

func testRecoveryTraffic(t *testing.T, f *presenceFixture, i int) {
	t.Helper()
	c := multiTCP(t, f.networks[i], echoServer(t))
	defer c.Close()
	if err := multiExchange(c, fmt.Sprintf("recovered-%d", i)); err != nil {
		t.Fatal(err)
	}
	udp, err := net.ListenPacket("udp", net.JoinHostPort(multiHost(t), "0"))
	if err != nil {
		t.Fatal(err)
	}
	defer udp.Close()
	go func() {
		b := make([]byte, 2048)
		for {
			n, a, err := udp.ReadFrom(b)
			if err != nil {
				return
			}
			udp.WriteTo(b[:n], a)
		}
	}()
	if b, err := udpExchange(f.networks[i], udp.LocalAddr().String(), []byte("UDP-DNS-framing")); err != nil || string(b) != "UDP-DNS-framing" {
		t.Fatalf("UDP recovery: %v", err)
	}
	dns, err := net.ListenPacket("udp", net.JoinHostPort(multiHost(t), "0"))
	if err != nil {
		t.Fatal(err)
	}
	defer dns.Close()
	dnsTCP, err := net.Listen("tcp", dns.LocalAddr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer dnsTCP.Close()
	answer := func(raw []byte) []byte {
		var query dnsmessage.Message
		if query.Unpack(raw) != nil {
			return nil
		}
		query.Response = true
		query.Answers = []dnsmessage.Resource{{Header: dnsmessage.ResourceHeader{Name: dnsmessage.MustNewName("recovery.test."), Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET, TTL: 1}, Body: &dnsmessage.AResource{A: [4]byte{203, 0, 113, 1}}}}
		out, _ := query.Pack()
		return out
	}
	go func() {
		b := make([]byte, 2048)
		for {
			n, a, err := dns.ReadFrom(b)
			if err != nil {
				return
			}
			dns.WriteTo(answer(b[:n]), a)
		}
	}()
	go func() {
		for {
			c, err := dnsTCP.Accept()
			if err != nil {
				return
			}
			go func() {
				defer c.Close()
				c.SetDeadline(time.Now().Add(3 * time.Second))
				var size [2]byte
				if _, err := io.ReadFull(c, size[:]); err != nil {
					return
				}
				raw := make([]byte, binary.BigEndian.Uint16(size[:]))
				if _, err := io.ReadFull(c, raw); err != nil {
					return
				}
				reply := answer(raw)
				binary.BigEndian.PutUint16(size[:], uint16(len(reply)))
				c.Write(append(size[:], reply...))
			}()
		}
	}()
	query := dnsmessage.Message{Header: dnsmessage.Header{ID: uint16(100 + i)}, Questions: []dnsmessage.Question{{Name: dnsmessage.MustNewName("recovery.test."), Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET}}}
	raw, _ := query.Pack()
	for _, exchange := range []func(*netstack.Net, string, []byte) ([]byte, error){udpExchange, tcpDNSExchange} {
		reply, err := exchange(f.networks[i], dns.LocalAddr().String(), raw)
		var parsed dnsmessage.Message
		if err != nil || parsed.Unpack(reply) != nil || !parsed.Response || parsed.ID != query.ID || len(parsed.Answers) != 1 {
			t.Fatalf("DNS recovery client %d: %v", i, err)
		}
	}
}

func TestPendingFlowCancellationCannotBlockEnableOrReuse(t *testing.T) {
	var budget flowBudget
	lease, err := budget.reserve("10.13.37.2")
	if err != nil {
		t.Fatal(err)
	}
	done := make(chan struct{})
	go func() { <-lease.ctx.Done(); lease.release(); close(done) }()
	budget.revoke("10.13.37.2")
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("pending dial reservation did not cancel")
	}
	if !budget.available("10.13.37.2") {
		t.Fatal("canceled reservation retained lease")
	}
}

func TestAuthenticatedServerLossIsObservedOnClient(t *testing.T) {
	f := newPresenceFixture(t)
	f.workers[0].Stop()
	events := make(chan string, 8)
	network := f.networks[0]
	f.workers[0] = presence.StartObserved(func(ctx context.Context, address string) (net.Conn, error) {
		return network.DialContext(ctx, "tcp", address)
	}, func(event string) { events <- event })
	for event := range events {
		if event == "back" {
			break
		}
	}
	started := time.Now()
	f.links[0].down.Store(true)
	select {
	case event := <-events:
		if event != "unavailable" {
			t.Fatalf("wrong client event: %s", event)
		}
	case <-time.After(presence.OfflineAfter + 3*time.Second):
		t.Fatal("client did not bound server loss")
	}
	t.Logf("client observes blackholed server: %s", time.Since(started))
	var peers []peerRecord
	json.Unmarshal([]byte(f.endpoint.PeerSnapshotsJSON()), &peers)
	if len(peers) != 3 {
		t.Fatal("liveness removed authorizations")
	}
}

func TestDisableEnableDrainsAndRestoresSameIdentityWithoutServerRestart(t *testing.T) {
	f := newPresenceFixture(t)
	stream := multiTCP(t, f.networks[0], echoServer(t))
	defer stream.Close()
	if err := multiExchange(stream, "before-disable"); err != nil {
		t.Fatal(err)
	}
	if err := f.endpoint.SetPeerActive(f.keys[0], false); err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(2 * time.Second)
	var err error
	for time.Now().Before(deadline) {
		err = f.endpoint.SetPeerActive(f.keys[0], true)
		if err == nil {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	if err != nil {
		t.Fatalf("enable did not drain: %v", err)
	}
	f.workers[0].Stop()
	f.clients[0].Close()
	f.clients[0], f.networks[0] = multiClient(t, f.private[0], f.public, "10.13.37.2/32", f.links[0].port())
	f.startWorker(0)
	f.waitState(t, 0, "connected", 5*time.Second)
	recovered := multiTCP(t, f.networks[0], echoServer(t))
	defer recovered.Close()
	if err = multiExchange(recovered, "after-enable"); err != nil {
		t.Fatal(err)
	}
	for i := range 3 {
		testRecoveryTraffic(t, f, i)
	}
}
