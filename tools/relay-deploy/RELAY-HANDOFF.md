# RELAY-HANDOFF — install the Ronin Agent Relay on the owner's server (for the chat that manages the server)

Written 27 Nov 2026 by the Ronin Browser chat. The owner (Vishwas) asked for this; he will say
"Ronin Browser project का RELAY-HANDOFF लगाओ". Please do ONLY what is below. Do not touch the trading engine,
the terminal, or the "Ronin Claude Browser" service.

## What it is
A small Go program (about 10-30 MB RAM, no browser, no page content stored). It lets Claude (one custom connector)
reach the owner's OWN Ronin app on his tablet / phone (HTTPS long polling from the devices). All safety rules
(no passwords / OTP, no orders, money sites read-only) run on the devices. Source: github.com/vishwas9215-star/ronin
branch `claude/agent-pilot`, `internal/agentrelay` + `cmd/roninrelay` (tests: `go test ./internal/agentrelay`).

## Steps (server 16.4.152.212)
1. Install (own user `roninrelay`, folder /opt/roninrelay + /var/lib/roninrelay, port 127.0.0.1:8790 only,
   systemd MemoryMax=96M, CPUQuota=25%, Nice=10):
   `curl -fsSL https://raw.githubusercontent.com/vishwas9215-star/ronin/claude/agent-pilot/tools/relay-deploy/install.sh -o /tmp/ri.sh && sudo sh /tmp/ri.sh https://relay-16-4-152-212.sslip.io`
   (the script checks the binary's sha256 and opens device pairing for 24 hours).
2. HTTPS front with a VALID certificate (Claude's connectors refuse self-signed ones). Use whatever front the server
   already has for the "Ronin Claude Browser" connector, and add a NEW host name for the relay, e.g.
   `relay-16-4-152-212.sslip.io` → `http://127.0.0.1:8790` (whole host, all paths: /mcp, /.well-known/*,
   /authorize, /token, /register, /agent/*). Long polls last 25 s: proxy read timeout >= 60 s, no buffering.
   Caddy example:
   ```
   relay-16-4-152-212.sslip.io {
       reverse_proxy 127.0.0.1:8790 {
           flush_interval -1
           transport http { read_timeout 70s }
       }
   }
   ```
   If the front is nginx: `proxy_pass http://127.0.0.1:8790; proxy_read_timeout 70s; proxy_buffering off;`
   plus a Let's Encrypt certificate for that name. Port 443 must be open in the AWS security group.
3. Check from outside: `curl https://relay-16-4-152-212.sslip.io/.well-known/ronin-relay.json` shows `pub`.
4. Tell the owner (Hindi): "Relay लग गया: https://relay-16-4-152-212.sslip.io". He passes the address to the
   Ronin Browser chat, which bakes the public key into the Ronin app and pairs his tablet + phone.

If the name above is not possible, any https name that reaches 127.0.0.1:8790 works; just report it.
Remove: `sudo systemctl disable --now roninrelay && sudo rm -rf /opt/roninrelay /var/lib/roninrelay /etc/roninrelay.env /etc/systemd/system/roninrelay.service && sudo userdel roninrelay`
