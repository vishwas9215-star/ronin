#!/bin/sh
# Ronin Agent Relay install (Linux server). Usage:  sudo sh install.sh https://relay.<name>  [amd64|arm64]
# Touches nothing else: own user, own folder, own port 127.0.0.1:8790. The HTTPS front (valid certificate) is
# added separately (see RELAY-HANDOFF.md). Opens pairing for 24 hours so the tablet / phone can register once.
set -e
PUBLIC="$1"; ARCH="${2:-$(uname -m | sed 's/x86_64/amd64/;s/aarch64/arm64/')}"
[ -n "$PUBLIC" ] || { echo "usage: sudo sh install.sh https://relay.example [amd64|arm64]"; exit 1; }
URL="https://github.com/vishwas9215-star/ronin/releases/download/agent-lab/roninrelay-linux-$ARCH"
id roninrelay >/dev/null 2>&1 || useradd --system --no-create-home --shell /usr/sbin/nologin roninrelay
mkdir -p /opt/roninrelay /var/lib/roninrelay
curl -fsSL "$URL" -o /opt/roninrelay/roninrelay.new
curl -fsSL "$URL.sha256" -o /opt/roninrelay/roninrelay.sha256
( cd /opt/roninrelay && echo "$(cat roninrelay.sha256)  roninrelay.new" | sha256sum -c - )
mv /opt/roninrelay/roninrelay.new /opt/roninrelay/roninrelay && chmod 755 /opt/roninrelay/roninrelay
chown -R roninrelay:roninrelay /var/lib/roninrelay && chmod 700 /var/lib/roninrelay
echo "RELAY_PUBLIC=$PUBLIC" > /etc/roninrelay.env
date -u -d '+24 hours' +%Y-%m-%dT%H:%M:%SZ > /var/lib/roninrelay/pair-until && chown roninrelay /var/lib/roninrelay/pair-until
curl -fsSL "https://raw.githubusercontent.com/vishwas9215-star/ronin/claude/agent-pilot/tools/relay-deploy/roninrelay.service" -o /etc/systemd/system/roninrelay.service
systemctl daemon-reload && systemctl enable --now roninrelay
sleep 2; systemctl --no-pager status roninrelay | head -5
curl -fsS http://127.0.0.1:8790/.well-known/ronin-relay.json && echo && echo "OK: now add the HTTPS front for $PUBLIC -> 127.0.0.1:8790"
