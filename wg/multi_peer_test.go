package linqavowg

import (
	"context"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/netip"
	"strings"
	"sync"
	"testing"
	"time"

	"golang.org/x/net/dns/dnsmessage"
	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

func multiHost(t *testing.T) string {
	t.Helper()
	addresses, err := net.InterfaceAddrs()
	if err != nil {
		t.Fatal(err)
	}
	for _, address := range addresses {
		prefix, err := netip.ParsePrefix(address.String())
		if err == nil && prefix.Addr().Is4() && prefix.Addr().IsGlobalUnicast() && !prefix.Addr().IsLoopback() && !netip.MustParsePrefix("10.13.37.0/24").Contains(prefix.Addr()) {
			probe, err := net.Listen("tcp", net.JoinHostPort(prefix.Addr().String(), "0"))
			if err == nil {
				probe.Close()
				return prefix.Addr().String()
			}
		}
	}
	t.Fatal("requires a local non-tunnel IPv4 address for isolated echo fixtures")
	return ""
}
func multiClient(t *testing.T, private, server, address string, port int) (*device.Device, *netstack.Net) {
	t.Helper()
	tun, network, err := netstack.CreateNetTUN([]netip.Addr{netip.MustParsePrefix(address).Addr()}, nil, mtu)
	if err != nil {
		t.Fatal(err)
	}
	client := device.NewDevice(tun, conn.NewDefaultBind(), device.NewLogger(device.LogLevelError, "multi-test: "))
	if err = client.IpcSet(fmt.Sprintf("private_key=%s\npublic_key=%s\nendpoint=127.0.0.1:%d\nallowed_ip=0.0.0.0/0\npersistent_keepalive_interval=1\n", private, server, port)); err != nil {
		client.Close()
		t.Fatal(err)
	}
	if err = client.Up(); err != nil {
		client.Close()
		t.Fatal(err)
	}
	return client, network
}
func multiTCP(t *testing.T, network *netstack.Net, destination string) net.Conn {
	t.Helper()
	deadline := time.Now().Add(15 * time.Second)
	for time.Now().Before(deadline) {
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		c, err := network.DialContext(ctx, "tcp", destination)
		cancel()
		if err == nil {
			return c
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatal("real WireGuard TCP connection did not establish")
	return nil
}
func multiExchange(c net.Conn, text string) error {
	c.SetDeadline(time.Now().Add(3 * time.Second))
	if _, err := c.Write([]byte(text)); err != nil {
		return err
	}
	buffer := make([]byte, len(text))
	_, err := io.ReadFull(c, buffer)
	if err == nil && string(buffer) != text {
		return fmt.Errorf("wrong echo")
	}
	return err
}
func udpExchange(network *netstack.Net, destination string, query []byte) ([]byte, error) {
	c, err := network.Dial("udp", destination)
	if err != nil {
		return nil, err
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(3 * time.Second))
	if _, err = c.Write(query); err != nil {
		return nil, err
	}
	buffer := make([]byte, 2048)
	n, err := c.Read(buffer)
	return buffer[:n], err
}
func publicBase64(t *testing.T, key string) string {
	t.Helper()
	raw, err := hex.DecodeString(key)
	if err != nil {
		t.Fatal(err)
	}
	return base64.StdEncoding.EncodeToString(raw)
}

// A length-prefixed DNS exchange exercises a distinct full-tunnel TCP flow,
// including new socket creation after a client's transport reconnects.
func tcpDNSExchange(network *netstack.Net, destination string, query []byte) ([]byte, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	c, err := network.DialContext(ctx, "tcp", destination)
	if err != nil {
		return nil, err
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(3 * time.Second))
	packet := make([]byte, 2+len(query))
	binary.BigEndian.PutUint16(packet, uint16(len(query)))
	copy(packet[2:], query)
	if _, err = c.Write(packet); err != nil {
		return nil, err
	}
	if _, err = io.ReadFull(c, packet[:2]); err != nil {
		return nil, err
	}
	reply := make([]byte, binary.BigEndian.Uint16(packet[:2]))
	_, err = io.ReadFull(c, reply)
	return reply, err
}

// Three independent real wireguard-go devices carry TCP, UDP and DNS traffic
// concurrently. Fixtures are local and explicitly exempt from production ACLs;
// no request is sent to an external server and no OS VPN adapter is created.
func TestThreeIndependentTunnelsAndLivePeerLifecycle(t *testing.T) {
	host := multiHost(t)
	tcp, err := net.Listen("tcp", net.JoinHostPort(host, "0"))
	if err != nil {
		t.Fatal(err)
	}
	defer tcp.Close()
	go func() {
		for {
			c, err := tcp.Accept()
			if err != nil {
				return
			}
			go func() { defer c.Close(); io.Copy(c, c) }()
		}
	}()
	udp, err := net.ListenPacket("udp", net.JoinHostPort(host, "0"))
	if err != nil {
		t.Fatal(err)
	}
	defer udp.Close()
	go func() {
		b := make([]byte, 2048)
		for {
			n, from, err := udp.ReadFrom(b)
			if err != nil {
				return
			}
			udp.WriteTo(b[:n], from)
		}
	}()
	dns, err := net.ListenPacket("udp", net.JoinHostPort(host, "0"))
	if err != nil {
		t.Fatal(err)
	}
	defer dns.Close()
	dnsTCP, err := net.Listen("tcp", dns.LocalAddr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer dnsTCP.Close()
	answerDNS := func(raw []byte) []byte {
		var query dnsmessage.Message
		if query.Unpack(raw) != nil {
			return nil
		}
		query.Header.Response = true
		query.Answers = []dnsmessage.Resource{{Header: dnsmessage.ResourceHeader{Name: dnsmessage.MustNewName("relay.test."), Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET, TTL: 1}, Body: &dnsmessage.AResource{A: [4]byte{1, 2, 3, 4}}}}
		reply, _ := query.Pack()
		return reply
	}
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
				reply := answerDNS(raw)
				binary.BigEndian.PutUint16(size[:], uint16(len(reply)))
				c.Write(append(size[:], reply...))
			}()
		}
	}()
	go func() {
		b := make([]byte, 2048)
		for {
			n, from, err := dns.ReadFrom(b)
			if err != nil {
				return
			}
			reply := answerDNS(b[:n])
			if reply == nil {
				continue
			}
			dns.WriteTo(reply, from)
		}
	}()
	serverPrivate, serverPublic := keyPair(t)
	port := freeUDPPort(t)
	endpoint, err := Start(fmt.Sprintf("private_key=%s\nlisten_port=%d\n", serverPrivate, port))
	if err != nil {
		t.Fatal(err)
	}
	defer endpoint.Stop()
	endpoint.tun.allowPrivateForTest = true
	var clients [3]*device.Device
	var networks [3]*netstack.Net
	var streams [3]net.Conn
	var keys [3]string
	var private [3]string
	defer func() {
		for _, c := range streams {
			if c != nil {
				c.Close()
			}
		}
		for _, c := range clients {
			if c != nil {
				c.Close()
			}
		}
	}()
	for i := range clients {
		var public string
		private[i], public = keyPair(t)
		keys[i] = publicBase64(t, public)
		lease, err := endpoint.EnrollPeer(keys[i], fmt.Sprintf("client-%d", i))
		if err != nil {
			t.Fatal(err)
		}
		if lease != fmt.Sprintf("10.13.37.%d/32", i+2) {
			t.Fatalf("conflicting lease: %s", lease)
		}
		clients[i], networks[i] = multiClient(t, private[i], serverPublic, lease, port)
		streams[i] = multiTCP(t, networks[i], tcp.Addr().String())
		for j := 0; j <= i; j++ {
			if err = multiExchange(streams[j], fmt.Sprintf("preserved-%d-%d", i, j)); err != nil {
				t.Fatalf("adding a peer disrupted existing TCP: %v", err)
			}
		}
	}
	var workers sync.WaitGroup
	failures := make(chan error, 3)
	for i := range clients {
		workers.Add(1)
		go func(i int) {
			defer workers.Done()
			for step := 0; step < 5; step++ {
				message := fmt.Sprintf("client-%d-message-%d", i, step)
				if err := multiExchange(streams[i], message); err != nil {
					failures <- err
					return
				}
				reply, err := udpExchange(networks[i], udp.LocalAddr().String(), []byte(message))
				if err != nil || string(reply) != message {
					failures <- fmt.Errorf("UDP client %d: %v", i, err)
					return
				}
				query := dnsmessage.Message{Header: dnsmessage.Header{ID: uint16(i*10 + step)}, Questions: []dnsmessage.Question{{Name: dnsmessage.MustNewName("relay.test."), Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET}}}
				raw, _ := query.Pack()
				reply, err = udpExchange(networks[i], dns.LocalAddr().String(), raw)
				var answer dnsmessage.Message
				if err != nil || answer.Unpack(reply) != nil || !answer.Header.Response || answer.Header.ID != query.Header.ID || len(answer.Answers) != 1 {
					failures <- fmt.Errorf("DNS client %d: %v", i, err)
					return
				}
				reply, err = tcpDNSExchange(networks[i], dnsTCP.Addr().String(), raw)
				if err != nil || answer.Unpack(reply) != nil || !answer.Header.Response || answer.Header.ID != query.Header.ID || len(answer.Answers) != 1 {
					failures <- fmt.Errorf("TCP DNS client %d: %v", i, err)
					return
				}
			}
		}(i)
	}
	workers.Wait()
	close(failures)
	for err := range failures {
		t.Fatal(err)
	}
	var snapshot []peerRecord
	if err = json.Unmarshal([]byte(endpoint.PeerSnapshotsJSON()), &snapshot); err != nil {
		t.Fatal(err)
	}
	if len(snapshot) != 3 {
		t.Fatal("three approvals were not three registry peers")
	}
	for _, peer := range snapshot {
		if peer.LastHandshake == 0 || peer.BytesReceived == 0 || peer.BytesSent == 0 || peer.Status != "connected" {
			t.Fatalf("peer lacks real tunnel evidence: %+v", peer)
		}
	}
	_, fourth := keyPair(t)
	if _, err = endpoint.EnrollPeer(publicBase64(t, fourth), "fourth"); err == nil {
		t.Fatal("capacity exceeded")
	}
	if lease, err := endpoint.EnrollPeer(keys[0], "spoofed rename"); err != nil || lease != "10.13.37.2/32" {
		t.Fatal("idempotency failed")
	}
	if err = endpoint.SetCapacity(2); err == nil {
		t.Fatal("reducing capacity silently removed peers")
	}
	if err = endpoint.SetPeerActive(keys[1], false); err != nil {
		t.Fatal(err)
	}
	if _, err = endpoint.EnrollPeer(keys[1], "bypass disable"); err == nil {
		t.Fatal("enrollment reactivated a disabled peer")
	}
	streams[1].SetDeadline(time.Now().Add(300 * time.Millisecond))
	streams[1].Write([]byte("blocked"))
	if _, err = streams[1].Read(make([]byte, 7)); err == nil {
		t.Fatal("disabled peer still forwards")
	}
	for _, i := range []int{0, 2} {
		if err = multiExchange(streams[i], "other-peer-survives"); err != nil {
			t.Fatal(err)
		}
	}
	if err = endpoint.SetPeerActive(keys[1], true); err != nil {
		t.Fatal(err)
	}
	streams[1].Close()
	clients[1].Close()
	clients[1], networks[1] = multiClient(t, private[1], serverPublic, "10.13.37.3/32", port)
	streams[1] = multiTCP(t, networks[1], tcp.Addr().String())
	if err = multiExchange(streams[1], "reconnected-with-own-key"); err != nil {
		t.Fatal(err)
	}
	// A real UDP source-port change with unchanged client key must preserve peers.
	before, _ := clients[0].IpcGet()
	if err = clients[0].IpcSet("listen_port=0\n"); err != nil {
		t.Fatal(err)
	}
	after, _ := clients[0].IpcGet()
	portValue := func(status string) string {
		for _, line := range strings.Split(status, "\n") {
			if strings.HasPrefix(line, "listen_port=") {
				return line
			}
		}
		return ""
	}
	if portValue(before) == "" || portValue(before) == portValue(after) {
		t.Fatal("test did not cause an actual UDP source-port change")
	}
	for _, i := range []int{0, 1, 2} {
		if err = multiExchange(streams[i], "after-source-rebind"); err != nil {
			t.Fatal(err)
		}
		message := fmt.Sprintf("UDP-after-reconnect-and-rebind-%d", i)
		if reply, err := udpExchange(networks[i], udp.LocalAddr().String(), []byte(message)); err != nil || string(reply) != message {
			t.Fatalf("UDP after reconnect client %d: %v", i, err)
		}
		query := dnsmessage.Message{Header: dnsmessage.Header{ID: uint16(100 + i)}, Questions: []dnsmessage.Question{{Name: dnsmessage.MustNewName("relay.test."), Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET}}}
		raw, _ := query.Pack()
		for _, exchange := range []func(*netstack.Net, string, []byte) ([]byte, error){udpExchange, tcpDNSExchange} {
			reply, err := exchange(networks[i], dns.LocalAddr().String(), raw)
			var answer dnsmessage.Message
			if err != nil || answer.Unpack(reply) != nil || !answer.Response || answer.ID != query.ID || len(answer.Answers) != 1 {
				t.Fatalf("DNS after reconnect client %d: %v", i, err)
			}
		}
	}
	if err = endpoint.RemovePeer(keys[1]); err != nil {
		t.Fatal(err)
	}
	streams[1].SetDeadline(time.Now().Add(300 * time.Millisecond))
	streams[1].Write([]byte("revoked"))
	if _, err = streams[1].Read(make([]byte, 7)); err == nil {
		t.Fatal("revoked tunnel still forwards")
	}
	for _, i := range []int{0, 2} {
		if err = multiExchange(streams[i], "survives-revocation"); err != nil {
			t.Fatal(err)
		}
	}
	if _, err = endpoint.EnrollPeer(keys[1], "replayed revoked identity"); err == nil {
		t.Fatal("revoked key reenrolled")
	}
	replacementPrivate, replacementPublic := keyPair(t)
	replacementKey := publicBase64(t, replacementPublic)
	var lease string
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		lease, err = endpoint.EnrollPeer(replacementKey, "replacement")
		if err == nil {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	if err != nil || lease != "10.13.37.3/32" {
		t.Fatalf("lease not safely reused: %s %v", lease, err)
	}
	streams[1].Close()
	clients[1].Close()
	clients[1], networks[1] = multiClient(t, replacementPrivate, serverPublic, lease, port)
	streams[1] = multiTCP(t, networks[1], tcp.Addr().String())
	for _, i := range []int{0, 1, 2} {
		if err = multiExchange(streams[i], "after-individual-revoke"); err != nil {
			t.Fatal(err)
		}
	}
	endpoint.Stop()
	if endpoint.PeerSnapshotsJSON() != "[]" {
		t.Fatal("shutdown retained live peers")
	}
}

func TestPeerCannotSpoofAnotherAssignedTunnelAddress(t *testing.T) {
	host := multiHost(t)
	backend, err := net.ListenPacket("udp", net.JoinHostPort(host, "0"))
	if err != nil {
		t.Fatal(err)
	}
	defer backend.Close()
	serverPrivate, serverPublic := keyPair(t)
	private, public := keyPair(t)
	_, otherPublic := keyPair(t)
	port := freeUDPPort(t)
	endpoint, err := Start(fmt.Sprintf("private_key=%s\nlisten_port=%d\n", serverPrivate, port))
	if err != nil {
		t.Fatal(err)
	}
	defer endpoint.Stop()
	endpoint.tun.allowPrivateForTest = true
	key := publicBase64(t, public)
	if _, err = endpoint.EnrollPeer(key, "first"); err != nil {
		t.Fatal(err)
	}
	if _, err = endpoint.EnrollPeer(publicBase64(t, otherPublic), "second"); err != nil {
		t.Fatal(err)
	}
	spoof, network := multiClient(t, private, serverPublic, "10.13.37.3/32", port)
	socket, err := network.Dial("udp", backend.LocalAddr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer socket.Close()
	socket.Write([]byte("spoof"))
	deadline := time.Now().Add(5 * time.Second)
	handshaked := false
	for time.Now().Before(deadline) {
		var peers []peerRecord
		json.Unmarshal([]byte(endpoint.PeerSnapshotsJSON()), &peers)
		for _, peer := range peers {
			if peer.PublicKey == key && peer.LastHandshake > 0 {
				handshaked = true
			}
		}
		if handshaked {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	if !handshaked {
		spoof.Close()
		t.Fatal("negative spoof test never authenticated a real WireGuard key")
	}
	backend.SetReadDeadline(time.Now().Add(300 * time.Millisecond))
	if _, _, err = backend.ReadFrom(make([]byte, 32)); err == nil {
		spoof.Close()
		t.Fatal("another peer's source address reached backend")
	}
	spoof.Close()
	valid, validNetwork := multiClient(t, private, serverPublic, "10.13.37.2/32", port)
	defer valid.Close()
	validSocket, err := validNetwork.Dial("udp", backend.LocalAddr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer validSocket.Close()
	validSocket.Write([]byte("allowed"))
	backend.SetReadDeadline(time.Now().Add(5 * time.Second))
	buffer := make([]byte, 32)
	n, _, err := backend.ReadFrom(buffer)
	if err != nil || string(buffer[:n]) != "allowed" {
		t.Fatalf("positive control failed: %v", err)
	}
}

func TestConcurrentEnrollmentRespectsCapacityAndUniqueLeases(t *testing.T) {
	private, _ := keyPair(t)
	endpoint, err := Start(fmt.Sprintf("private_key=%s\nlisten_port=%d\n", private, freeUDPPort(t)))
	if err != nil {
		t.Fatal(err)
	}
	defer endpoint.Stop()
	if err = endpoint.SetCapacity(1); err != nil {
		t.Fatal(err)
	}
	_, public := keyPair(t)
	first := publicBase64(t, public)
	if _, err = endpoint.EnrollPeer(first, "first"); err != nil {
		t.Fatal(err)
	}
	_, second := keyPair(t)
	if _, err = endpoint.EnrollPeer(publicBase64(t, second), "second"); err == nil {
		t.Fatal("configured capacity 1 ignored")
	}
	if err = endpoint.SetCapacity(3); err != nil {
		t.Fatal(err)
	}
	var workers sync.WaitGroup
	addresses := make(chan string, 20)
	for range 20 {
		_, public := keyPair(t)
		key := publicBase64(t, public)
		workers.Add(1)
		go func() {
			defer workers.Done()
			if lease, err := endpoint.EnrollPeer(key, "concurrent"); err == nil {
				addresses <- lease
			}
		}()
	}
	workers.Wait()
	close(addresses)
	seen := map[string]bool{"10.13.37.2/32": true}
	for address := range addresses {
		if seen[address] {
			t.Fatal("duplicate live lease")
		}
		seen[address] = true
	}
	if len(seen) != 3 {
		t.Fatalf("capacity race: %v", seen)
	}
	var peers []peerRecord
	json.Unmarshal([]byte(endpoint.PeerSnapshotsJSON()), &peers)
	if len(peers) != 3 {
		t.Fatal("registry capacity drift")
	}
	if _, err = endpoint.EnrollPeer(first, "untrusted rename"); err != nil {
		t.Fatal(err)
	}
	json.Unmarshal([]byte(endpoint.PeerSnapshotsJSON()), &peers)
	for _, peer := range peers {
		if peer.PublicKey == first && peer.Name != "first" {
			t.Fatal("replayed enrollment renamed an identity")
		}
	}
}
