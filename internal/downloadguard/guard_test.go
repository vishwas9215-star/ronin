package downloadguard

import (
	"strings"
	"testing"
)

func TestCheckLevels(t *testing.T) {
	cases := []struct {
		name string
		mode Mode
		want Level
	}{
		{"statement.pdf", Normal, Safe},
		{"photo.JPG", Normal, Safe},
		{"setup.exe", Normal, Dangerous},
		{"invoice.pdf.exe", Normal, Dangerous},
		{"Run.BAT", Normal, Dangerous},
		{"update.ps1", Normal, Dangerous},
		{"shortcut.lnk", Normal, Dangerous},
		{"game.apk", Normal, Dangerous},
		{"disk.iso", Normal, Dangerous},
		{"files.zip", Normal, Caution},
		{"report.xlsm", Normal, Caution},
		{"noextension", Normal, Caution},
		{"photo‮gpj.exe", Normal, Dangerous},
		{"statement.pdf", BankingMode, Safe},
		{"statement.csv", BankingMode, Safe},
		{"setup.exe", BankingMode, Blocked},
		{"files.zip", BankingMode, Blocked},
		{"homework.pdf", KidsMode, Safe},
		{"game.exe", KidsMode, Blocked},
		{"song.mp3", KidsMode, Safe},
	}
	for _, c := range cases {
		if got := Check(c.name, c.mode); got.Level != c.want {
			t.Errorf("Check(%q,%v) = %v (%s), want %v", c.name, c.mode, got.Level, got.Reason, c.want)
		}
	}
	if r := Check("invoice.pdf.exe", Normal); !strings.Contains(r.Reason, ".pdf") {
		t.Errorf("double extension not explained: %q", r.Reason)
	}
}

func TestSanitizeName(t *testing.T) {
	cases := map[string]string{
		`..\..\Windows\evil.exe`: "evil.exe",
		"a<b>c:d.pdf":            "a_b_c_d.pdf",
		"CON.txt":                "_CON.txt",
		"report.pdf. ":           "report.pdf",
		"":                       "download",
		"x‮gpj.exe":              "xgpj.exe",
		"../":                    "download",
	}
	for in, want := range cases {
		if got := SanitizeName(in); got != want {
			t.Errorf("SanitizeName(%q) = %q want %q", in, got, want)
		}
	}
	long := strings.Repeat("a", 300) + ".pdf"
	if got := SanitizeName(long); len(got) > 200 || !strings.HasSuffix(got, ".pdf") {
		t.Errorf("long name not shortened properly: %d", len(got))
	}
}

func TestZoneIdentifier(t *testing.T) {
	z := ZoneIdentifier("https://user:pass@files.example.com/a.exe?token=SECRET#x", "https://site.example.com/page?id=9")
	if !strings.Contains(z, "ZoneId=3") || strings.Contains(z, "SECRET") || strings.Contains(z, "pass") || strings.Contains(z, "id=9") {
		t.Fatalf("bad zone identifier: %q", z)
	}
	if !strings.Contains(z, "HostUrl=https://files.example.com/a.exe\r\n") {
		t.Fatalf("host url missing: %q", z)
	}
	if strings.Contains(ZoneIdentifier("https://a.com/\r\nZoneId=0", ""), "ZoneId=0") {
		t.Fatal("newline injection not stopped")
	}
}
