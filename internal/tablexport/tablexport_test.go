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

func TestNumbersAndPartial(t *testing.T) {
	for v, want := range map[string]bool{"40": true, "38.5": true, "-3": true, "0": true, "0123": false, "123456789012": false, "9876543210": false, "5A": false, "": false, ".5": false} {
		if number(v) != want {
			t.Errorf("%q: %v", v, !want)
		}
	}
	d := load(t)
	d.Partial = "page 7 did not load"
	if !strings.Contains(d.ReportHTML(), "NOT complete: page 7") {
		t.Fatal("partial not shown")
	}
}

// RENDER=/path/data.json go test -run TestRender writes data.xlsx and data.html next to it (for checking by eye).
func TestRender(t *testing.T) {
	in := os.Getenv("RENDER")
	if in == "" {
		t.Skip()
	}
	b, _ := os.ReadFile(in)
	d, err := Parse(string(b))
	if err != nil {
		t.Fatal(err)
	}
	x, _ := d.XLSX()
	_ = os.WriteFile(strings.TrimSuffix(in, ".json")+".xlsx", x, 0o644)
	_ = os.WriteFile(strings.TrimSuffix(in, ".json")+".html", []byte(d.ReportHTML()), 0o644)
}

// "डेटा मिलाओ" output (made-up children, from tools/uitest/merge.js): blue / grey marks,
// its own summary and legend, and a PDF limited to the chosen columns.
func TestMergeResult(t *testing.T) {
	b, err := os.ReadFile("testdata/merge.json")
	if err != nil {
		t.Fatal(err)
	}
	d, err := Parse(string(b))
	if err != nil {
		t.Fatal(err)
	}
	x, err := d.XLSX()
	if err != nil {
		t.Fatal(err)
	}
	zr, _ := zip.NewReader(bytes.NewReader(x), int64(len(x)))
	parts := map[string]string{}
	for _, f := range zr.File {
		r, _ := f.Open()
		var buf bytes.Buffer
		_, _ = buf.ReadFrom(r)
		parts[f.Name] = buf.String()
		if strings.HasSuffix(f.Name, ".xml") {
			if err := xml.Unmarshal(buf.Bytes(), new(struct{})); err != nil {
				t.Fatal(f.Name, err)
			}
		}
	}
	if !strings.Contains(parts["xl/worksheets/sheet1.xml"], `s="5"`) || !strings.Contains(parts["xl/worksheets/sheet1.xml"], `s="6"`) {
		t.Fatal("blue / grey marks missing")
	}
	if !strings.Contains(parts["xl/worksheets/sheet2.xml"], "अंतर वाले बच्चे") || !strings.Contains(parts["xl/worksheets/sheet2.xml"], "नीला") {
		t.Fatal("merge summary / legend missing")
	}
	keep, _ := d.PrintColumns()
	if len(d.PrintCols) == 0 || len(keep) > len(d.PrintCols) {
		t.Fatal("print columns not used", keep, d.PrintCols)
	}
	h := d.ReportHTML()
	if !strings.Contains(h, `class="diff"`) || !strings.Contains(h, `class="k only"`) || !strings.Contains(h, "धूसर") {
		t.Fatal("report marks")
	}
}
