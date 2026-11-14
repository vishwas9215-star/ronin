// Command release makes the signed update files for one Ronin version:
//
//	go run ./tools/release -version 3.2.0 -exe build/Ronin.exe \
//	    -base https://github.com/OWNER/ronin-updates/releases/download/v3.2.0 \
//	    -key release-key/ronin-release-private.key -out build/release
//
// It writes manifest.json and manifest.json.sig. Upload both, together with
// Ronin.exe, to the GitHub release. Ronin installs an update only when the
// signature matches the public key built into it.
package main

import (
	"crypto/ed25519"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"errors"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

	"roninbrowser/internal/updater"
)

// must match releasePublicKey in cmd/ronin/update_windows.go
const publicKey = "1sMBFc7MJJjpIS98PAV3LAvsgqdWjAiDV94YG/dA61k="

func main() {
	version := flag.String("version", "", "version, e.g. 3.2.0")
	exe := flag.String("exe", "build/Ronin.exe", "the new Ronin.exe")
	base := flag.String("base", "", "https address of the folder where Ronin.exe will be")
	keyPath := flag.String("key", "release-key/ronin-release-private.key", "private signing key (never shared)")
	out := flag.String("out", "build/release", "output folder")
	flag.Parse()
	if err := run(*version, *exe, *base, *keyPath, *out); err != nil {
		fmt.Fprintln(os.Stderr, "release:", err)
		os.Exit(1)
	}
}

func run(version, exe, base, keyPath, out string) error {
	if version == "" || !strings.HasPrefix(base, "https://") {
		return errors.New("-version and an https -base are needed")
	}
	priv, err := loadKey(keyPath)
	if err != nil {
		return err
	}
	if base64.StdEncoding.EncodeToString(priv.Public().(ed25519.PublicKey)) != publicKey {
		return errors.New("this key does not belong to Ronin's public key; refusing to sign")
	}
	data, err := os.ReadFile(exe)
	if err != nil {
		return err
	}
	sum := sha256.Sum256(data)
	m := updater.Manifest{App: "ronin", Version: version, Date: time.Now().UTC().Truncate(time.Second),
		Files: []updater.File{{Name: "Ronin.exe", URL: strings.TrimSuffix(base, "/") + "/Ronin.exe",
			SHA256: hex.EncodeToString(sum[:]), Size: int64(len(data))}}}
	js, err := json.MarshalIndent(m, "", "  ")
	if err != nil {
		return err
	}
	sig := base64.StdEncoding.EncodeToString(ed25519.Sign(priv, js))
	// check it the way Ronin will
	if _, err := updater.VerifyManifest(js, sig, priv.Public().(ed25519.PublicKey)); err != nil {
		return err
	}
	if err := os.MkdirAll(out, 0o755); err != nil {
		return err
	}
	if err := os.WriteFile(filepath.Join(out, "manifest.json"), js, 0o644); err != nil {
		return err
	}
	if err := os.WriteFile(filepath.Join(out, "manifest.json.sig"), []byte(sig), 0o644); err != nil {
		return err
	}
	fmt.Println("signed", version, "->", out)
	return nil
}

// loadKey reads the private key in any of the usual forms, without printing it.
func loadKey(path string) (ed25519.PrivateKey, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	if b, _ := pem.Decode(raw); b != nil {
		k, err := x509.ParsePKCS8PrivateKey(b.Bytes)
		if err == nil {
			if p, ok := k.(ed25519.PrivateKey); ok {
				return p, nil
			}
		}
		return nil, errors.New("PEM key is not an Ed25519 key")
	}
	for _, cand := range [][]byte{raw, decodeB64(raw), decodeHex(raw)} {
		switch len(cand) {
		case ed25519.PrivateKeySize:
			return ed25519.PrivateKey(cand), nil
		case ed25519.SeedSize:
			return ed25519.NewKeyFromSeed(cand), nil
		}
	}
	return nil, errors.New("key file format not understood")
}

func decodeB64(b []byte) []byte {
	d, err := base64.StdEncoding.DecodeString(strings.TrimSpace(string(b)))
	if err != nil {
		return nil
	}
	return d
}

func decodeHex(b []byte) []byte {
	d, err := hex.DecodeString(strings.TrimSpace(string(b)))
	if err != nil {
		return nil
	}
	return d
}
