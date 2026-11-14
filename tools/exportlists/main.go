// exportlists writes Ronin's shared rules for the Android app.
//
//	go run ./tools/exportlists -assets android/app/src/main/assets/ronin
//	    rules.json (bank / trading / call / payment sites, ad rules, study-time sites)
//	    and the shared page scripts.
//	go run ./tools/exportlists -lists out/
//	    downloads the public ad lists and writes adblock-domains.txt.gz (one domain
//	    per line, the same parsing as Windows). The build machine publishes it daily.
package main

import (
	"bytes"
	"compress/gzip"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"time"

	"roninbrowser/internal/adblock"
	"roninbrowser/internal/focus"
	"roninbrowser/internal/quicklinks"
	"roninbrowser/internal/safemoney"
)

func main() {
	assets := flag.String("assets", "", "Android assets folder to fill")
	lists := flag.String("lists", "", "folder for the downloaded ad-list file")
	flag.Parse()
	if *assets == "" && *lists == "" {
		fmt.Fprintln(os.Stderr, "use -assets DIR and/or -lists DIR")
		os.Exit(2)
	}
	if *assets != "" {
		check(writeAssets(*assets))
	}
	if *lists != "" {
		check(writeLists(*lists))
	}
}

func check(err error) {
	if err != nil {
		fmt.Fprintln(os.Stderr, "exportlists:", err)
		os.Exit(1)
	}
}

func sorted(in []string) []string {
	out := append([]string(nil), in...)
	sort.Strings(out)
	return out
}

func writeAssets(dir string) error {
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	block, allow := adblock.New().Snapshot()
	third, paths := adblock.Extra()
	rules := map[string]any{
		"sites":         safemoney.Export(),
		"adBlock":       sorted(block),
		"adAllow":       sorted(allow),
		"thirdParty":    third,
		"adPaths":       paths,
		"focusSites":    focus.DefaultSites,
		"lookalike":     safemoney.LookalikeData(),
		"quickLinks":    quicklinks.Defaults,
		"quickSections": quicklinks.Sections,
		"testThreat":    adblock.TestThreat,
	}
	b, err := json.MarshalIndent(rules, "", " ")
	if err != nil {
		return err
	}
	if err := os.WriteFile(filepath.Join(dir, "rules.json"), b, 0o644); err != nil {
		return err
	}
	// page scripts shared with Windows
	for _, f := range []string{"inject_adblock.js", "quicklinks.js", "table_export.js"} {
		src, err := os.ReadFile(filepath.Join("cmd", "ronin", "ui", f))
		if err != nil {
			return err
		}
		if err := os.WriteFile(filepath.Join(dir, f), src, 0o644); err != nil {
			return err
		}
	}
	// the PDF / Photo tools page with its libraries
	for _, f := range []string{"index.html", "tools.js", "merge.js", "lib/pdf-lib.min.js", "lib/pdf.min.mjs", "lib/pdf.worker.min.mjs", "lib/LICENSE-pdf-lib.md", "lib/LICENSE-pdfjs.txt"} {
		src, err := os.ReadFile(filepath.Join("cmd", "ronin", "ui", "tools", f))
		if err != nil {
			return err
		}
		dst := filepath.Join(dir, "tools", filepath.FromSlash(f))
		if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
			return err
		}
		if err := os.WriteFile(dst, src, 0o644); err != nil {
			return err
		}
	}
	fmt.Printf("assets: %d ad domains, %d bank sites\n", len(block), len(safemoney.Export()["bank"]))
	return nil
}

func writeLists(dir string) error {
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	e := adblock.New()
	client := &http.Client{Timeout: 2 * time.Minute}
	for _, l := range adblock.Lists {
		resp, err := client.Get(l.URL)
		if err != nil {
			fmt.Println("skip", l.Name, err)
			continue
		}
		body, err := io.ReadAll(io.LimitReader(resp.Body, 64<<20))
		resp.Body.Close()
		if err != nil || resp.StatusCode != 200 {
			fmt.Println("skip", l.Name, resp.StatusCode, err)
			continue
		}
		block, _ := adblock.ParseList(bytes.NewReader(body))
		if len(block) < l.Min {
			fmt.Println("skip", l.Name, "too few rules:", len(block))
			continue
		}
		fmt.Println(l.Name, len(block))
		if l.Threat {
			e.LoadThreat(bytes.NewReader(body))
		} else {
			e.LoadList(bytes.NewReader(body))
		}
	}
	if err := writeThreats(dir, e.Threats()); err != nil {
		fmt.Println("danger list skipped:", err) // the ad list is still published
	}
	block, allow := e.Snapshot()
	if len(block) < 50000 {
		return fmt.Errorf("only %d domains - lists look broken, not publishing", len(block))
	}
	var buf bytes.Buffer
	zw := gzip.NewWriter(&buf)
	fmt.Fprintf(zw, "# Ronin ad-block domains %s\n", time.Now().UTC().Format(time.RFC3339))
	for _, d := range sorted(block) {
		fmt.Fprintln(zw, d)
	}
	for _, d := range sorted(allow) {
		fmt.Fprintln(zw, "@@"+d)
	}
	if err := zw.Close(); err != nil {
		return err
	}
	fmt.Printf("lists: %d blocked, %d allowed, %d bytes\n", len(block), len(allow), buf.Len())
	return os.WriteFile(filepath.Join(dir, "adblock-domains.txt.gz"), buf.Bytes(), 0o644)
}

// writeThreats writes the phishing / malware sites (whole site is stopped
// with a red page on Android).
func writeThreats(dir string, threats []string) error {
	if len(threats) < 1000 {
		return fmt.Errorf("only %d dangerous sites - lists look broken, not publishing", len(threats))
	}
	var buf bytes.Buffer
	zw := gzip.NewWriter(&buf)
	fmt.Fprintf(zw, "# Ronin danger list (phishing / malware) %s\n", time.Now().UTC().Format(time.RFC3339))
	for _, d := range sorted(threats) {
		fmt.Fprintln(zw, d)
	}
	if err := zw.Close(); err != nil {
		return err
	}
	fmt.Printf("threats: %d sites, %d bytes\n", len(threats), buf.Len())
	return os.WriteFile(filepath.Join(dir, "threat-domains.txt.gz"), buf.Bytes(), 0o644)
}
