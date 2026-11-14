package vpn

import (
	"crypto/tls"
	"crypto/x509"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

const good = `[Interface]
# Key for Ronin laptop
# Bouncing = 3
PrivateKey = aGVsbG8gd29ybGQgdGhpcyBpcyBhIHRlc3Qga2V5MTI=
Address = 10.2.0.2/32
DNS = 10.2.0.1

[Peer]
# NL-FREE#12
PublicKey = c29tZSBwdWJsaWMga2V5IGZvciB0ZXN0aW5nIG9ubHk9
AllowedIPs = 0.0.0.0/0
Endpoint = 185.107.56.1:51820
`

func TestCheck(t *testing.T) {
	info, err := Check(good)
	if err != nil {
		t.Fatal(err)
	}
	if info.Endpoint != "185.107.56.1:51820" || !strings.Contains(info.Name, "Ronin") {
		t.Fatalf("info = %+v", info)
	}
	if _, err := Check(strings.Replace(good, "Endpoint", "#Endpoint", 1)); err == nil {
		t.Fatal("missing endpoint accepted")
	}
	if _, err := Check("hello world"); err == nil {
		t.Fatal("garbage accepted")
	}
	if _, err := Check(""); err == nil {
		t.Fatal("empty accepted")
	}
}

func TestConfigAndArgs(t *testing.T) {
	c := WireproxyConfig(`C:\x\wg.conf`)
	if !strings.Contains(c, `WGConfig = C:\x\wg.conf`) || !strings.Contains(c, "BindAddress = 127.0.0.1:40877") {
		t.Fatal(c)
	}
	a := strings.Join(BrowserArgs(), " ")
	if !strings.Contains(a, "socks5://127.0.0.1:40877") || !strings.Contains(a, "disable_non_proxied_udp") {
		t.Fatal(a)
	}
}

func fakeSocks(t *testing.T, reply byte, answer string) string {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { l.Close() })
	go func() {
		for {
			c, err := l.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) {
				defer c.Close()
				b := make([]byte, 300)
				io.ReadFull(c, b[:3])
				c.Write([]byte{5, 0})
				io.ReadFull(c, b[:5])
				io.ReadFull(c, b[:int(b[4])+2])
				c.Write([]byte{5, reply, 0, 1, 0, 0, 0, 0, 0, 0})
				if reply == 0 && answer != "" {
					c.Read(b)
					c.Write([]byte(answer))
				}
			}(c)
		}
	}()
	return l.Addr().String()
}

func TestSocksCheck(t *testing.T) {
	if err := SocksCheck(fakeSocks(t, 0, "HTTP/1.1 200 OK\r\n\r\n"), "www.gstatic.com:80", 2*time.Second); err != nil {
		t.Fatalf("working tunnel reported broken: %v", err)
	}
	if err := SocksCheck(fakeSocks(t, 0, ""), "www.gstatic.com:80", 2*time.Second); err == nil {
		t.Fatal("tunnel that returns no data reported working")
	}
	if err := SocksCheck(fakeSocks(t, 4, ""), "www.gstatic.com:80", 2*time.Second); err == nil {
		t.Fatal("broken tunnel reported working")
	}
	if err := SocksCheck("127.0.0.1:1", "www.gstatic.com:443", time.Second); err == nil {
		t.Fatal("dead proxy reported working")
	}
	if Next(0, 1) != 0 || Next(0, 3) != 1 || Next(2, 3) != 0 {
		t.Fatal("Next wrong")
	}
}

func TestPrepareConf(t *testing.T) {
	out := PrepareConf(good)
	if strings.Count(out, "MTU = 1280") != 1 || !strings.Contains(out, "Endpoint = 185.107.56.1:51820") {
		t.Fatal(out)
	}
	if i, j := strings.Index(out, "MTU"), strings.Index(out, "[Peer]"); i > j {
		t.Fatal("MTU must be in [Interface]")
	}
	withMTU := strings.Replace(good, "DNS = 10.2.0.1", "DNS = 10.2.0.1\r\nMTU = 1420", 1)
	out = PrepareConf(withMTU)
	if strings.Contains(out, "1420") || strings.Count(out, "MTU = 1280") != 1 {
		t.Fatal(out)
	}
	if _, err := Check(out); err != nil {
		t.Fatal(err)
	}
	only := "[Interface]\nPrivateKey = x\nAddress = 10.0.0.2/32"
	if !strings.HasSuffix(PrepareConf(only), "MTU = 1280") {
		t.Fatal("interface at the end")
	}
}

// forwardingSocks is a tiny SOCKS5 server that really connects (to dst).
func forwardingSocks(t *testing.T, dst string) string {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { l.Close() })
	go func() {
		for {
			c, err := l.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) {
				defer c.Close()
				b := make([]byte, 300)
				io.ReadFull(c, b[:3])
				c.Write([]byte{5, 0})
				io.ReadFull(c, b[:5])
				io.ReadFull(c, b[:int(b[4])+2])
				r, err := net.Dial("tcp", dst)
				if err != nil {
					c.Write([]byte{5, 5, 0, 1, 0, 0, 0, 0, 0, 0})
					return
				}
				defer r.Close()
				c.Write([]byte{5, 0, 0, 1, 0, 0, 0, 0, 0, 0})
				go io.Copy(r, c)
				io.Copy(c, r)
			}(c)
		}
	}()
	return l.Addr().String()
}

func TestSocksCheckTLS(t *testing.T) {
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}))
	defer srv.Close()
	pool := x509.NewCertPool()
	pool.AddCert(srv.Certificate())
	proxy := forwardingSocks(t, srv.Listener.Addr().String())
	if err := socksCheck(proxy, "example.com:443", 3*time.Second, &tls.Config{RootCAs: pool}); err != nil {
		t.Fatalf("working secure tunnel reported broken: %v", err)
	}
	// a tunnel where the secure handshake never completes (plain server that stays silent)
	silent, _ := net.Listen("tcp", "127.0.0.1:0")
	defer silent.Close()
	go func() {
		for {
			c, err := silent.Accept()
			if err != nil {
				return
			}
			go func() { time.Sleep(3 * time.Second); c.Close() }()
		}
	}()
	if err := socksCheck(forwardingSocks(t, silent.Addr().String()), "example.com:443", time.Second, &tls.Config{RootCAs: pool}); err == nil {
		t.Fatal("half-broken tunnel reported working")
	}
}
