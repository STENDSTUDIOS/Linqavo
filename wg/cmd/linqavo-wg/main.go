// Command linqavo-wg is the Windows half of Full Mode: it stands up a
// WinTun adapter and runs a userspace WireGuard tunnel to the phone.
//
// It exists as a separate executable rather than as code inside the app for one
// reason: creating a network adapter and changing routes requires
// Administrator, and Relay is deliberately a per-user install that never asks
// for it (ADR-0005). Putting the privileged part in its own short-lived process
// keeps that property for everything except the mode that genuinely cannot have
// it, and keeps the elevation prompt tied to a single visible action.
//
// The configuration is handed over a stream, never as a file. It carries the
// client's private key, and a key written to a temp file survives a crash, a
// backup, and anyone reading the disk afterwards. A command line is no better:
// any process on the machine can read one.
//
// Protocol with the parent process, over a named pipe (-config-pipe) or over
// stdin and stdout when run by hand:
//
//	in      the wireguard-go IPC configuration, ended by "END-CONFIG"
//	out     "READY" once the peer has handshaked and traffic can flow, or
//	        "NO-HANDSHAKE" if the adapter came up and the peer never answered;
//	        then, only with -hold-on-loss, "PEER-LOST" and "PEER-BACK" as the
//	        phone stops and resumes answering
//	stderr  human-readable progress and errors
//	exit    tear the tunnel down when the stream closes, or on Ctrl+Break
//
// The stream stays open for the life of the tunnel, which is what makes its
// closing mean "the app is gone" -- including the app crashing, the case that
// matters, because a tunnel nobody is watching still holds the machine's
// routes. That is also why the configuration ends with a sentinel rather than
// with EOF: reading to EOF would consume the very signal being waited on.
//
// The adapter disappears when this process exits, by any route including being
// killed: WinTun removes it with the last handle. That is what makes the
// teardown safe rather than merely careful -- the routes go with it, so there
// is no state left behind for a crash to strand.
package main

import (
	"bufio"
	"context"
	"errors"
	"flag"
	"fmt"
	linqavowg "linqavo.local/wg"
	"linqavo.local/wg/internal/presence"
	"io"
	"net"
	"net/netip"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"golang.org/x/sys/windows"
	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun"
	"golang.zx2c4.com/wireguard/windows/tunnel/winipcfg"
)

const (
	// Matches the endpoint's MTU (/wg/linqavowg.go). A mismatch shows up as large
	// replies vanishing while small ones work, which reads like a broken site.
	mtu = 1420

	// Lower than any physical adapter's, so the tunnel wins for the prefixes it
	// is given without having to delete anyone else's routes.
	routeMetric = 5

	// Ends the configuration on stdin without closing it. See [readConfig].
	configTerminator = "END-CONFIG"

	// Printed when the tunnel is up but the filters that keep traffic inside it
	// could not be installed. Not fatal -- see where it is used -- but the app
	// must be able to tell the difference between protected and not.
	leakProtectionFailedLine = "LEAK-PROTECTION-FAILED"

	// Prefix of the line the app sends when the phone turns up at a new address,
	// e.g. "ENDPOINT 192.168.1.14:51820". See [roam].
	endpointPrefix = "ENDPOINT "

	// Printed instead of READY when the adapter came up but the peer never
	// answered. Distinct from a generic failure because the user's next action
	// is different: rescan the QR, because the phone has almost certainly minted
	// new keys since that one was drawn.
	noHandshakeLine = "NO-HANDSHAKE"
	tunnelReadyLine = "TUNNEL-READY"

	// How long to wait for the first handshake before giving up on the peer.
	// A handshake on a working link completes in well under a second; this is
	// long enough to ride out a phone that is still bringing its endpoint up.
	handshakeTimeout = 20 * time.Second

	handshakePoll = 250 * time.Millisecond

	// Printed with -hold-on-loss when the peer goes quiet and again when it
	// answers. See [watchPeer].
	peerLostLine = "PEER-LOST"
	peerBackLine = "PEER-BACK"
)

var expectedParentPID uint

func main() {
	flag.UintVar(&expectedParentPID, "parent-pid", 0, "expected named pipe server PID")
	generate := flag.Bool("generate-keypair", false, "generate a local client identity; never creates an adapter")
	name := flag.String("name", "Linqavo", "adapter name")
	address := flag.String("address", "10.13.37.2/32", "this end of the tunnel")
	dns := flag.String("dns", "", "DNS server to set on the adapter; empty leaves it alone")
	diagnostics := flag.Bool("lifecycle-diagnostics", false, "emit bounded redacted lifecycle trace lines")
	routes := flag.String("routes", "0.0.0.0/0", "comma-separated prefixes to send through the tunnel")
	pipe := flag.String("config-pipe", "",
		"named pipe carrying the configuration and readiness; stdin/stdout when empty")
	blockLeaks := flag.Bool("block-leaks", true,
		"block traffic that would bypass the tunnel (other resolvers, IPv6, other adapters)")
	holdOnLoss := flag.Bool("hold-on-loss", false,
		"kill switch: when the phone stops answering, keep the tunnel's routes so nothing "+
			"leaves by another adapter, and wait for it to come back")
	flag.Parse()
	if *generate {
		identity, err := linqavowg.NewClientIdentity()
		if err != nil {
			os.Exit(1)
		}
		fmt.Println(identity)
		return
	}

	if err := run(*name, *address, *dns, *routes, *pipe, *blockLeaks, *holdOnLoss, *diagnostics); err != nil {
		fmt.Fprintf(os.Stderr, "linqavo-wg: %v\n", err)
		os.Exit(1)
	}
}

func run(name, address, dns, routes, pipe string, blockLeaks, holdOnLoss, diagnostics bool) error {
	// The app talks over a named pipe rather than stdin, because a process
	// launched through the elevation prompt cannot have its streams redirected
	// at all -- and the only other way to hand it a private key would be a temp
	// file, which is exactly what should not exist. Stdin remains for running
	// this by hand, and for the tests.
	channel, err := openChannel(pipe)
	if err != nil {
		return err
	}
	defer channel.Close()

	reader := bufio.NewReader(channel)
	config, parentGone, err := readConfig(reader)
	if err != nil {
		return err
	}

	prefix, err := netip.ParsePrefix(address)
	if err != nil {
		return fmt.Errorf("address %q: %w", address, err)
	}
	tunnelRoutes, err := parsePrefixes(routes)
	if err != nil {
		return err
	}

	// Creating the adapter is the step that needs Administrator. Its failure is
	// the one a person is most likely to hit, so it is reported on its own
	// rather than folded into a generic "could not start".
	adapter, err := tun.CreateTUN(name, mtu)
	if err != nil {
		return fmt.Errorf("could not create the %q adapter (Administrator required): %w", name, err)
	}
	defer adapter.Close()

	native, ok := adapter.(*tun.NativeTun)
	if !ok {
		return errors.New("the adapter is not a WinTun device")
	}
	luid := winipcfg.LUID(native.LUID())

	observed := &observedTunnel{Device: adapter, start: time.Now()}
	dev := device.NewDevice(observed, conn.NewDefaultBind(),
		device.NewLogger(device.LogLevelError, "linqavo-wg: "))
	defer dev.Close()

	if err := dev.IpcSet(config); err != nil {
		return fmt.Errorf("the configuration was rejected: %w", err)
	}
	if err := dev.Up(); err != nil {
		return fmt.Errorf("the tunnel did not come up: %w", err)
	}

	if err := configureAdapter(luid, prefix, dns, tunnelRoutes); err != nil {
		io.WriteString(channel, "ROUTING-FAILED\n")
		return err
	}

	// Close the two ways traffic was leaving around the tunnel.
	//
	// DNS   Windows resolves names on every interface at once, so on a Wi-Fi
	//       shared with the phone the router answered alongside the tunnel and a
	//       leak test listed the local ISP. A user reported exactly that, and
	//       only ever on Wi-Fi -- on a hotspot the only other resolver is the
	//       phone, so nothing showed.
	// IPv6  This client configures AF_INET only, so every v6 connection left by
	//       the physical adapter carrying the real address.
	//
	// wfp_windows.go says why this is written against WFP directly rather than
	// using wireguard-windows' firewall package, which cannot work from a
	// non-service process. Failure is reported and the tunnel still comes up: a
	// leak is bad, but a Relay that refuses to connect is worse, and every
	// release before this one ran without these filters anyway. What must not
	// happen is silence, because the person now believes they are protected.
	if blockLeaks {
		var resolvers []netip.Addr
		if server, err := netip.ParseAddr(dns); err == nil {
			resolvers = append(resolvers, server)
		}
		stop, err := enableLeakProtection(resolvers)
		if err != nil {
			fmt.Fprintf(os.Stderr, "leak protection unavailable: %v", err)
			fmt.Fprintln(channel, leakProtectionFailedLine)
			return fmt.Errorf("required leak protection could not be installed: %w", err)
		} else {
			defer stop()
			fmt.Fprintln(os.Stderr, "leak protection on: DNS is pinned to the tunnel and IPv6 is refused")
		}
	}

	fmt.Fprintf(os.Stderr, "tunnel up on %s via %q\n", prefix, name)

	// Readiness is the handshake, not the adapter.
	//
	// This used to report READY as soon as the adapter existed, which is equally
	// true of a tunnel whose peer is gone, whose keys have been rotated, or that
	// is pointed at nothing at all. The app then said "Connected" over a tunnel
	// that could never carry a byte, and — having no probe and no supervision,
	// on the strength of a comment claiming this line already meant a handshake
	// — went on saying it. A stale QR is the ordinary way in: the phone mints
	// fresh keys every time sharing restarts, so a code scanned minutes ago is
	// no longer one the endpoint will answer.
	if !waitForHandshake(dev, handshakeTimeout) {
		fmt.Fprintln(os.Stderr, "no handshake: the peer never answered")
		// Best effort — if the parent is already gone this fails and the error
		// below is still the right one.
		io.WriteString(channel, noHandshakeLine+"\n")
		return errors.New("the peer never completed a handshake")
	}

	// The tunnel now carries traffic -- but only for connections opened from
	// here on. Everything already established keeps leaving by the adapter it
	// was born on, because Windows binds a TCP connection to its source address
	// for life. Close those so their owners reconnect through the route that now
	// exists; reset_windows.go carries the measurement behind this.
	if closed := resetForeignConnections(prefix.Addr()); closed > 0 {
		fmt.Fprintf(os.Stderr, "closed %d connection(s) that predated the tunnel\n", closed)
	}
	if _, err := io.WriteString(channel, tunnelReadyLine+"\n"); err != nil {
		return fmt.Errorf("reporting readiness: %w", err)
	}
	peerKey := peerPublicKey(config)
	ctx, cancel := context.WithCancel(context.Background())
	started := time.Now()
	dialer := func() (net.Dialer, error) {
		iface, err := luid.IPInterface(windows.AF_INET)
		if err != nil {
			return net.Dialer{}, err
		}
		return tunnelPresenceDialer(prefix.Addr(), iface.InterfaceIndex), nil
	}
	events := make(chan string, 8)
	presenceClient := presence.StartObserved(func(ctx context.Context, destination string) (net.Conn, error) {
		bound, err := dialer()
		if err != nil {
			return nil, err
		}
		return bound.DialContext(ctx, "tcp4", destination)
	}, func(event string) {
		select {
		case events <- event:
		case <-ctx.Done():
		}
	})
	lifecycleGone := make(chan struct{})
	lifecycleDone := make(chan struct{})
	var peerConfig sync.Mutex
	var dnsOK, dnsFail atomic.Int64
	var traceSession, tracePeer string
	if diagnostics {
		traceSession, tracePeer = traceIDs(config)
	}
	go func() {
		defer close(lifecycleDone)
		ticker := time.NewTicker(time.Second)
		defer ticker.Stop()
		last, control := "", ""
		var traceAt time.Time
		var previousRX, previousTX int64
		remaining := 200
		for {
			select {
			case <-ctx.Done():
				return
			case control = <-events:
				if control == "client-disabled" || control == "server-stopped" || control == "enrollment-revoked" {
					observed.reply.Store(0)
				}
			case <-ticker.C:
			}
			now := time.Now()
			line := forwardingState(now, started, observed.lastReply(), control)
			if diagnostics && remaining > 0 && (!now.Before(traceAt) || line != last) {
				rx, tx := observed.rx.Load(), observed.tx.Load()
				age := int64(-1)
				if seconds := lastHandshakeUnix(dev); seconds > 0 {
					age = max(0, now.Unix()-seconds)
				}
				io.WriteString(channel, traceLine(started, traceSession, tracePeer, line, control, age,
					rx-previousRX, tx-previousTX, dnsOK.Swap(0), dnsFail.Swap(0))+"\n")
				previousRX, previousTX = rx, tx
				traceAt = now.Add(5 * time.Second)
				remaining--
			}
			if line == "" || line == last {
				continue
			}
			last = line
			if _, err := io.WriteString(channel, line+"\n"); err != nil {
				close(lifecycleGone)
				return
			}
			switch line {
			case "CLIENT-DISABLED", "SERVER-STOPPED", "ENROLLMENT-REVOKED", "SERVER-UNAVAILABLE":
				if !holdOnLoss {
					close(lifecycleGone)
					return
				}
				peerConfig.Lock()
				err := recoverHeldPeer(dev, peerKey, controlForRecovery(line, control))
				peerConfig.Unlock()
				if err != nil {
					io.WriteString(channel, "TRANSPORT-FAILED\n")
				}
			}
		}
	}()
	probeDone := make(chan struct{})
	go func() {
		defer close(probeDone)
		if dns == "" {
			return
		}
		ticker := time.NewTicker(8 * time.Second)
		defer ticker.Stop()
		for {
			if time.Since(observed.lastReply()) >= 15*time.Second {
				if bound, err := dialer(); err == nil {
					bound.LocalAddr = &net.UDPAddr{IP: net.IP(prefix.Addr().AsSlice())}
					probe, finish := context.WithTimeout(ctx, 3*time.Second)
					if probeResolver(probe, bound, dns) {
						dnsOK.Add(1)
					} else {
						dnsFail.Add(1)
					}
					finish()
				}
			}
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
			}
		}
	}()
	defer func() {
		cancel()
		presenceClient.Stop()
		<-lifecycleDone
		<-probeDone
	}()

	waitForShutdown(reader, parentGone, nil, func(line string) {
		endpoint, ok := strings.CutPrefix(line, endpointPrefix)
		if !ok {
			return // the app says nothing else here; ignore whatever this was
		}
		endpoint = strings.TrimSpace(endpoint)
		peerConfig.Lock()
		err := roam(dev, peerKey, endpoint)
		peerConfig.Unlock()
		if err != nil {
			fmt.Fprintf(os.Stderr, "could not follow the peer to %q: %v\n", endpoint, err)
			return
		}
		fmt.Fprintf(os.Stderr, "the peer moved to %s\n", endpoint)
	}, lifecycleGone)
	fmt.Fprintln(os.Stderr, "tearing down")
	// The deferred Close calls remove the adapter, and Windows drops its
	// addresses and routes with it.
	return nil
}

// roam re-points the tunnel at the peer's new address without rebuilding it.
//
// WireGuard follows a peer that moves, but only in the direction this end is
// not: a responder learns an initiator's address from the packets it receives,
// and here the phone is the responder and the one that moves. Its address is a
// DHCP lease, so a renewal or a rejoin is enough, and then every handshake goes
// to an address nobody is listening on until [peerLost] tears the tunnel down —
// which is what "worked once, then never again" looked like from the outside.
//
// Only the endpoint moves. The keys are untouched, so this cannot hand the
// tunnel to anyone: an endpoint that does not hold the peer's key completes no
// handshake and gets nothing. update_only is belt and braces on top of that —
// a line naming an unknown key moves nothing rather than adding a second peer.
//
// The address must be a literal. The beacon carries one, and refusing anything
// else keeps a name lookup — which blocks, and which the tunnel would be
// routing — off this path entirely.
func roam(dev *device.Device, peerKey, endpoint string) error {
	if peerKey == "" {
		return errors.New("the configuration named no peer to move")
	}
	if _, err := netip.ParseAddrPort(endpoint); err != nil {
		return fmt.Errorf("not an address and port: %w", err)
	}
	return dev.IpcSet(fmt.Sprintf(
		"public_key=%s\nupdate_only=true\nendpoint=%s\n", peerKey, endpoint))
}

// peerPublicKey returns the peer's key from an IPC configuration.
//
// The device's own key is private_key=, so the first public_key= line is the
// peer's -- and there is exactly one peer, by ADR-0009.
func peerPublicKey(config string) string {
	for _, line := range strings.Split(config, "\n") {
		if key, ok := strings.CutPrefix(strings.TrimSpace(line), "public_key="); ok {
			return key
		}
	}
	return ""
}

// waitForHandshake reports whether the peer answered within [within].
func waitForHandshake(dev *device.Device, within time.Duration) bool {
	deadline := time.Now().Add(within)
	for {
		if lastHandshakeUnix(dev) > 0 {
			return true
		}
		if time.Now().After(deadline) {
			return false
		}
		time.Sleep(handshakePoll)
	}
}

// lastHandshakeUnix reads the peer's last handshake out of the device itself,
// for the same reason [linqavowg.Endpoint] does: a second copy of this state is a
// second thing that can disagree with the tunnel.
func lastHandshakeUnix(dev *device.Device) int64 {
	status, err := dev.IpcGet()
	if err != nil {
		return 0
	}
	for _, line := range strings.Split(status, "\n") {
		value, ok := strings.CutPrefix(strings.TrimSpace(line), "last_handshake_time_sec=")
		if !ok {
			continue
		}
		seconds, err := strconv.ParseInt(value, 10, 64)
		if err != nil {
			return 0
		}
		return seconds
	}
	return 0
}

// configureAdapter gives the tunnel its address, DNS and routes.
//
// Order matters: the address has to exist before a route can point at the
// interface, and the interface metric has to be set before the routes or
// Windows may prefer the physical adapter for the same prefix.
func configureAdapter(luid winipcfg.LUID, prefix netip.Prefix, dns string, routes []netip.Prefix) error {
	if err := luid.SetIPAddressesForFamily(windows.AF_INET, []netip.Prefix{prefix}); err != nil {
		return fmt.Errorf("setting %s on the adapter: %w", prefix, err)
	}

	iface, err := luid.IPInterface(windows.AF_INET)
	if err != nil {
		return fmt.Errorf("reading the adapter's IP settings: %w", err)
	}
	iface.NLMTU = mtu
	iface.UseAutomaticMetric = false
	iface.Metric = routeMetric
	// Without this Windows sends router solicitations out of a point-to-point
	// tunnel that has no router, which shows up as a delay before traffic flows.
	iface.RouterDiscoveryBehavior = winipcfg.RouterDiscoveryDisabled
	iface.DadTransmits = 0
	if err := iface.Set(); err != nil {
		return fmt.Errorf("applying the adapter's IP settings: %w", err)
	}

	if dns != "" {
		server, err := netip.ParseAddr(dns)
		if err != nil {
			return fmt.Errorf("dns %q: %w", dns, err)
		}
		if err := luid.SetDNS(windows.AF_INET, []netip.Addr{server}, nil); err != nil {
			return fmt.Errorf("setting DNS: %w", err)
		}
	}

	// Deliberately additive. Nothing here deletes or rewrites an existing route:
	// the tunnel's default route wins on metric while it exists and disappears
	// with the adapter, so a crash cannot strand a machine with a route to
	// somewhere that is gone. The phone itself stays reachable because it sits
	// on the local subnet, whose on-link route is more specific than 0.0.0.0/0
	// and stays on the physical adapter -- which is also what stops the tunnel's
	// own UDP from being routed into itself.
	data := make([]*winipcfg.RouteData, 0, len(routes))
	for _, route := range routes {
		data = append(data, &winipcfg.RouteData{
			Destination: route,
			NextHop:     netip.IPv4Unspecified(),
			Metric:      0,
		})
	}
	if err := luid.SetRoutesForFamily(windows.AF_INET, data); err != nil {
		return fmt.Errorf("setting routes: %w", err)
	}
	return nil
}

func parsePrefixes(list string) ([]netip.Prefix, error) {
	parsed := []netip.Prefix{}
	for _, item := range strings.Split(list, ",") {
		item = strings.TrimSpace(item)
		if item == "" {
			continue
		}
		prefix, err := netip.ParsePrefix(item)
		if err != nil {
			return nil, fmt.Errorf("route %q: %w", item, err)
		}
		parsed = append(parsed, prefix)
	}
	if len(parsed) == 0 {
		return nil, errors.New("no routes to install — the tunnel would carry nothing")
	}
	return parsed, nil
}

// openChannel returns the stream the configuration arrives on and readiness is
// reported over: a named pipe when one is given, otherwise stdin and stdout.
//
// The pipe is opened read-write and stays open for the life of the tunnel. Its
// closing is how this process learns the app has gone -- including the app
// crashing, which is the case that matters, because a tunnel nobody is watching
// still holds the machine's routes.
func openChannel(pipe string) (io.ReadWriteCloser, error) {
	if pipe == "" {
		return stdioChannel{}, nil
	}
	if !strings.HasPrefix(pipe, "linqavo-wg-") || len(pipe) != len("linqavo-wg-")+32 || expectedParentPID == 0 {
		return nil, errors.New("invalid pipe identity")
	}
	path := `\\.\pipe\` + pipe
	// The command reader normally has an outstanding read for the entire
	// session. A synchronous Windows handle serializes lifecycle writes
	// behind that read, including the status sent before teardown.
	handle, err := os.OpenFile(path, os.O_RDWR|windows.O_FILE_FLAG_OVERLAPPED, 0)
	if err != nil {
		return nil, fmt.Errorf("opening %s: %w", path, err)
	}
	var parent uint32
	if err := windows.GetNamedPipeServerProcessId(windows.Handle(handle.Fd()), &parent); err != nil || parent != uint32(expectedParentPID) {
		handle.Close()
		return nil, errors.New("unexpected pipe server")
	}
	return handle, nil
}

// stdioChannel reads from stdin and writes to stdout as one stream.
type stdioChannel struct{}

func (stdioChannel) Read(p []byte) (int, error)  { return os.Stdin.Read(p) }
func (stdioChannel) Write(p []byte) (int, error) { return os.Stdout.Write(p) }
func (stdioChannel) Close() error                { return nil }

// readConfig reads the IPC configuration up to [configTerminator], and reports
// whether stdin reached EOF while doing so.
//
// The terminator exists because stdin is two things at once: the way the
// configuration arrives, and the way this process learns its parent is gone.
// Reading to EOF for the first would consume the second, and the tunnel would
// tear itself down the instant it came up.
//
// Reaching EOF instead of the terminator is not an error — that is what
// `type relay.conf | linqavo-wg` looks like, and it is a reasonable way to
// run this by hand. It only means the parent is already gone, so Ctrl+Break
// becomes the only way out.
func readConfig(stdin *bufio.Reader) (config string, parentGone bool, err error) {
	var builder strings.Builder
	for {
		line, readErr := stdin.ReadSlice('\n')
		if len(line) > 1024 || builder.Len()+len(line) > 8192 || errors.Is(readErr, bufio.ErrBufferFull) {
			return "", true, errors.New("configuration exceeds limits")
		}
		trimmed := strings.TrimRight(string(line), "\r\n")
		if trimmed == configTerminator {
			break
		}
		if trimmed != "" {
			builder.WriteString(trimmed)
			builder.WriteByte('\n')
		}
		if readErr != nil {
			if !errors.Is(readErr, io.EOF) {
				return "", true, fmt.Errorf("reading the configuration: %w", readErr)
			}
			parentGone = true
			break
		}
	}
	if builder.Len() == 0 {
		return "", parentGone, errors.New("no configuration on stdin")
	}
	return builder.String(), parentGone, nil
}

// waitForShutdown returns when the parent goes away or the console interrupts,
// passing each line the parent sends meanwhile to [onLine].
//
// Both shutdown paths matter. The parent closing stdin is the ordinary
// Disconnect; the signal is what a person pressing Ctrl+C in a console window
// sends. Waiting on only one of them leaves a tunnel running with nobody
// watching it.
//
// The lines are new. This end of the channel used to be drained into
// io.Discard, because the only thing it had to detect was the stream ending --
// and reading it for content costs nothing, since it was already being read.
func waitForShutdown(
	channel *bufio.Reader,
	parentAlreadyGone bool,
	peerGone <-chan struct{},
	onLine func(string),
	extra ...<-chan struct{},
) {
	var lifecycle <-chan struct{}
	if len(extra) > 0 {
		lifecycle = extra[0]
	}
	signals := make(chan os.Signal, 1)
	signal.Notify(signals, os.Interrupt, syscall.SIGTERM)
	defer signal.Stop(signals)

	if parentAlreadyGone {
		select {
		case <-signals:
		case <-peerGone:
		case <-lifecycle:
		}
		return
	}

	closed := make(chan struct{})
	go func() {
		defer close(closed)
		scanner := bufio.NewScanner(channel)
		for scanner.Scan() {
			onLine(strings.TrimSpace(scanner.Text()))
		}
	}()

	select {
	case <-signals:
	case <-closed:
	case <-peerGone:
	case <-lifecycle:
	}
}
