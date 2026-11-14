package tablexport

import (
	"archive/zip"
	"bytes"
	"encoding/xml"
	"io"
	"os"
	"strings"
	"testing"
)

// The JSON comes from tools/uitest/tableexport.js (the real page script); a copy is kept here.
func load(t *testing.T) *Data {
	b, err := os.ReadFile("testdata/result.json")
	if err != nil {
		t.Fatal(err)
	}
	d, err := Parse(string(b))
	if err != nil {
		t.Fatal(err)
	}
	return d
}

func TestXLSXIsValidAndColoured(t *testing.T) {
	d := load(t)
	x, err := d.XLSX()
	if err != nil {
		t.Fatal(err)
	}
	zr, err := zip.NewReader(bytes.NewReader(x), int64(len(x)))
	if err != nil {
		t.Fatal(err)
	}
	var sheet string
	for _, f := range zr.File {
		rc, _ := f.Open()
		b, _ := io.ReadAll(rc)
		rc.Close()
		// every part must be well-formed XML (Excel refuses broken files)
		dec := xml.NewDecoder(bytes.NewReader(b))
		for {
			if _, err := dec.Token(); err == io.EOF {
				break
			} else if err != nil {
				t.Fatalf("%s: %v", f.Name, err)
			}
		}
		if f.Name == "xl/worksheets/sheet1.xml" {
			sheet = string(b)
		}
	}
	if !strings.Contains(sheet, "Rahul Kumr") || !strings.Contains(sheet, `s="4"`) || !strings.Contains(sheet, `s="3"`) || !strings.Contains(sheet, `s="2"`) {
		t.Fatal("colours or data missing")
	}
	if strings.Count(sheet, "<row ") != len(d.Rows)+1 {
		t.Fatal("row count")
	}
	if !strings.Contains(sheet, "मिलता-जुलता नाम") {
		t.Fatal("check notes missing")
	}
	if os.Getenv("SAVE") != "" {
		_ = os.WriteFile(os.Getenv("SAVE"), x, 0o644)
	}
}

func TestReportHTML(t *testing.T) {
	h := load(t).ReportHTML()
	if !strings.Contains(h, `class="spell"`) || !strings.Contains(h, "Rahul Kumr") || !strings.Contains(h, "A4 landscape") {
		t.Fatal("report")
	}
	if _, err := Parse(`{"rows":[]}`); err == nil {
		t.Fatal("empty table accepted")
	}
}

func TestColName(t *testing.T) {
	for i, want := range map[int]string{0: "A", 25: "Z", 26: "AA", 27: "AB", 701: "ZZ", 702: "AAA"} {
		if colName(i) != want {
			t.Errorf("%d: %s", i, colName(i))
		}
	}
}
