package main

import (
	"golang.org/x/sys/windows"
	"math/bits"
	"net"
	"net/netip"
	"syscall"
)

// Pin only this internal control socket to WinTun as well as its source /32.
// A more-specific, overlapping physical LAN route must never carry the control
// stream in plaintext. Socket-option failure aborts the dial, without fallback.
func tunnelPresenceDialer(source netip.Addr, index uint32) net.Dialer {
	return net.Dialer{
		LocalAddr: &net.TCPAddr{IP: net.IP(source.AsSlice())},
		Control: func(_, _ string, raw syscall.RawConn) error {
			var socketErr error
			err := raw.Control(func(fd uintptr) {
				// IP_UNICAST_IF requires IPv4 interface index in network byte order.
				// Same setting as wireguard-go/conn.bindSocketToInterface4; Windows is LE.
				const ipUnicastIf = 31
				socketErr = windows.SetsockoptInt(windows.Handle(fd), windows.IPPROTO_IP, ipUnicastIf, int(bits.ReverseBytes32(index)))
			})
			if err != nil {
				return err
			}
			return socketErr
		},
	}
}
