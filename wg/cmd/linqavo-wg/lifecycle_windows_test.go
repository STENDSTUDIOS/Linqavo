package main

import (
	"context"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	linqavowg "linqavo.local/wg"
	"linqavo.local/wg/internal/presence"
	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
	"net"
	"net/netip"
	"testing"
	"time"
)

func TestHeldClientRekeysAfterDisableAndRecoversSameIdentity(t *testing.T) {
	identity := func() (string, string) {
		raw, err := linqavowg.NewClientIdentity()
		if err != nil {
			t.Fatal(err)
		}
		var v struct{ PrivateKey, PublicKey string }
		json.Unmarshal([]byte(raw), &v)
		return v.PrivateKey, v.PublicKey
	}
	toHex := func(key string) string {
		raw, _ := base64.StdEncoding.DecodeString(key)
		return hex.EncodeToString(raw)
	}
	serverPrivate, serverPublic := identity()
	private, public := identity()
	socket, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := socket.LocalAddr().(*net.UDPAddr).Port
	socket.Close()
	server, err := linqavowg.Start(fmt.Sprintf("private_key=%s\nlisten_port=%d\n", toHex(serverPrivate), port))
	if err != nil {
		t.Fatal(err)
	}
	defer server.Stop()
	lease, err := server.EnrollPeer(public, "held test")
	if err != nil {
		t.Fatal(err)
	}
	tun, network, err := netstack.CreateNetTUN([]netip.Addr{netip.MustParsePrefix(lease).Addr()}, nil, 1420)
	if err != nil {
		t.Fatal(err)
	}
	dev := device.NewDevice(tun, conn.NewDefaultBind(), device.NewLogger(device.LogLevelError, "held-test: "))
	defer dev.Close()
	serverHex := toHex(serverPublic)
	if err = dev.IpcSet(fmt.Sprintf("private_key=%s\npublic_key=%s\nendpoint=127.0.0.1:%d\nallowed_ip=0.0.0.0/0\npersistent_keepalive_interval=25\n", toHex(private), serverHex, port)); err != nil {
		t.Fatal(err)
	}
	if err = dev.Up(); err != nil {
		t.Fatal(err)
	}
	events := make(chan string, 16)
	resetErrors := make(chan error, 1)
	client := presence.StartObserved(func(ctx context.Context, address string) (net.Conn, error) {
		return network.DialContext(ctx, "tcp", address)
	}, func(event string) {
		if event == "client-disabled" {
			resetErrors <- recoverHeldPeer(dev, serverHex, event)
		}
		events <- event
	})
	defer client.Stop()
	await := func(want string) {
		deadline := time.After(presence.OfflineAfter + presence.ReplyTimeout)
		for {
			select {
			case event := <-events:
				if event == want {
					return
				}
			case <-deadline:
				t.Fatalf("missing %s", want)
			}
		}
	}
	await("back")
	if err = server.SetPeerActive(public, false); err != nil {
		t.Fatal(err)
	}
	await("client-disabled")
	if err = <-resetErrors; err != nil {
		t.Fatal(err)
	}
	if err = server.SetPeerActive(public, true); err != nil {
		t.Fatal(err)
	}
	await("back")
	if server.EnrollmentError(public) != "" {
		t.Fatal("same authorized identity was lost")
	}
}

func TestPeerResetCarriesOnlyPublicRoutingConfiguration(t *testing.T) {
	config, err := publicPeerConfiguration("private_key=secret\npublic_key=selected\npreshared_key=secret\nendpoint=127.0.0.1:1\nallowed_ip=0.0.0.0/0\npersistent_keepalive_interval=25\n", "selected")
	if err != nil {
		t.Fatal(err)
	}
	if config != "public_key=selected\nendpoint=127.0.0.1:1\nallowed_ip=0.0.0.0/0\npersistent_keepalive_interval=25\n" {
		t.Fatal("peer reset copied secret or unrelated config")
	}
}
