#!/bin/sh
# Builds Ronin.exe and RoninSetup.exe for 64-bit Windows.
set -e
cd "$(dirname "$0")"
VERSION="${1:-$(cat VERSION)}"
export GOOS=windows GOARCH=amd64 CGO_ENABLED=0 GOFLAGS=-mod=mod GOPROXY="${GOPROXY:-off}"

GOOS= GOARCH= go test ./internal/...
go vet ./internal/...
node tools/jstest/scam_test.js
node tools/jstest/adblock_test.js
NODE_PATH=$(npm root -g) node tools/uitest/passwords.js
NODE_PATH=$(npm root -g) node tools/uitest/voice.js /tmp/ronin-voice.png
NODE_PATH=$(npm root -g) node tools/uitest/listen.js /tmp/ronin-listen.png
mkdir -p /tmp/ronin-ui && NODE_PATH=$(npm root -g) node tools/uitest/extras.js /tmp/ronin-ui
mkdir -p /tmp/ronin-ui && NODE_PATH=$(npm root -g) node tools/uitest/newtab.js /tmp/ronin-ui
NODE_PATH=$(npm root -g) node tools/uitest/popguard.js
NODE_PATH=$(npm root -g) node tools/uitest/tableexport.js
NODE_PATH=$(npm root -g) node tools/uitest/tableexport2.js
NODE_PATH=$(npm root -g) node tools/uitest/darkcsp.js
NODE_PATH=$(npm root -g) node tools/uitest/merge.js /tmp/ronin-ui
python3 tools/uitest/make_tool_fixtures.py /tmp/toolfx && NODE_PATH=$(npm root -g) node tools/uitest/pdftools.js /tmp/ronin-ui
go build -trimpath -ldflags "-H=windowsgui -s -w -X main.Version=$VERSION" -o build/Ronin.exe ./cmd/ronin
mkdir -p cmd/setup/payload
cp build/Ronin.exe cmd/setup/payload/Ronin.exe
cp cmd/ronin/res/ronin.ico cmd/setup/payload/ronin.ico
go build -trimpath -ldflags "-H=windowsgui -s -w -X main.Version=$VERSION" -o build/RoninSetup.exe ./cmd/setup
sha256sum build/Ronin.exe build/RoninSetup.exe > build/SHA256SUMS.txt
ls -la build
