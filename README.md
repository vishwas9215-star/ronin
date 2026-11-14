# Ronin Browser 3

Windows browser on Microsoft WebView2 (engine updated automatically by Microsoft).

- `cmd/ronin` – the browser (Win32 window + WebView2, toolbar UI in `cmd/ronin/ui`)
- `cmd/setup` – RoninSetup.exe (per-user install, shortcuts, uninstall)
- `internal/safemoney` – bank / fake-bank detection, remote-access tools, antivirus status
- `internal/containers` – which window (PERSONAL, BANKING, TRADING, KIDS, PRIVATE) a page belongs to
- `internal/downloadguard` – risky downloads, Mark of the Web
- `internal/updater` – signed self-update (Ed25519 + SHA-256, self-test, rollback)
- `internal/omnibox`, `internal/settings`
- `third_party/go-webview2` – MIT library, patched so a failed call never closes the browser
- `third_party/xsys` – golang.org/x/sys v0.27.0 (BSD)

Build (Linux or Windows, Go 1.24): `./build.sh 3.0.0` → `build/RoninSetup.exe`.
Unit tests: `go test ./internal/...`.

The release signing private key is NOT in this folder; keep it safe and never share it.
