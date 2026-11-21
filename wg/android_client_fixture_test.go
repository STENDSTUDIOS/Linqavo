//go:build relay_android_client_fixture

package linqavowg

import (
	"crypto/tls"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"io"
	"net"
	"net/netip"
	"sync/atomic"
	"testing"
	"time"

	"linqavo.local/wg/internal/presence"
	"golang.org/x/net/dns/dnsmessage"
	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

// TestServeAndroidClientFixture serves the explicitly selected emulator
// journey. Its echo/DNS destinations live inside a local userspace stack.
// Run only with the opt-in tag and stop the process after instrumentation.
func TestServeAndroidClientFixture(t *testing.T) {
	raw, err := NewEnrollmentIdentity()
	if err != nil {
		t.Fatal(err)
	}
	var identity map[string]string
	if err = json.Unmarshal([]byte(raw), &identity); err != nil {
		t.Fatal(err)
	}
	toPEM := func(key, label string) []byte {
		der, err := base64.StdEncoding.DecodeString(identity[key])
		if err != nil {
			t.Fatal(err)
		}
		return pem.EncodeToMemory(&pem.Block{Type: label, Bytes: der})
	}
	certificate, err := tls.X509KeyPair(toPEM("certificate", "CERTIFICATE"), toPEM("privateKey", "PRIVATE KEY"))
	if err != nil {
		t.Fatal(err)
	}
	enrollment, err := tls.Listen("tcp4", "127.0.0.1:49057", &tls.Config{
		Certificates: []tls.Certificate{certificate}, MinVersion: tls.VersionTLS12,
	})
	if err != nil {
		t.Fatal(err)
	}
	defer enrollment.Close()
	resolver := netip.MustParseAddr("1.1.1.1")
	link, stack, err := netstack.CreateNetTUN([]netip.Addr{netip.MustParseAddr(tunnelAddress), resolver}, nil, mtu)
	if err != nil {
		t.Fatal(err)
	}
	wg := device.NewDevice(link, conn.NewDefaultBind(), device.NewLogger(device.LogLevelError, "fixture: "))
	defer wg.Close()
	private, public := keyPair(t)
	port := freeUDPPort(t)
	if err = wg.IpcSet(fmt.Sprintf("private_key=%s\nlisten_port=%d\n", private, port)); err != nil {
		t.Fatal(err)
	}
	if err = wg.Up(); err != nil {
		t.Fatal(err)
	}
	control, err := stack.ListenTCP(&net.TCPAddr{IP: net.ParseIP(tunnelAddress), Port: presence.Port})
	if err != nil {
		t.Fatal(err)
	}
	defer control.Close()
	echo, err := stack.ListenTCP(&net.TCPAddr{IP: resolver.AsSlice(), Port: 41820})
	if err != nil {
		t.Fatal(err)
	}
	defer echo.Close()
	var current atomic.Pointer[presence.Control]
	go fixtureTCP(control, func(c net.Conn) {
		var stream *presence.Control
		presence.ServeManaged(c, func() bool { current.Store(stream); return true },
			func(bool) { current.CompareAndSwap(stream, nil) }, func(ready *presence.Control) { stream = ready })
	})
	go fixtureTCP(echo, func(c net.Conn) { io.Copy(c, c) })
	var datagrams []net.PacketConn
	for _, number := range []int{53, 41820} {
		socket, err := stack.ListenUDP(&net.UDPAddr{IP: resolver.AsSlice(), Port: number})
		if err != nil {
			t.Fatal(err)
		}
		defer socket.Close()
		datagrams = append(datagrams, socket)
		go fixtureDatagrams(socket, number == 53)
	}
	action, err := stack.ListenTCP(&net.TCPAddr{IP: resolver.AsSlice(), Port: 41821})
	if err != nil {
		t.Fatal(err)
	}
	defer action.Close()
	go fixtureTCP(action, func(c net.Conn) {
		var command [1]byte
		if _, err := io.ReadFull(c, command[:]); err != nil {
			return
		}
		switch command[0] {
		case 'F':
			echo.Close()
			for _, socket := range datagrams {
				socket.Close()
			}
		case 'S':
			if !current.Load().Notify('S') {
				t.Error("emulator did not acknowledge encrypted Stop")
			}
			wg.Close()
		}
	})
	t.Logf("Emulator host 10.0.2.2 port 49057 pin %s", identity["fingerprint"])
	var clientKey string
	for {
		c, err := enrollment.Accept()
		if err != nil {
			t.Fatal(err)
		}
		func() {
			defer c.Close()
			c.SetDeadline(time.Now().Add(10 * time.Second))
			var req struct {
				V, Pair         int
				ClientPublicKey string
			}
			if json.NewDecoder(io.LimitReader(c, 4096)).Decode(&req) != nil || req.V != 2 || req.Pair != 1 {
				return
			}
			raw, err := base64.StdEncoding.DecodeString(req.ClientPublicKey)
			if err != nil || len(raw) != 32 {
				return
			}
			result := json.NewEncoder(c)
			if clientKey == "" {
				if err = wg.IpcSet("public_key=" + hex.EncodeToString(raw) + "\nallowed_ip=10.13.37.2/32\n"); err != nil {
					t.Error(err)
					return
				}
				clientKey = req.ClientPublicKey
			}
			if clientKey != req.ClientPublicKey {
				result.Encode(map[string]any{"v": 2, "error": "ERR_CAPACITY_REACHED"})
				return
			}
			result.Encode(map[string]any{"v": 2, "ok": 1, "host": "10.0.2.2", "port": port,
				"wg": map[string]any{"serverPublicKey": publicBase64(t, public), "clientAddress": "10.13.37.2/32",
					"allowedIps": "0.0.0.0/0", "endpointPort": port, "dns": resolver.String()}})
		}()
	}
}

func fixtureTCP(listener net.Listener, exchange func(net.Conn)) {
	for {
		socket, err := listener.Accept()
		if err != nil {
			return
		}
		go func() { defer socket.Close(); exchange(socket) }()
	}
}

func fixtureDatagrams(socket net.PacketConn, dns bool) {
	buffer := make([]byte, 512)
	for {
		n, source, err := socket.ReadFrom(buffer)
		if err != nil {
			return
		}
		data := buffer[:n]
		if dns {
			var message dnsmessage.Message
			if message.Unpack(data) != nil {
				continue
			}
			message.Header.Response = true
			message.Header.RecursionAvailable = true
			data, err = message.Pack()
			if err != nil {
				continue
			}
		}
		socket.WriteTo(data, source)
	}
}
