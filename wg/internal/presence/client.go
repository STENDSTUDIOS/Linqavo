package presence

import (
	"context"
	"net"
	"sync"
	"time"
)

// Client owns a single control worker. Loss/retry does not change tunnel routes,
// keys or the Windows hold-on-loss/WFP policy. No DNS lookup or Internet dial.
type Client struct {
	stop   chan struct{}
	done   chan struct{}
	once   sync.Once
	cancel context.CancelFunc
	mu     sync.Mutex
	conn   net.Conn
	event  func(string)
}

func Start(dial func(context.Context, string) (net.Conn, error)) *Client {
	return StartObserved(dial, nil)
}

func StartObserved(dial func(context.Context, string) (net.Conn, error), event func(string)) *Client {
	ctx, cancel := context.WithCancel(context.Background())
	client := &Client{stop: make(chan struct{}), done: make(chan struct{}), cancel: cancel, event: event}
	go client.run(ctx, dial)
	return client
}

func (c *Client) run(ctx context.Context, dial func(context.Context, string) (net.Conn, error)) {
	defer close(c.done)
	seen, lost := false, false
	firstDeadline := time.Now().Add(OfflineAfter)
	notify := func(event string) {
		if c.event != nil {
			c.event(event)
		}
	}
	for ctx.Err() == nil {
		if !seen && !lost && !time.Now().Before(firstDeadline) {
			notify("unavailable")
			lost = true
		}
		limit := ReplyTimeout
		if !seen && !lost && time.Until(firstDeadline) < limit {
			limit = time.Until(firstDeadline)
		}
		attempt, cancel := context.WithTimeout(ctx, limit)
		socket, err := dial(attempt, Address)
		cancel()
		if err == nil {
			c.mu.Lock()
			c.conn = socket
			c.mu.Unlock()
			limit = ReplyTimeout
			if !seen && !lost && time.Until(firstDeadline) < limit {
				limit = time.Until(firstDeadline)
			}
			ticket, outcome, err := openWithin(socket, limit)
			if err == nil {
				seen = true
				if outcome == "" {
					lost = false
					notify("back")
					outcome = c.watch(socket, ticket)
				}
				if ctx.Err() == nil {
					if outcome == "" {
						outcome = "unavailable"
					}
					notify(outcome)
					lost = true
					if outcome == "server-stopped" || outcome == "enrollment-revoked" {
						socket.Close()
						c.mu.Lock()
						c.conn = nil
						c.mu.Unlock()
						return
					}
				}
			}
			socket.Close()
			c.mu.Lock()
			c.conn = nil
			c.mu.Unlock()
		}
		if seen && !lost && ctx.Err() == nil {
			notify("unavailable")
			lost = true
		}
		pause := ReplyTimeout
		if !seen && !lost && time.Until(firstDeadline) < pause {
			pause = time.Until(firstDeadline)
		}
		select {
		case <-c.stop:
			return
		case <-time.After(pause):
		}
	}
}

func (c *Client) watch(socket net.Conn, ticket [TicketBytes]byte) string {
	ticker := time.NewTicker(HeartbeatInterval)
	defer ticker.Stop()
	frames := make(chan byte, 4)
	failed := make(chan error, 1)
	quit := make(chan struct{})
	readDone := make(chan struct{})
	go func() {
		defer close(readDone)
		for {
			socket.SetReadDeadline(time.Now().Add(OfflineAfter))
			kind, err := readFrame(socket, ticket)
			if err != nil {
				failed <- err
				return
			}
			select {
			case frames <- kind:
			case <-quit:
				return
			}
			if kind == 'S' || kind == 'X' || kind == 'R' {
				return
			}
		}
	}()
	defer func() { close(quit); socket.Close(); <-readDone }()
	for {
		select {
		case <-c.stop:
			socket.SetWriteDeadline(time.Now().Add(DisconnectWait))
			if writeFrame(socket, ticket, 'D') == nil {
				select {
				case <-frames:
				case <-failed:
				case <-time.After(DisconnectWait):
				}
			}
			return ""
		case <-ticker.C:
			socket.SetWriteDeadline(time.Now().Add(ReplyTimeout))
			if writeFrame(socket, ticket, 'H') != nil {
				return "unavailable"
			}
		case kind := <-frames:
			if kind == 'h' {
				continue
			}
			if event := receiveEvent(socket, ticket, kind); event != "" {
				return event
			}
			return "unavailable"
		case <-failed:
			return "unavailable"
		}
	}
}

func (c *Client) Stop() {
	c.once.Do(func() { close(c.stop); c.cancel() })
	select {
	case <-c.done:
	case <-time.After(DisconnectWait):
		c.mu.Lock()
		if c.conn != nil {
			c.conn.Close()
		}
		c.mu.Unlock()
	}
}
