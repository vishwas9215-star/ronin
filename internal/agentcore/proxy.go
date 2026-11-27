package agentcore

import (
	"context"
	"errors"
	"io"
	"net"
	"net/http"
	"strings"
	"syscall"
	"time"
)

// Proxy: every connection of the Claude tab goes through this small local proxy, which checks the address it is
// ACTUALLY connecting to (after DNS). So a name that answers "public" to one lookup and "192.168.1.1" to the next
// (DNS rebinding), a WebSocket, a service worker or a page script can never reach the home network, this PC or
// the server's own network. The browser is started with --proxy-server pointing here.
type Proxy struct {
	Addr    string               // 127.0.0.1:port, after Start
	Blocked func(addr string)    // told about every refused connection
	allow   func(ip net.IP) bool // tests may widen it; nil = public addresses only
	ln      net.Listener
	tr      *http.Transport
	dialer  *net.Dialer
}

var errPrivate = errors.New("home-network / private address refused by Ronin")

func (p *Proxy) ok(ip net.IP) bool {
	if p.allow != nil {
		return p.allow(ip)
	}
	return !PrivateAddress(ip)
}

// Start listens on a random port of 127.0.0.1.
func (p *Proxy) Start() error {
	p.dialer = &net.Dialer{Timeout: 20 * time.Second, KeepAlive: 30 * time.Second,
		Control: func(network, address string, _ syscall.RawConn) error {
			host, _, err := net.SplitHostPort(address)
			ip := net.ParseIP(host)
			if err != nil || ip == nil || !p.ok(ip) {
				if p.Blocked != nil {
					p.Blocked(address)
				}
				return errPrivate
			}
			return nil
		}}
	p.tr = &http.Transport{DialContext: p.dialer.DialContext, Proxy: nil, ForceAttemptHTTP2: false, MaxIdleConns: 50, IdleConnTimeout: 60 * time.Second}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return err
	}
	p.ln, p.Addr = ln, ln.Addr().String()
	go http.Serve(ln, p)
	return nil
}

func (p *Proxy) Close() {
	if p.ln != nil {
		p.ln.Close()
	}
}

// Dial: a connection through the same check (tests, and anything else of the agent that goes out).
func (p *Proxy) Dial(ctx context.Context, addr string) (net.Conn, error) {
	return p.dialer.DialContext(ctx, "tcp", addr)
}

var hopHeaders = []string{"Connection", "Proxy-Connection", "Keep-Alive", "Proxy-Authenticate", "Proxy-Authorization", "Te", "Trailer", "Transfer-Encoding", "Upgrade"}

func (p *Proxy) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodConnect { // HTTPS and secure WebSockets: a tunnel to a checked address
		c, err := p.Dial(r.Context(), r.Host)
		if err != nil {
			http.Error(w, "Blocked by Ronin", http.StatusForbidden)
			return
		}
		hj, ok := w.(http.Hijacker)
		if !ok {
			c.Close()
			return
		}
		cc, buf, err := hj.Hijack()
		if err != nil {
			c.Close()
			return
		}
		cc.Write([]byte("HTTP/1.1 200 Connection Established\r\n\r\n"))
		go func() {
			if buf.Reader.Buffered() > 0 {
				io.CopyN(c, buf, int64(buf.Reader.Buffered()))
			}
			io.Copy(c, cc)
			c.Close()
		}()
		io.Copy(cc, c)
		cc.Close()
		return
	}
	if r.URL.Scheme != "http" || r.URL.Host == "" {
		http.Error(w, "not a proxy request", http.StatusBadRequest)
		return
	}
	if strings.EqualFold(r.Header.Get("Upgrade"), "websocket") { // ws:// : tunnel the upgraded connection
		p.plainWebSocket(w, r)
		return
	}
	out := r.Clone(r.Context())
	out.RequestURI = ""
	for _, h := range hopHeaders {
		out.Header.Del(h)
	}
	res, err := p.tr.RoundTrip(out)
	if err != nil {
		http.Error(w, "Blocked by Ronin", http.StatusForbidden)
		return
	}
	defer res.Body.Close()
	for _, h := range hopHeaders {
		res.Header.Del(h)
	}
	for k, v := range res.Header {
		w.Header()[k] = v
	}
	w.WriteHeader(res.StatusCode)
	io.Copy(w, res.Body)
}

func (p *Proxy) plainWebSocket(w http.ResponseWriter, r *http.Request) {
	addr := r.URL.Host
	if !strings.Contains(addr, ":") {
		addr += ":80"
	}
	c, err := p.Dial(r.Context(), addr)
	if err != nil {
		http.Error(w, "Blocked by Ronin", http.StatusForbidden)
		return
	}
	hj, ok := w.(http.Hijacker)
	if !ok {
		c.Close()
		return
	}
	cc, _, err := hj.Hijack()
	if err != nil {
		c.Close()
		return
	}
	r.RequestURI = r.URL.RequestURI()
	r.Header.Del("Proxy-Connection")
	r.Write(c)
	go func() { io.Copy(c, cc); c.Close() }()
	io.Copy(cc, c)
	cc.Close()
}
