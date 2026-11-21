package forwarding

import "testing"

func TestTCPRefusalAndControlAreNotForwardingEvidence(t *testing.T) {
	packet := make([]byte, 40)
	packet[0], packet[3], packet[9], packet[12], packet[32] = 0x45, 40, 6, 1, 0x50
	for _, test := range []struct {
		flags byte
		want  bool
	}{{0x14, false}, {0x10, false}, {0x12, true}} {
		packet[33] = test.flags
		if got := IPv4Reply(packet); got != test.want {
			t.Errorf("TCP flags %x: got %t, want %t", test.flags, got, test.want)
		}
	}
	copy(packet[12:16], []byte{10, 13, 37, 1})
	if IPv4Reply(packet) {
		t.Fatal("control traffic counted as forwarding")
	}
}
