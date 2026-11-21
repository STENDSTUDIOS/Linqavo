package main

import (
	"testing"
	"time"
)

func TestForwardingAndControlAreIndependent(t *testing.T) {
	start := time.Now()
	now := start.Add(40 * time.Second)
	recent := now.Add(-time.Second)
	old := start
	for _, test := range []struct {
		control string
		reply   time.Time
		want    string
	}{
		{"back", recent, peerBackLine},
		{"unavailable", recent, "PRESENCE-DEGRADED"},
		{"back", old, "FORWARDING-UNAVAILABLE"},
		{"unavailable", old, "SERVER-UNAVAILABLE"},
		{"", time.Time{}, "SERVER-UNAVAILABLE"},
		{"server-stopped", recent, "SERVER-STOPPED"},
		{"client-disabled", recent, "CLIENT-DISABLED"},
		{"enrollment-revoked", recent, "ENROLLMENT-REVOKED"},
	} {
		if got := forwardingState(now, start, test.reply, test.control); got != test.want {
			t.Errorf("%s: got %s, want %s", test.control, got, test.want)
		}
	}
	if got := forwardingState(start.Add(30*time.Second), start, start, "unavailable"); got != "FORWARDING-UNAVAILABLE" {
		t.Fatalf("expired data cannot remain connected during initial control grace: %s", got)
	}
}
