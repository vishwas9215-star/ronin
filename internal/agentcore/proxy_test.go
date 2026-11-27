package agentcore

import (
	"bufio"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
)

// The proxy checks the address it really connects to: loopback / home network are refused for plain HTTP,
// HTTPS tunnels and WebSockets, whatever name was used (a name that resolves to 127.0.0.1 = DNS rebinding).
func TestProxyRefusesPrivateAddresses(t *testing.T) {
	inside := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { fmt.Fprint(w, "router admin page") }))
	defer inside.Close()
	var blocked []string
	p := &Proxy{Blocked: func(a string) { blocked = append(blocked, a) }}
	if err := p.Start(); err != nil {
		t.Fatal(err)
	}
	defer p.Close()
	pu, _ := url.Parse("http://" + p.Addr)
	c := &http.Client{Transport: &http.Transport{Proxy: http.ProxyURL(pu)}}
	_, port, _ := net.SplitHostPort(strings.TrimPrefix(inside.URL, "http://"))
	for _, u := range []string{inside.URL, "http://localhost:" + port + "/"} { // the name "localhost" = rebinding case
		res, err := c.Get(u)
		if err != nil {
			t.Fatal(err)
		}
		b, _ := io.ReadAll(res.Body)
		if res.StatusCode != 403 || strings.Contains(string(b), "router") {
			t.Fatalf("%s reached: %d %s", u, res.StatusCode, b)
		}
	}
	// CONNECT (https / wss) to the home network
	conn, err := net.Dial("tcp", p.Addr)
	if err != nil {
		t.Fatal(err)
	}
	fmt.Fprintf(conn, "CONNECT 127.0.0.1:%s HTTP/1.1\r\nHost: 127.0.0.1:%s\r\n\r\n", port, port)
	res, err := http.ReadResponse(bufio.NewReader(conn), nil)
	if err != nil || res.StatusCode != 403 {
		t.Fatalf("CONNECT: %v %v", err, res)
	}
	conn.Close()
	if len(blocked) < 3 {
		t.Fatal("every refusal is reported", blocked)
	}
	// the same proxy passes a public address (here: the test server, allowed for the test only)
	p.allow = func(net.IP) bool { return true }
	res, err = c.Get(inside.URL)
	if err != nil {
		t.Fatal(err)
	}
	b, _ := io.ReadAll(res.Body)
	if string(b) != "router admin page" {
		t.Fatal("allowed path", string(b))
	}
}
