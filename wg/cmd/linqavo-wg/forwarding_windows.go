package main

import (
	"context"
	"crypto/rand"
	"encoding/binary"
	"net"
	"sync/atomic"
	"time"

	"linqavo.local/wg/internal/forwarding"
	"linqavo.local/wg/internal/presence"
	"golang.org/x/net/dns/dnsmessage"
	"golang.zx2c4.com/wireguard/tun"
)

type observedTunnel struct {
	tun.Device
	start time.Time
	reply atomic.Int64
	rx    atomic.Int64
	tx    atomic.Int64
}

func (t *observedTunnel) Write(bufs [][]byte, offset int) (int, error) {
	n, err := t.Device.Write(bufs, offset)
	for _, buf := range bufs[:n] {
		if forwarding.IPv4Reply(buf[offset:]) {
			t.reply.Store(int64(time.Since(t.start)))
			t.rx.Add(int64(len(buf) - offset))
		}
	}
	return n, err
}

func (t *observedTunnel) Read(bufs [][]byte, sizes []int, offset int) (int, error) {
	n, err := t.Device.Read(bufs, sizes, offset)
	for i := range n {
		if forwarding.IPv4Data(bufs[i][offset:offset+sizes[i]], 16) {
			t.tx.Add(int64(sizes[i]))
		}
	}
	return n, err
}

func (t *observedTunnel) lastReply() time.Time {
	if elapsed := t.reply.Load(); elapsed != 0 {
		return t.start.Add(time.Duration(elapsed))
	}
	return time.Time{}
}

// Use the configured tunnel resolver to obtain evidence while applications
// are idle. Source and interface are fixed before the UDP socket can send.
func probeResolver(ctx context.Context, dialer net.Dialer, resolver string) bool {
	var id [2]byte
	if _, err := rand.Read(id[:]); err != nil {
		return false
	}
	question := dnsmessage.Question{Name: dnsmessage.MustNewName("."), Type: dnsmessage.TypeNS, Class: dnsmessage.ClassINET}
	query := dnsmessage.Message{
		Header:    dnsmessage.Header{ID: binary.BigEndian.Uint16(id[:]), RecursionDesired: true},
		Questions: []dnsmessage.Question{question},
	}
	packet, err := query.Pack()
	if err != nil {
		return false
	}
	socket, err := dialer.DialContext(ctx, "udp4", net.JoinHostPort(resolver, "53"))
	if err != nil {
		return false
	}
	defer socket.Close()
	socket.SetDeadline(time.Now().Add(3 * time.Second))
	if _, err := socket.Write(packet); err != nil {
		return false
	}
	buf := make([]byte, 4096)
	n, err := socket.Read(buf)
	var reply dnsmessage.Message
	return err == nil && reply.Unpack(buf[:n]) == nil && reply.Response &&
		reply.ID == query.ID && len(reply.Questions) == 1 && reply.Questions[0] == question
}

func forwardingState(now, started, reply time.Time, control string) string {
	switch control {
	case "server-stopped":
		return "SERVER-STOPPED"
	case "client-disabled":
		return "CLIENT-DISABLED"
	case "enrollment-revoked":
		return "ENROLLMENT-REVOKED"
	}
	if !reply.IsZero() && now.Sub(reply) < forwarding.FreshFor {
		if control == "back" {
			return peerBackLine
		}
		return "PRESENCE-DEGRADED"
	}
	if control == "back" {
		return "FORWARDING-UNAVAILABLE"
	}
	if now.Sub(started) >= presence.OfflineAfter {
		return "SERVER-UNAVAILABLE"
	}
	if !reply.IsZero() {
		return "FORWARDING-UNAVAILABLE"
	}
	return ""
}

func controlForRecovery(line, control string) string {
	if line == "SERVER-UNAVAILABLE" {
		return "unavailable"
	}
	return control
}
