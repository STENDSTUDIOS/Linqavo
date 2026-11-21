package linqavowg

import (
	"context"
	"errors"
	"net"
	"net/netip"
	"sync"
	"time"
)

const maxGlobalFlows = 384
const maxClientFlows = 128

var specialRanges = []netip.Prefix{
	netip.MustParsePrefix("100.64.0.0/10"), netip.MustParsePrefix("192.0.0.0/24"),
	netip.MustParsePrefix("198.18.0.0/15"), netip.MustParsePrefix("0.0.0.0/8"),
}

func publicDestination(ip netip.Addr) bool {
	if !ip.Is4() || !ip.IsGlobalUnicast() || ip.IsPrivate() || ip.IsLoopback() || ip.IsLinkLocalUnicast() {
		return false
	}
	for _, prefix := range specialRanges {
		if prefix.Contains(ip) {
			return false
		}
	}
	return true
}

func allowedDestination(address string) bool {
	host, port, err := net.SplitHostPort(address)
	if err != nil || port == "0" {
		return false
	}
	ip, err := netip.ParseAddr(host)
	if err != nil || !publicDestination(ip) {
		return false
	}
	local, err := net.InterfaceAddrs()
	// Android may deny netlink interface enumeration. The mandatory special/
	// private address policy above still applies; public self-address exclusion
	// is best effort until platform-provided address updates are available.
	if err != nil {
		return true
	}
	for _, entry := range local {
		if prefix, err := netip.ParsePrefix(entry.String()); err == nil && prefix.Addr().Unmap() == ip {
			return false
		}
	}
	return true
}

// Reservations include in-progress dials, so opening many incomplete flows
// cannot evade the global/per-client limits. Generation prevents a dial from
// surviving revocation or attaching itself to a later reuse of the same lease.
type flowBudget struct {
	closed      bool
	mu          sync.Mutex
	total       int
	clients     map[string]int
	blocked     map[string]bool
	generations map[string]uint64
	sockets     map[string]map[net.Conn]bool
	leases      map[*flowLease]bool
	forwarded   map[string]time.Time
}

// Write only records payload copied from a real egress socket into the tunnel.
// A canceled or released flow cannot leave evidence for a reused lease.
type forwardedReplies struct {
	net.Conn
	lease *flowLease
}

func (c forwardedReplies) Write(packet []byte) (int, error) {
	n, err := c.Conn.Write(packet)
	if n > 0 {
		l := c.lease
		b := l.budget
		b.mu.Lock()
		if b.leases[l] && l.ctx.Err() == nil {
			if b.forwarded == nil {
				b.forwarded = make(map[string]time.Time)
			}
			b.forwarded[l.client] = time.Now()
		}
		b.mu.Unlock()
	}
	return n, err
}

func (b *flowBudget) lastForwarded(client string) time.Time {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.forwarded[client]
}

type flowLease struct {
	budget     *flowBudget
	client     string
	generation uint64
	once       sync.Once
	socket     net.Conn
	ctx        context.Context
	cancel     context.CancelFunc
}

func (b *flowBudget) reserve(client string) (*flowLease, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.clients == nil {
		b.clients = map[string]int{}
		b.blocked = map[string]bool{}
		b.generations = map[string]uint64{}
		b.sockets = map[string]map[net.Conn]bool{}
		b.leases = map[*flowLease]bool{}
	}
	if b.closed || b.blocked[client] {
		return nil, errors.New("peer disabled")
	}
	if b.total >= maxGlobalFlows || b.clients[client] >= maxClientFlows {
		return nil, errors.New("flow limit")
	}
	b.total++
	b.clients[client]++
	ctx, cancel := context.WithCancel(context.Background())
	lease := &flowLease{budget: b, client: client, generation: b.generations[client], ctx: ctx, cancel: cancel}
	b.leases[lease] = true
	return lease, nil
}
func (l *flowLease) attach(conn net.Conn) bool {
	b := l.budget
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.closed || b.generations[l.client] != l.generation || l.ctx.Err() != nil {
		conn.Close()
		return false
	}
	if b.sockets[l.client] == nil {
		b.sockets[l.client] = map[net.Conn]bool{}
	}
	b.sockets[l.client][conn] = true
	l.socket = conn
	return true
}
func (l *flowLease) release() {
	l.once.Do(func() {
		b := l.budget
		b.mu.Lock()
		defer b.mu.Unlock()
		b.total--
		b.clients[l.client]--
		delete(b.sockets[l.client], l.socket)
		delete(b.leases, l)
		l.cancel()
	})
}
func (b *flowBudget) revoke(client string) {
	b.revokeExcept(client, nil)
}
func (b *flowBudget) revokeExcept(client string, except net.Conn) {
	b.mu.Lock()
	if b.generations == nil {
		b.clients = map[string]int{}
		b.blocked = map[string]bool{}
		b.generations = map[string]uint64{}
		b.sockets = map[string]map[net.Conn]bool{}
		b.leases = map[*flowLease]bool{}
	}
	b.generations[client]++
	b.blocked[client] = true
	delete(b.forwarded, client)
	var sockets []net.Conn
	for socket := range b.sockets[client] {
		if socket != except {
			sockets = append(sockets, socket)
		}
	}
	for lease := range b.leases {
		if lease.client == client && (except == nil || lease.socket != except) {
			lease.cancel()
		}
	}
	b.mu.Unlock()
	for _, socket := range sockets {
		socket.Close()
	}
}

func (b *flowBudget) available(client string) bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.clients[client] == 0
}
func (b *flowBudget) unblock(client string) {
	b.mu.Lock()
	defer b.mu.Unlock()
	delete(b.blocked, client)
}

func (b *flowBudget) close() {
	b.mu.Lock()
	if b.closed {
		b.mu.Unlock()
		return
	}
	b.closed = true
	for lease := range b.leases {
		lease.cancel()
	}
	var sockets []net.Conn
	for _, client := range b.sockets {
		for socket := range client {
			sockets = append(sockets, socket)
		}
	}
	b.mu.Unlock()
	for _, socket := range sockets {
		socket.Close()
	}
}
