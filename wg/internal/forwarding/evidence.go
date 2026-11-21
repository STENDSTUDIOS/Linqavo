// Package forwarding classifies inner IPv4 response evidence at a client TUN.
package forwarding

import (
	"encoding/binary"
	"time"
)

const FreshFor = 25 * time.Second

func IPv4Data(packet []byte, addressOffset int) bool {
	return len(packet) >= 20 && packet[0]>>4 == 4 &&
		(packet[9] == 6 || packet[9] == 17) &&
		string(packet[addressOffset:addressOffset+4]) != "\x0a\x0d\x25\x01"
}

// A reset from a refused dial and empty TCP ACKs prove no forwarding.
// A SYN-ACK is evidence because Relay opens the egress connection first.
func IPv4Reply(packet []byte) bool {
	if !IPv4Data(packet, 12) {
		return false
	}
	header := int(packet[0]&15) * 4
	size := int(binary.BigEndian.Uint16(packet[2:4]))
	if header < 20 || size > len(packet) || size < header ||
		binary.BigEndian.Uint16(packet[6:8])&0x1fff != 0 {
		return false
	}
	transport := packet[header:size]
	if packet[9] == 17 {
		return len(transport) > 8
	}
	if len(transport) < 20 {
		return false
	}
	tcpHeader := int(transport[12]>>4) * 4
	flags := transport[13]
	return tcpHeader >= 20 && tcpHeader <= len(transport) && flags&4 == 0 &&
		(flags&0x12 == 0x12 || len(transport) > tcpHeader)
}
