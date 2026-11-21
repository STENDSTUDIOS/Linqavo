package linqavowg

import (
	"net"
	"net/netip"
	"testing"
)

func TestDestinationIsolation(t *testing.T) {
	for _, ip := range []string{"127.0.0.1", "10.13.37.1", "10.13.37.3", "192.168.1.1", "172.16.0.1", "169.254.1.1", "100.64.0.1", "224.0.0.1", "::1", "0.1.2.3"} {
		if publicDestination(netip.MustParseAddr(ip)) {
			t.Fatalf("private/special destination allowed: %s", ip)
		}
	}
	for _, ip := range []string{"1.1.1.1", "8.8.8.8"} {
		if !publicDestination(netip.MustParseAddr(ip)) {
			t.Fatalf("public destination blocked: %s", ip)
		}
	}
}
func TestFlowLimitsAndRevocation(t *testing.T) {
	var b flowBudget
	var leases []*flowLease
	for range maxClientFlows {
		l, err := b.reserve("10.13.37.2")
		if err != nil {
			t.Fatal(err)
		}
		leases = append(leases, l)
	}
	if _, err := b.reserve("10.13.37.2"); err == nil {
		t.Fatal("per-client limit bypass")
	}
	other, err := b.reserve("10.13.37.3")
	if err != nil {
		t.Fatal("another client was blocked")
	}
	defer other.release()
	a, c := net.Pipe()
	defer c.Close()
	if !leases[0].attach(a) {
		t.Fatal("attach")
	}
	b.revoke("10.13.37.2")
	if _, err = c.Write([]byte{1}); err == nil {
		t.Fatal("revoked flow survives")
	}
	d, e := net.Pipe()
	defer e.Close()
	if leases[1].attach(d) {
		t.Fatal("in-flight dial survived revocation")
	}
	for _, l := range leases {
		l.release()
		l.release()
	}
	if b.total != 1 {
		t.Fatalf("flow reservation leak: %d", b.total)
	}
}

func TestGlobalFlowBudgetIsBoundedAcrossClients(t *testing.T) {
	var budget flowBudget
	var leases []*flowLease
	for _, client := range []string{"10.13.37.2", "10.13.37.3", "10.13.37.4"} {
		for range maxClientFlows {
			lease, err := budget.reserve(client)
			if err != nil {
				t.Fatal(err)
			}
			leases = append(leases, lease)
		}
	}
	if _, err := budget.reserve("another-client"); err == nil {
		t.Fatal("global flow limit bypass")
	}
	for _, lease := range leases {
		lease.release()
	}
	if budget.total != 0 {
		t.Fatal("global reservation leak")
	}
}

func TestShutdownClosesTrackedSocketsAndRefusesLateDials(t *testing.T) {
	var budget flowBudget
	lease, err := budget.reserve("client")
	if err != nil {
		t.Fatal(err)
	}
	defer lease.release()
	late, err := budget.reserve("client")
	if err != nil {
		t.Fatal(err)
	}
	defer late.release()
	a, b := net.Pipe()
	defer b.Close()
	if !lease.attach(a) {
		t.Fatal("attach")
	}
	budget.close()
	budget.close()
	if _, err = b.Write([]byte{1}); err == nil {
		t.Fatal("socket survived shutdown")
	}
	c, d := net.Pipe()
	defer d.Close()
	if late.attach(c) {
		t.Fatal("late dial survived shutdown")
	}
	if _, err = budget.reserve("new-client"); err == nil {
		t.Fatal("new flow after shutdown")
	}
}
