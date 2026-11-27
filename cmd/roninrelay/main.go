// roninrelay: the Ronin Agent Relay (see internal/agentrelay). One small binary, no browser, about 10-30 MB of RAM.
//
//	roninrelay -data /var/lib/roninrelay -addr 127.0.0.1:8790 -public https://relay.example
//	roninrelay -data ... -pair 15m      also lets new devices pair for 15 minutes
//	roninrelay -data ... -pubkey        prints the relay's public key (baked into the Ronin apps)
package main

import (
	"flag"
	"fmt"
	"log"
	"net/http"
	"time"

	"roninbrowser/internal/agentrelay"
)

func main() {
	data := flag.String("data", "/var/lib/roninrelay", "folder for the relay key, paired devices and tokens (0700)")
	addr := flag.String("addr", "127.0.0.1:8790", "listen address (behind the server's HTTPS front)")
	public := flag.String("public", "", "public https address, e.g. https://relay.example (no slash)")
	cert := flag.String("cert", "", "TLS certificate file (optional: serve HTTPS directly)")
	key := flag.String("key", "", "TLS key file")
	pair := flag.Duration("pair", 0, "let NEW devices pair for this long (e.g. 15m)")
	pub := flag.Bool("pubkey", false, "print the relay public key and exit")
	flag.Parse()
	r, err := agentrelay.New(agentrelay.Config{DataDir: *data, Public: *public})
	if err != nil {
		log.Fatal(err)
	}
	if *pub {
		fmt.Print(r.PublicKeyPEM())
		return
	}
	if *pair > 0 {
		r.OpenPairing(*pair)
		log.Printf("pairing open for %s", *pair)
	}
	srv := &http.Server{Addr: *addr, Handler: r.Handler(), ReadHeaderTimeout: 10 * time.Second, IdleTimeout: 120 * time.Second}
	log.Printf("Ronin Agent Relay on %s (public %s)", *addr, *public)
	if *cert != "" {
		log.Fatal(srv.ListenAndServeTLS(*cert, *key))
	}
	log.Fatal(srv.ListenAndServe())
}
