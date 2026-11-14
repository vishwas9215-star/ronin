package vault

import (
	"bytes"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// xorCipher stands in for DPAPI in tests (key bound to "this account").
type xorCipher byte

func (k xorCipher) Seal(b []byte) ([]byte, error) {
	out := []byte("SEAL")
	for _, c := range b {
		out = append(out, c^byte(k))
	}
	return out, nil
}
func (k xorCipher) Open(b []byte) ([]byte, error) {
	if !bytes.HasPrefix(b, []byte("SEAL")) {
		return nil, errors.New("bad")
	}
	var out []byte
	for _, c := range b[4:] {
		out = append(out, c^byte(k))
	}
	return out, nil
}

func TestOrigin(t *testing.T) {
	for in, want := range map[string]string{
		"https://www.Facebook.com/login?x=1": "https://facebook.com",
		"https://accounts.google.com:443/a":  "https://accounts.google.com",
		"https://example.com:8443/":          "https://example.com:8443",
		"http://example.com/login":           "", // never on plain http
		"http://localhost:8080/":             "http://localhost:8080",
		"ftp://x.com":                        "",
		"javascript:alert(1)":                "",
		"":                                   "",
	} {
		if got := Origin(in); got != want {
			t.Errorf("%q = %q, want %q", in, got, want)
		}
	}
}

func TestVault(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "passwords.dat")
	v, err := Open(p, xorCipher(7))
	if err != nil {
		t.Fatal(err)
	}
	if v.Status("https://www.drishtiias.com/login", "vishwas", "s3cret") != "new" {
		t.Error("status new")
	}
	if err := v.Put("https://www.drishtiias.com/login", "vishwas", "s3cret", ""); err != nil {
		t.Fatal(err)
	}
	if err := v.Put("http://insecure.com/", "a", "b", ""); err == nil {
		t.Error("saved for plain http")
	}
	raw, _ := os.ReadFile(p)
	if bytes.Contains(raw, []byte("s3cret")) || bytes.Contains(raw, []byte("vishwas")) {
		t.Fatal("file is not encrypted")
	}
	v2, err := Open(p, xorCipher(7))
	if err != nil {
		t.Fatal(err)
	}
	if f := v2.Find("https://drishtiias.com/other"); len(f) != 1 || f[0].Password != "s3cret" {
		t.Errorf("find %+v", f)
	}
	for _, bad := range []string{"https://drishtiias.com.evil.in/", "https://evil.com/?u=https://drishtiias.com", "https://xdrishtiias.com/", "https://sub.drishtiias.com/"} {
		if len(v2.Find(bad)) != 0 {
			t.Error("filled on wrong site:", bad)
		}
	}
	if v2.Status("https://drishtiias.com/", "vishwas", "s3cret") != "same" || v2.Status("https://drishtiias.com/", "vishwas", "new") != "update" {
		t.Error("status same/update")
	}
	_ = v2.Never("https://spam.com/")
	if v2.Status("https://www.spam.com/x", "a", "b") != "never" {
		t.Error("never")
	}
	if _, err := Open(p, xorCipher(9)); err != nil && err != ErrLocked {
		t.Error("wrong key error type")
	}
	os.WriteFile(p+"x", []byte("junk"), 0o600)
	if _, err := Open(p+"x", xorCipher(7)); err != ErrLocked {
		t.Error("broken file not locked")
	}
	_ = v2.Remove("https://drishtiias.com", "vishwas")
	if v2.Len() != 0 {
		t.Error("remove")
	}
}

func TestCSV(t *testing.T) {
	v, _ := Open(filepath.Join(t.TempDir(), "p.dat"), xorCipher(1))
	chrome := "name,url,username,password,note\n" +
		"drishti,https://www.drishtiias.com/login,vishwas,\"pa\"\"ss\",\n" +
		"gmail,https://accounts.google.com/,me@gmail.com,\"comma,pass\",my note\n" +
		"old,http://plain.com/,u,p,\n" +
		"empty,https://x.com/,u,,\n"
	added, skipped, err := v.ImportCSV(strings.NewReader("\ufeff" + chrome))
	if err != nil || added != 2 || skipped != 2 {
		t.Fatalf("chrome import %d %d %v", added, skipped, err)
	}
	bw := "folder,favorite,type,name,notes,fields,reprompt,login_uri,login_username,login_password,login_totp\n" +
		",,login,SBI,,,0,https://onlinesbi.sbi/,user1,pw1,\n" +
		",,login,again,,,0,https://accounts.google.com/,me@gmail.com,comma,pass2,\n"
	added, _, err = v.ImportCSV(strings.NewReader(bw))
	if err != nil || added != 2 || v.Len() != 3 {
		t.Fatalf("bitwarden import %d %v len %d", added, err, v.Len())
	}
	if f := v.Find("https://accounts.google.com/x"); len(f) != 1 || f[0].Password != "comma" || f[0].Note != "my note" {
		t.Errorf("update by import %+v", f)
	}
	var buf bytes.Buffer
	if err := v.ExportCSV(&buf); err != nil {
		t.Fatal(err)
	}
	v2, _ := Open(filepath.Join(t.TempDir(), "q.dat"), xorCipher(1))
	if a, _, err := v2.ImportCSV(&buf); err != nil || a != 3 {
		t.Errorf("round trip %d %v", a, err)
	}
	if f := v2.Find("https://drishtiias.com/"); len(f) != 1 || f[0].Password != `pa"ss` {
		t.Errorf("quotes lost %+v", f)
	}
	if _, _, err := v2.ImportCSV(strings.NewReader("a,b\n1,2\n")); err == nil {
		t.Error("wrong file accepted")
	}
}
