package main

import (
	"errors"
	"golang.zx2c4.com/wireguard/device"
	"strings"
)

func resetClientPeer(dev *device.Device, key string) error {
	status, err := dev.IpcGet()
	if err != nil {
		return err
	}
	config, err := publicPeerConfiguration(status, key)
	if err != nil {
		return err
	}
	if err = dev.IpcSet("public_key=" + key + "\nremove=true\n"); err != nil {
		return err
	}
	return dev.IpcSet(config)
}

// A missed Disable notification must not strand a held client on obsolete WG
// receiver indices. A liveness timeout also starts a fresh authenticated handshake.
func recoverHeldPeer(dev *device.Device, key, event string) error {
	if event == "client-disabled" || event == "unavailable" {
		return resetClientPeer(dev, key)
	}
	if event == "server-stopped" || event == "enrollment-revoked" {
		return dev.IpcSet("public_key=" + key + "\nremove=true\n")
	}
	return nil
}
func publicPeerConfiguration(status, key string) (string, error) {
	selected := false
	endpoint, route := false, false
	out := "public_key=" + key + "\n"
	for _, line := range strings.Split(status, "\n") {
		k, v, ok := strings.Cut(line, "=")
		if !ok {
			continue
		}
		if k == "public_key" {
			selected = v == key
			continue
		}
		if !selected {
			continue
		}
		switch k {
		case "endpoint":
			endpoint = true
			out += line + "\n"
		case "allowed_ip":
			route = true
			out += line + "\n"
		case "persistent_keepalive_interval":
			out += line + "\n"
		}
	}
	if !endpoint || !route {
		return "", errors.New("peer route unavailable")
	}
	return out, nil
}
