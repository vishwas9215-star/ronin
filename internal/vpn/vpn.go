// Package vpn checks WireGuard config files (for example from Proton VPN's
// free plan) and writes the small wireproxy config that turns the tunnel
// into a local SOCKS5 proxy used only by Ronin's PERSONAL and PRIVATE windows.
package vpn

import (
	"bufio"
	"crypto/tls"
	"errors"
	"fmt"
	"io"
	"net"
	"strconv"
	"strings"
	"time"
)

// Port is the local proxy port (only reachable from this computer).
const Port = 40877

// Info is what the settings panel shows about an imported config.
type Info struct {
	Endpoint string // server address, e.g. "nl-free-12.protonvpn.net:51820"
	Name     string // from a "# ..." comment if present
}

// Check validates a WireGuard .conf text and returns its server address.
func Check(conf string) (Info, error) {
	var info Info
	section := ""
	have := map[string]bool{}
	sc := bufio.NewScanner(strings.NewReader(conf))
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if line == "" {
			continue
		}
		if strings.HasPrefix(line, "#") {
			if info.Name == "" {
				info.Name = strings.TrimSpace(strings.TrimPrefix(line, "#"))
			}
			continue
		}
		if strings.HasPrefix(line, "[") && strings.HasSuffix(line, "]") {
			section = strings.ToLower(strings.Trim(line, "[]"))
			continue
		}
		k, v, ok := strings.Cut(line, "=")
		if !ok {
			return info, fmt.Errorf("line not understood: %q", short(line))
		}
		k = strings.ToLower(strings.TrimSpace(k))
		v = strings.TrimSpace(v)
		have[section+"."+k] = v != ""
		if section == "peer" && k == "endpoint" {
			info.Endpoint = v
		}
	}
	for _, need := range []string{"interface.privatekey", "interface.address", "peer.publickey", "peer.endpoint"} {
		if !have[need] {
			return info, errors.New("this is not a complete WireGuard config (missing " + need + ")")
		}
	}
	if len(info.Name) > 60 {
		info.Name = info.Name[:60]
	}
	return info, nil
}

func short(s string) string {
	if len(s) > 30 {
		return s[:30] + "..."
	}
	return s
}

// WireproxyConfig is the config file for wireproxy.
func WireproxyConfig(wgConfPath string) string {
	return "WGConfig = " + wgConfPath + "\n\n[Socks5]\nBindAddress = 127.0.0.1:" + fmt.Sprint(Port) + "\n"
}

// BrowserArgs are the extra engine switches for a window that uses the VPN.
// DNS goes through the tunnel (SOCKS5 resolves names on the far side) and
// WebRTC may not reveal the real address.
func BrowserArgs() []string {
	return []string{
		fmt.Sprintf("--proxy-server=socks5://127.0.0.1:%d", Port),
		"--proxy-bypass-list=<-loopback>",
		"--force-webrtc-ip-handling-policy=disable_non_proxied_udp",
		"--webrtc-ip-handling-policy=disable_non_proxied_udp",
	}
}

// SmartBrowserArgs: the engine asks the rules at pacURL which connections
// use the VPN; WebRTC still may not reveal the real address.
func SmartBrowserArgs(pacURL string) []string {
	return []string{
		"--proxy-pac-url=" + pacURL,
		"--force-webrtc-ip-handling-policy=disable_non_proxied_udp",
		"--webrtc-ip-handling-policy=disable_non_proxied_udp",
	}
}

// SafeMTU is the packet size Ronin uses inside the tunnel. Some home and
// mobile networks drop bigger packets: then small requests work but secure
// pages never load. 1280 works everywhere.
const SafeMTU = 1280

// PrepareConf returns the WireGuard config with the safe packet size set.
func PrepareConf(conf string) string {
	var out []string
	section, done := "", false
	lines := strings.Split(strings.ReplaceAll(conf, "\r\n", "\n"), "\n")
	for _, line := range lines {
		t := strings.TrimSpace(line)
		if strings.HasPrefix(t, "[") && strings.HasSuffix(t, "]") {
			if section == "interface" && !done {
				out = append(out, fmt.Sprintf("MTU = %d", SafeMTU))
				done = true
			}
			section = strings.ToLower(strings.Trim(t, "[]"))
		}
		if section == "interface" {
			if k, _, ok := strings.Cut(t, "="); ok && strings.EqualFold(strings.TrimSpace(k), "mtu") {
				if !done {
					out = append(out, fmt.Sprintf("MTU = %d", SafeMTU))
					done = true
				}
				continue
			}
		}
		out = append(out, line)
	}
	if section == "interface" && !done {
		out = append(out, fmt.Sprintf("MTU = %d", SafeMTU))
	}
	return strings.Join(out, "\n")
}

// SocksCheck opens a connection to target through the local SOCKS5 proxy at
// proxyAddr. For port 80 it makes a small web request; for port 443 a full
// secure (TLS) handshake, which needs big packets, so a half-broken tunnel
// is found too. It returns nil when the tunnel really carries traffic.
func SocksCheck(proxyAddr, target string, timeout time.Duration) error {
	return socksCheck(proxyAddr, target, timeout, nil)
}

func socksCheck(proxyAddr, target string, timeout time.Duration, tlsConf *tls.Config) error {
	host, portStr, err := net.SplitHostPort(target)
	if err != nil || len(host) > 255 {
		return errors.New("bad target")
	}
	port, err := strconv.Atoi(portStr)
	if err != nil || port < 1 || port > 65535 {
		return errors.New("bad port")
	}
	c, err := net.DialTimeout("tcp", proxyAddr, timeout)
	if err != nil {
		return err
	}
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(timeout))
	if _, err := c.Write([]byte{5, 1, 0}); err != nil { // version 5, one method: no login
		return err
	}
	buf := make([]byte, 10)
	if _, err := io.ReadFull(c, buf[:2]); err != nil {
		return err
	}
	if buf[0] != 5 || buf[1] != 0 {
		return errors.New("proxy refused")
	}
	req := append([]byte{5, 1, 0, 3, byte(len(host))}, host...)
	req = append(req, byte(port>>8), byte(port))
	if _, err := c.Write(req); err != nil {
		return err
	}
	if _, err := io.ReadFull(c, buf[:4]); err != nil {
		return err
	}
	if buf[1] != 0 {
		return fmt.Errorf("tunnel cannot reach the internet (code %d)", buf[1])
	}
	// read the rest of the reply (address + port), then make a real request
	rest := 6
	switch buf[3] {
	case 4:
		rest = 18
	case 3:
		if _, err := io.ReadFull(c, buf[:1]); err != nil {
			return err
		}
		rest = int(buf[0]) + 2
	}
	if _, err := io.ReadFull(c, make([]byte, rest)); err != nil {
		return err
	}
	if port == 443 {
		cfg := &tls.Config{ServerName: host, MinVersion: tls.VersionTLS12}
		if tlsConf != nil {
			cfg = tlsConf.Clone()
			cfg.ServerName = host
		}
		tc := tls.Client(c, cfg)
		if err := tc.Handshake(); err != nil {
			return errors.New("secure pages do not get through the tunnel")
		}
		return nil
	}
	if port != 80 {
		return nil
	}
	if _, err := c.Write([]byte("HEAD / HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n")); err != nil {
		return err
	}
	if _, err := io.ReadFull(c, buf[:5]); err != nil {
		return errors.New("tunnel is open but no data comes back")
	}
	if string(buf[:5]) != "HTTP/" {
		return errors.New("tunnel gave a wrong answer")
	}
	return nil
}

// Next picks the config to try after one failed: the next one in the list,
// going round. With a single config it stays on it.
func Next(current, count int) int {
	if count <= 1 {
		return 0
	}
	return (current + 1) % count
}
