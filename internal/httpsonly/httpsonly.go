// Package httpsonly decides when an insecure (http://) address is opened in
// its secure form (https://) instead.
package httpsonly

import (
	"net"
	"net/url"
	"strings"
)

// AllowMark is added to an address when the user chose "open anyway (not
// secure)" on Ronin's warning page.
const AllowMark = "#ronin-allow-http"

// Upgrade returns the secure address to open instead of rawURL, or ok=false
// when the address stays as it is (already secure, a local address, a
// device on the home network, or a site the user allowed).
func Upgrade(rawURL string, allowed func(host string) bool) (secure string, ok bool) {
	u, err := url.Parse(rawURL)
	if err != nil || !strings.EqualFold(u.Scheme, "http") {
		return "", false
	}
	host := strings.ToLower(u.Hostname())
	if host == "" || Local(host) || (allowed != nil && allowed(host)) {
		return "", false
	}
	if p := u.Port(); p != "" && p != "80" {
		return "", false // a special port is usually a device or a test server
	}
	u.Scheme = "https"
	u.Host = host
	if strings.Contains(host, ":") {
		u.Host = "[" + host + "]"
	}
	return u.String(), true
}

// AllowRequest reports whether rawURL carries the "open anyway" mark, and
// returns its host.
func AllowRequest(rawURL string) (host string, ok bool) {
	if !strings.HasSuffix(rawURL, AllowMark) || !strings.HasPrefix(strings.ToLower(rawURL), "http://") {
		return "", false
	}
	u, err := url.Parse(rawURL)
	if err != nil {
		return "", false
	}
	return strings.ToLower(u.Hostname()), true
}

// Local reports addresses that never get a secure version: this computer,
// home-network devices (router, printer) and names without a dot.
func Local(host string) bool {
	host = strings.Trim(strings.ToLower(host), "[]")
	if host == "localhost" || !strings.Contains(host, ".") && net.ParseIP(host) == nil {
		return true
	}
	for _, s := range []string{".local", ".lan", ".home", ".internal", ".test", ".localhost", ".home.arpa"} {
		if strings.HasSuffix(host, s) {
			return true
		}
	}
	if ip := net.ParseIP(host); ip != nil {
		return ip.IsLoopback() || ip.IsPrivate() || ip.IsLinkLocalUnicast() || ip.IsUnspecified()
	}
	return false
}
