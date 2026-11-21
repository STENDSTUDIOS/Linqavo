package presence

import (
	"net"
	"sync"
	"time"
)

const LegacyPreface = "RLYP0001"
const ServerEventWait = 500 * time.Millisecond

// Control serializes server ACK/event writes. Events are meaningful only on an
// authenticated in-tunnel v2 stream with this stream's fresh ticket.
type Control struct {
	c        net.Conn
	ticket   [TicketBytes]byte
	version2 bool
	writeMu  sync.Mutex
	mu       sync.Mutex
	pending  byte
	ack      chan struct{}
}

func (c *Control) Version() string {
	if c == nil {
		return "absent"
	}
	if c.version2 {
		return Preface
	}
	return LegacyPreface
}

func (c *Control) write(kind byte, timeout time.Duration) error {
	c.writeMu.Lock()
	defer c.writeMu.Unlock()
	c.c.SetWriteDeadline(time.Now().Add(timeout))
	return writeFrame(c.c, c.ticket, kind)
}
func (c *Control) Notify(kind byte) bool {
	if c == nil || !c.version2 || (kind != 'S' && kind != 'X' && kind != 'R') {
		return false
	}
	c.mu.Lock()
	if c.pending != 0 {
		c.mu.Unlock()
		return false
	}
	c.pending = kind
	c.ack = make(chan struct{}, 1)
	ack := c.ack
	c.mu.Unlock()
	if c.write(kind, ServerEventWait) != nil {
		return false
	}
	select {
	case <-ack:
		return true
	case <-time.After(ServerEventWait):
		return false
	}
}
func (c *Control) acknowledge(kind byte) bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.pending == 0 || kind != c.pending+('a'-'A') {
		return false
	}
	select {
	case c.ack <- struct{}{}:
	default:
	}
	return true
}
