package linqavowg

import (
	"context"
	"errors"
	"net"
	"os"
	"strconv"
	"strings"
	"sync/atomic"
	"syscall"
	"time"

	"linqavo.local/wg/internal/forwarding"
	"linqavo.local/wg/internal/presence"
	"golang.org/x/sys/unix"
	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun"
)

// AndroidClientSockets is implemented by the owning VpnService. The presence
// socket must be bound to the VPN before connecting.
type AndroidClientSockets interface {
	PrepareTransport(fd int32) bool
	PreparePresence(fd int32) bool
	OnPresence(event string)
}

type androidBind struct {
	*conn.StdNetBind
	sockets AndroidClientSockets
}

func (b *androidBind) Open(port uint16) ([]conn.ReceiveFunc, uint16, error) {
	receive, actual, err := b.StdNetBind.Open(port)
	if err != nil {
		return nil, 0, err
	}
	// The pinned StdNetBind returns IPv4 followed by IPv6 when both exist.
	// Require IPv4 and use only its receive loop. Protect before any worker
	// can use the sockets, including when wireguard-go reopens its bind.
	if len(receive) == 2 {
		v4, err4 := b.PeekLookAtSocketFd4()
		v6, err6 := b.PeekLookAtSocketFd6()
		if err4 == nil && err6 == nil && b.sockets.PrepareTransport(int32(v4)) && b.sockets.PrepareTransport(int32(v6)) {
			return receive[:1], actual, nil
		}
	}
	b.StdNetBind.Close()
	return nil, 0, errors.New("cannot protect and bind Android transport")
}

// androidClientTun wraps a duplicate of the VpnService descriptor. Closing
// this worker leaves the service's original descriptor and routes in place.
type androidClientTun struct {
	file   *os.File
	events chan tun.Event
	rx     atomic.Int64
	tx     atomic.Int64
	proof  atomic.Int64
}

func (t *androidClientTun) File() *os.File           { return t.file }
func (t *androidClientTun) Name() (string, error)    { return "Linqavo Android", nil }
func (t *androidClientTun) MTU() (int, error)        { return mtu, nil }
func (t *androidClientTun) Events() <-chan tun.Event { return t.events }
func (t *androidClientTun) BatchSize() int           { return 1 }
func (t *androidClientTun) Close() error {
	close(t.events)
	return t.file.Close()
}

func (t *androidClientTun) Read(bufs [][]byte, sizes []int, offset int) (int, error) {
	packet := bufs[0][offset:]
	n, err := t.file.Read(packet)
	if err != nil {
		return 0, err
	}
	sizes[0] = n
	if forwarding.IPv4Data(packet[:n], 16) {
		t.tx.Add(int64(n))
	}
	return 1, nil
}

func (t *androidClientTun) Write(bufs [][]byte, offset int) (int, error) {
	for i, buf := range bufs {
		packet := buf[offset:]
		if _, err := t.file.Write(packet); err != nil {
			return i, err
		}
		if forwarding.IPv4Data(packet, 12) {
			t.rx.Add(int64(len(packet)))
		}
		if forwarding.IPv4Reply(packet) {
			t.proof.Add(1)
		}
	}
	return len(bufs), nil
}

type AndroidClientTunnel struct {
	tun      *androidClientTun
	device   *device.Device
	presence *presence.Client
}

// StartAndroidClient consumes fd even on failure. WireGuard opens no socket
// that can send traffic before the service approves its network binding.
func StartAndroidClient(fd int32, config, address string, sockets AndroidClientSockets) (*AndroidClientTunnel, error) {
	if err := unix.SetNonblock(int(fd), true); err != nil {
		unix.Close(int(fd))
		return nil, errors.New("invalid Android TUN descriptor")
	}
	link := &androidClientTun{file: os.NewFile(uintptr(fd), "linqavo-tun"), events: make(chan tun.Event)}
	bind := &androidBind{conn.NewStdNetBind().(*conn.StdNetBind), sockets}
	wg := device.NewDevice(link, bind, device.NewLogger(device.LogLevelSilent, ""))
	if err := wg.IpcSet(config); err != nil {
		wg.Close()
		return nil, errors.New("invalid Android tunnel configuration")
	}
	if err := wg.Up(); err != nil {
		wg.Close()
		return nil, errors.New("Android tunnel transport unavailable")
	}
	dialer := net.Dialer{
		LocalAddr: &net.TCPAddr{IP: net.ParseIP(address)},
		Control: func(_, _ string, socket syscall.RawConn) error {
			var bound bool
			if err := socket.Control(func(fd uintptr) { bound = sockets.PreparePresence(int32(fd)) }); err != nil {
				return err
			}
			if !bound {
				return errors.New("Android VPN control socket unavailable")
			}
			return nil
		},
	}
	control := presence.StartObserved(func(ctx context.Context, endpoint string) (net.Conn, error) {
		return dialer.DialContext(ctx, "tcp4", endpoint)
	}, sockets.OnPresence)
	return &AndroidClientTunnel{link, wg, control}, nil
}

func (c *AndroidClientTunnel) Close() {
	c.presence.Stop()
	c.device.Close()
}

func (c *AndroidClientTunnel) Received() int64 { return c.tun.rx.Load() }
func (c *AndroidClientTunnel) Sent() int64     { return c.tun.tx.Load() }
func (c *AndroidClientTunnel) Replies() int64  { return c.tun.proof.Load() }

func (c *AndroidClientTunnel) HandshakeAgeSeconds() int64 {
	status, err := c.device.IpcGet()
	if err == nil {
		for _, line := range strings.Split(status, "\n") {
			if value, ok := strings.CutPrefix(line, "last_handshake_time_sec="); ok {
				if seconds, err := strconv.ParseInt(value, 10, 64); err == nil && seconds > 0 {
					return max(0, time.Now().Unix()-seconds)
				}
			}
		}
	}
	return -1
}

func (c *AndroidClientTunnel) Running() bool {
	select {
	case <-c.device.Wait():
		return false
	default:
		return true
	}
}
