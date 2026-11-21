package main

import (
	"bufio"
	"context"
	"io"
	"net"
	"net/netip"
	"testing"
	"time"
)

func TestParentDisconnectAndCrashEOFReturnToTunnelCleanup(t *testing.T) {
	for _, reason := range []string{"disconnect", "app-crash"} {
		t.Run(reason, func(t *testing.T) {
			reader, writer := io.Pipe()
			defer reader.Close()
			done := make(chan struct{})
			go func() { waitForShutdown(bufio.NewReader(reader), false, nil, func(string) {}); close(done) }()
			writer.Close()
			select {
			case <-done:
			case <-time.After(time.Second):
				t.Fatal("parent EOF did not reach tunnel teardown")
			}
		})
	}
}

func TestPresenceSocketCannotFallBackWhenTunnelInterfaceIsInvalid(t *testing.T) {
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	dialer := tunnelPresenceDialer(netip.IPv4Unspecified(), ^uint32(0))
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	c, err := dialer.DialContext(ctx, "tcp4", listener.Addr().String())
	if err == nil {
		c.Close()
		t.Fatal("presence socket silently fell back to physical routing")
	}
}
