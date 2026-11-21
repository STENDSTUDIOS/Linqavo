// Package presence implements shared/peer-presence.json. It runs ONLY inside a
// WireGuard tunnel; it is not an authenticator for ordinary LAN TCP sockets.
package presence

import (
	"crypto/rand"
	"crypto/subtle"
	"errors"
	"io"
	"net"
	"sync"
	"time"
)

const Address = "10.13.37.1:47658"
const Port = 47658
const Preface = "RLYP0002"
const TicketBytes = 16
const FrameBytes = 1 + TicketBytes
const HeartbeatInterval = 10 * time.Second
const ReplyTimeout = 5 * time.Second
const ReconnectingAfter = 25 * time.Second
const OfflineAfter = 35 * time.Second
const DisconnectWait = time.Second
const MaximumStreams = 6
const MaximumStreamsPerPeer = 2

func writeAll(c net.Conn, b []byte) error {
	for len(b) > 0 {
		n, err := c.Write(b)
		if err != nil {
			return err
		}
		if n == 0 {
			return io.ErrShortWrite
		}
		b = b[n:]
	}
	return nil
}

// Serve validates a fresh stream ticket before any state mutation. The caller
// binds beat/end callbacks to the enrolled key and current connection generation.
func Serve(c net.Conn, beat func() bool, end func()) {
	ServeManaged(c, beat, func(bool) { end() }, nil)
}

func ServeManaged(c net.Conn, beat func() bool, end func(bool), ready func(*Control)) {
	defer c.Close()
	goodbye := false
	finish := sync.OnceFunc(func() { end(goodbye) })
	defer finish()
	c.SetDeadline(time.Now().Add(ReplyTimeout))
	preface := make([]byte, len(Preface))
	if _, err := io.ReadFull(c, preface); err != nil || (string(preface) != Preface && string(preface) != LegacyPreface) {
		return
	}
	var ticket [TicketBytes]byte
	if _, err := rand.Read(ticket[:]); err != nil {
		return
	}
	control := &Control{c: c, ticket: ticket, version2: string(preface) == Preface}
	if writeAll(c, append(preface, ticket[:]...)) != nil {
		return
	}
	if ready != nil {
		ready(control)
	}
	started := false
	for {
		var frame [FrameBytes]byte
		if _, err := io.ReadFull(c, frame[:]); err != nil {
			return
		}
		if subtle.ConstantTimeCompare(frame[1:], ticket[:]) != 1 {
			return
		}
		switch frame[0] {
		case 'H':
			if !beat() {
				return
			}
			started = true
			frame[0] = 'h'
		case 'D':
			if !started {
				return
			}
			goodbye = true
			finish() // offline before acknowledging; does not revoke authorization
			frame[0] = 'd'
		case 's', 'x', 'r':
			if started && control.acknowledge(frame[0]) {
				return
			}
			return
		default:
			return
		}
		if control.write(frame[0], ReplyTimeout) != nil || frame[0] == 'd' {
			return
		}
		c.SetReadDeadline(time.Now().Add(OfflineAfter))
	}
}

func writeFrame(c net.Conn, ticket [TicketBytes]byte, kind byte) error {
	var frame [FrameBytes]byte
	frame[0] = kind
	copy(frame[1:], ticket[:])
	return writeAll(c, frame[:])
}

func readFrame(c net.Conn, ticket [TicketBytes]byte) (byte, error) {
	var frame [FrameBytes]byte
	if _, err := io.ReadFull(c, frame[:]); err != nil {
		return 0, err
	}
	if subtle.ConstantTimeCompare(frame[1:], ticket[:]) != 1 {
		return 0, errors.New("invalid presence ticket")
	}
	return frame[0], nil
}

// Open authenticates the already encrypted stream's generation, not a LAN peer.
func Open(c net.Conn) ([TicketBytes]byte, error) {
	ticket, event, err := openWithin(c, ReplyTimeout)
	if event != "" {
		return ticket, errors.New(event)
	}
	return ticket, err
}

func openWithin(c net.Conn, timeout time.Duration) ([TicketBytes]byte, string, error) {
	var ticket [TicketBytes]byte
	deadline := time.Now().Add(timeout)
	c.SetDeadline(deadline)
	if err := writeAll(c, []byte(Preface)); err != nil {
		return ticket, "", err
	}
	var reply [len(Preface) + TicketBytes]byte
	if _, err := io.ReadFull(c, reply[:]); err != nil {
		return ticket, "", err
	}
	if string(reply[:len(Preface)]) != Preface {
		return ticket, "", errors.New("incompatible presence version")
	}
	copy(ticket[:], reply[len(Preface):])
	if err := writeFrame(c, ticket, 'H'); err != nil {
		return ticket, "", err
	}
	kind, err := readFrame(c, ticket)
	if err != nil || kind == 'h' {
		return ticket, "", err
	}
	if event := receiveEvent(c, ticket, kind); event != "" {
		return ticket, event, nil
	}
	return ticket, "", errors.New("invalid presence acknowledgement")
}

func receiveEvent(c net.Conn, ticket [TicketBytes]byte, kind byte) string {
	var event string
	switch kind {
	case 'S':
		event = "server-stopped"
	case 'X':
		event = "client-disabled"
	case 'R':
		event = "enrollment-revoked"
	default:
		return ""
	}
	c.SetWriteDeadline(time.Now().Add(ServerEventWait))
	writeFrame(c, ticket, kind+('a'-'A'))
	return event
}

func Exchange(c net.Conn, ticket [TicketBytes]byte, kind byte, timeout time.Duration) error {
	c.SetDeadline(time.Now().Add(timeout))
	var frame [FrameBytes]byte
	frame[0] = kind
	copy(frame[1:], ticket[:])
	if err := writeAll(c, frame[:]); err != nil {
		return err
	}
	if _, err := io.ReadFull(c, frame[:]); err != nil {
		return err
	}
	if frame[0] != kind+('a'-'A') || subtle.ConstantTimeCompare(frame[1:], ticket[:]) != 1 {
		return errors.New("invalid presence acknowledgement")
	}
	return nil
}
