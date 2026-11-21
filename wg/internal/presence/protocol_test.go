package presence

import (
	"context"
	"encoding/json"
	"io"
	"net"
	"os"
	"sync/atomic"
	"testing"
	"time"
)

func TestInitialControlFailureAlsoHasBoundedClientLiveness(t *testing.T) {
	events := make(chan string, 2)
	started := time.Now()
	client := StartObserved(func(ctx context.Context, _ string) (net.Conn, error) {
		<-ctx.Done()
		return nil, ctx.Err()
	}, func(event string) { events <- event })
	defer client.Stop()
	select {
	case event := <-events:
		if event != "unavailable" || time.Since(started) < OfflineAfter-time.Second {
			t.Fatalf("wrong initial liveness result: %s at %s", event, time.Since(started))
		}
		t.Logf("initial control unavailable: %s", time.Since(started))
	case <-time.After(OfflineAfter + time.Second):
		t.Fatal("never-confirmed presence stayed connected beyond the deadline")
	}
}

func TestServerEventsArriveWithoutWaitingForNextHeartbeat(t *testing.T) {
	for _, kind := range []byte{'S', 'X', 'R'} {
		t.Run(string(kind), func(t *testing.T) {
			ready := make(chan *Control, 1)
			events := make(chan string, 8)
			client := StartObserved(func(context.Context, string) (net.Conn, error) {
				a, b := net.Pipe()
				go ServeManaged(b, func() bool { return true }, func(bool) {}, func(c *Control) { ready <- c })
				return a, nil
			}, func(event string) { events <- event })
			defer client.Stop()
			control := <-ready
			for event := range events {
				if event == "back" {
					break
				}
			}
			if !control.Notify(kind) {
				t.Fatal("server event was not acknowledged")
			}
			select {
			case event := <-events:
				if event != map[byte]string{'S': "server-stopped", 'X': "client-disabled", 'R': "enrollment-revoked"}[kind] {
					t.Fatalf("wrong event: %s", event)
				}
			case <-time.After(time.Second):
				t.Fatal("push event waited for heartbeat")
			}
			if control.Notify(kind) {
				t.Fatal("duplicate event accepted")
			}
		})
	}
}

func TestStopCanPrecedeFirstHeartbeatAcknowledgement(t *testing.T) {
	events := make(chan string, 4)
	ack := make(chan byte, 1)
	client := StartObserved(func(ctx context.Context, _ string) (net.Conn, error) {
		a, b := net.Pipe()
		go func() {
			defer b.Close()
			b.SetDeadline(time.Now().Add(time.Second))
			var preface [len(Preface)]byte
			var ticket [TicketBytes]byte
			ticket[0] = 1
			if _, err := io.ReadFull(b, preface[:]); err != nil {
				return
			}
			if writeAll(b, append(preface[:], ticket[:]...)) != nil {
				return
			}
			if kind, err := readFrame(b, ticket); err != nil || kind != 'H' {
				return
			}
			if writeFrame(b, ticket, 'S') != nil {
				return
			}
			kind, _ := readFrame(b, ticket)
			ack <- kind
		}()
		return a, nil
	}, func(event string) { events <- event })
	defer client.Stop()
	select {
	case event := <-events:
		if event != "server-stopped" {
			t.Fatalf("expected authenticated Stop, got %q", event)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("client discarded authenticated Stop ahead of the first heartbeat ACK")
	}
	if kind := <-ack; kind != 's' {
		t.Fatalf("expected Stop ACK, got %q", kind)
	}
}

func TestLegacyClientGoodbyeRemainsSupported(t *testing.T) {
	a, b := net.Pipe()
	defer a.Close()
	ready := make(chan *Control, 1)
	go ServeManaged(b, func() bool { return true }, func(bool) {}, func(c *Control) { ready <- c })
	a.SetDeadline(time.Now().Add(time.Second))
	a.Write([]byte(LegacyPreface))
	greeting := make([]byte, len(LegacyPreface)+TicketBytes)
	if _, err := io.ReadFull(a, greeting); err != nil {
		t.Fatal(err)
	}
	var ticket [TicketBytes]byte
	copy(ticket[:], greeting[len(LegacyPreface):])
	if err := Exchange(a, ticket, 'H', time.Second); err != nil {
		t.Fatal(err)
	}
	if (<-ready).Notify('S') {
		t.Fatal("v2 event injected into legacy stream")
	}
	if err := Exchange(a, ticket, 'D', time.Second); err != nil {
		t.Fatal(err)
	}
}

func TestClientNormalDisconnectIsAcknowledgedAndBounded(t *testing.T) {
	ready, ended := make(chan struct{}, 1), make(chan struct{}, 1)
	client := Start(func(context.Context, string) (net.Conn, error) {
		a, b := net.Pipe()
		go Serve(b, func() bool {
			select {
			case ready <- struct{}{}:
			default:
			}
			return true
		}, func() { ended <- struct{}{} })
		return a, nil
	})
	defer client.Stop()
	select {
	case <-ready:
	case <-time.After(2 * time.Second):
		t.Fatal("no heartbeat")
	}
	started := time.Now()
	client.Stop()
	select {
	case <-ended:
	case <-time.After(100 * time.Millisecond):
		t.Fatal("disconnect was not delivered")
	}
	t.Logf("acknowledged normal disconnect: %s", time.Since(started))
	select {
	case <-client.done:
	default:
		t.Fatal("control worker survived stop")
	}
}

func TestUnresponsiveControlCannotHoldTunnelShutdown(t *testing.T) {
	ready := make(chan struct{})
	client := Start(func(context.Context, string) (net.Conn, error) {
		a, b := net.Pipe()
		go func() { defer b.Close(); close(ready); io.Copy(io.Discard, b) }()
		return a, nil
	})
	<-ready
	started := time.Now()
	client.Stop()
	if time.Since(started) > DisconnectWait+300*time.Millisecond {
		t.Fatal("shutdown exceeded bound")
	}
	select {
	case <-client.done:
	case <-time.After(time.Second):
		t.Fatal("blocked worker leaked")
	}
}

func TestMalformedControlCannotInstallAPresence(t *testing.T) {
	for _, preface := range []string{"RLYP0000", "INVALID!"} {
		t.Run(preface, func(t *testing.T) {
			a, b := net.Pipe()
			defer a.Close()
			var beats atomic.Int32
			done := make(chan struct{})
			go func() { Serve(b, func() bool { beats.Add(1); return true }, func() {}); close(done) }()
			a.SetDeadline(time.Now().Add(time.Second))
			a.Write([]byte(preface))
			<-done
			if beats.Load() != 0 {
				t.Fatal("invalid protocol installed presence")
			}
		})
	}
}

func TestSharedPresenceLimitsMatchBothNativeEnds(t *testing.T) {
	raw, err := os.ReadFile("../../../shared/peer-presence.json")
	if err != nil {
		t.Fatal(err)
	}
	var c struct {
		Address, Preface                                                                                                                                                                               string
		TicketBytes, FrameBytes, HeartbeatSeconds, ReplyTimeoutSeconds, ReconnectingAfterSeconds, OfflineAfterSeconds, DisconnectWaitMilliseconds, MaximumControlStreams, MaximumControlStreamsPerPeer int
	}
	if err = json.Unmarshal(raw, &c); err != nil {
		t.Fatal(err)
	}
	if c.Address != Address || c.Preface != Preface || c.TicketBytes != TicketBytes || c.FrameBytes != FrameBytes ||
		time.Duration(c.HeartbeatSeconds)*time.Second != HeartbeatInterval || time.Duration(c.ReplyTimeoutSeconds)*time.Second != ReplyTimeout ||
		time.Duration(c.ReconnectingAfterSeconds)*time.Second != ReconnectingAfter || time.Duration(c.OfflineAfterSeconds)*time.Second != OfflineAfter ||
		time.Duration(c.DisconnectWaitMilliseconds)*time.Millisecond != DisconnectWait || c.MaximumControlStreams != MaximumStreams || c.MaximumControlStreamsPerPeer != MaximumStreamsPerPeer {
		t.Fatal("presence implementation diverged from shared contract")
	}
}
