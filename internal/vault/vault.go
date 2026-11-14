// Package vault is Ronin's password store. The file on disk is always
// encrypted (on Windows with the user's own Windows key, DPAPI), so another
// Windows user or a copied file cannot read it. Passwords are matched to the
// exact site (scheme + host, "www." ignored) and are filled only after the
// user clicks "Fill".
package vault

import (
	"encoding/csv"
	"encoding/json"
	"errors"
	"io"
	"net/url"
	"os"
	"sort"
	"strings"
	"sync"
	"time"
)

// Entry is one saved login.
type Entry struct {
	Origin   string `json:"o"` // "https://accounts.example.com"
	Username string `json:"u"`
	Password string `json:"p"`
	Note     string `json:"n,omitempty"`
	Changed  int64  `json:"c"`
}

// Cipher encrypts the whole file (DPAPI on Windows).
type Cipher interface {
	Seal([]byte) ([]byte, error)
	Open([]byte) ([]byte, error)
}

type fileData struct {
	Entries []Entry  `json:"entries"`
	Never   []string `json:"never,omitempty"` // sites where the user said "Never save"
}

// Vault is the open password store.
type Vault struct {
	mu   sync.Mutex
	path string
	c    Cipher
	d    fileData
	now  func() time.Time
}

// MaxEntries keeps the file small and the lists quick.
const MaxEntries = 5000

// ErrLocked: the file exists but cannot be decrypted (other Windows user, damaged).
var ErrLocked = errors.New("the password file could not be opened on this Windows account")

// Open reads the store (a missing file is an empty store).
func Open(path string, c Cipher) (*Vault, error) {
	v := &Vault{path: path, c: c, now: time.Now}
	b, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return v, nil
	}
	if err != nil {
		return nil, err
	}
	plain, err := c.Open(b)
	if err != nil {
		return nil, ErrLocked
	}
	if err := json.Unmarshal(plain, &v.d); err != nil {
		return nil, ErrLocked
	}
	return v, nil
}

// Origin turns a page address into the site key: "https://host[:port]".
// Only https (and http on this computer) can hold passwords.
func Origin(raw string) string {
	u, err := url.Parse(strings.TrimSpace(raw))
	if err != nil || u.Host == "" {
		return ""
	}
	scheme := strings.ToLower(u.Scheme)
	host := strings.TrimSuffix(strings.ToLower(u.Hostname()), ".")
	host = strings.TrimPrefix(host, "www.")
	if host == "" {
		return ""
	}
	local := host == "localhost" || host == "127.0.0.1"
	if scheme != "https" && !(scheme == "http" && local) {
		return ""
	}
	if p := u.Port(); p != "" && !(scheme == "https" && p == "443") {
		host += ":" + p
	}
	return scheme + "://" + host
}

func (v *Vault) save() error {
	b, err := json.Marshal(v.d)
	if err != nil {
		return err
	}
	sealed, err := v.c.Seal(b)
	if err != nil {
		return err
	}
	tmp := v.path + ".tmp"
	if err := os.WriteFile(tmp, sealed, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, v.path)
}

// Find returns the logins for the site of a page address.
func (v *Vault) Find(page string) []Entry {
	o := Origin(page)
	if o == "" {
		return nil
	}
	v.mu.Lock()
	defer v.mu.Unlock()
	var out []Entry
	for _, e := range v.d.Entries {
		if e.Origin == o {
			out = append(out, e)
		}
	}
	return out
}

// Status says what a captured login means: "new", "update" (other password
// for a saved name), "same" (nothing to do) or "never"/"" (do not ask).
func (v *Vault) Status(page, user, pass string) string {
	o := Origin(page)
	if o == "" || pass == "" || len(pass) > 500 || len(user) > 300 {
		return ""
	}
	v.mu.Lock()
	defer v.mu.Unlock()
	for _, n := range v.d.Never {
		if n == o {
			return "never"
		}
	}
	for _, e := range v.d.Entries {
		if e.Origin == o && e.Username == user {
			if e.Password == pass {
				return "same"
			}
			return "update"
		}
	}
	return "new"
}

// Put saves or updates the login for the page's site.
func (v *Vault) Put(page, user, pass, note string) error {
	o := Origin(page)
	if o == "" || pass == "" {
		return errors.New("passwords are kept only for secure (https) sites")
	}
	v.mu.Lock()
	defer v.mu.Unlock()
	v.put(Entry{Origin: o, Username: user, Password: pass, Note: note, Changed: v.now().Unix()})
	return v.save()
}

func (v *Vault) put(e Entry) bool {
	for i, x := range v.d.Entries {
		if x.Origin == e.Origin && x.Username == e.Username {
			if x.Password == e.Password {
				return false
			}
			if e.Note == "" {
				e.Note = x.Note
			}
			v.d.Entries[i] = e
			return true
		}
	}
	if len(v.d.Entries) >= MaxEntries {
		return false
	}
	v.d.Entries = append(v.d.Entries, e)
	return true
}

// Remove deletes one login.
func (v *Vault) Remove(origin, user string) error {
	v.mu.Lock()
	defer v.mu.Unlock()
	keep := v.d.Entries[:0]
	for _, e := range v.d.Entries {
		if !(e.Origin == origin && e.Username == user) {
			keep = append(keep, e)
		}
	}
	v.d.Entries = keep
	return v.save()
}

// Never stops Ronin asking to save passwords for the page's site.
func (v *Vault) Never(page string) error {
	o := Origin(page)
	if o == "" {
		return nil
	}
	v.mu.Lock()
	defer v.mu.Unlock()
	for _, n := range v.d.Never {
		if n == o {
			return nil
		}
	}
	v.d.Never = append(v.d.Never, o)
	return v.save()
}

// List returns every login sorted by site, for the Passwords panel.
func (v *Vault) List() []Entry {
	v.mu.Lock()
	defer v.mu.Unlock()
	out := append([]Entry(nil), v.d.Entries...)
	sort.Slice(out, func(i, j int) bool {
		if out[i].Origin != out[j].Origin {
			return out[i].Origin < out[j].Origin
		}
		return out[i].Username < out[j].Username
	})
	return out
}

// Len is the number of saved logins.
func (v *Vault) Len() int {
	v.mu.Lock()
	defer v.mu.Unlock()
	return len(v.d.Entries)
}

// ---- CSV import / export ---------------------------------------------------------
//
// The CSV format is the common one other password managers read and write:
//   name,url,username,password,note
// Import also understands the Bitwarden and Firefox column names.

// ImportCSV adds the logins from a CSV file. It returns how many were added
// or changed and how many rows were skipped (no secure address / no password).
func (v *Vault) ImportCSV(r io.Reader) (added, skipped int, err error) {
	cr := csv.NewReader(r)
	cr.FieldsPerRecord = -1
	cr.LazyQuotes = true
	head, err := cr.Read()
	if err != nil {
		return 0, 0, errors.New("this is not a password CSV file")
	}
	col := map[string]int{}
	for i, h := range head {
		col[strings.ToLower(strings.TrimSpace(strings.TrimPrefix(h, "\ufeff")))] = i
	}
	pick := func(names ...string) int {
		for _, n := range names {
			if i, ok := col[n]; ok {
				return i
			}
		}
		return -1
	}
	cURL := pick("url", "login_uri", "website", "web site", "origin_url")
	cUser := pick("username", "login_username", "login", "user name", "email")
	cPass := pick("password", "login_password")
	cNote := pick("note", "notes", "extra")
	if cURL < 0 || cPass < 0 {
		return 0, 0, errors.New("the file has no 'url' and 'password' columns")
	}
	get := func(rec []string, i int) string {
		if i < 0 || i >= len(rec) {
			return ""
		}
		return strings.TrimSpace(rec[i])
	}
	v.mu.Lock()
	defer v.mu.Unlock()
	now := v.now().Unix()
	for n := 0; n < 100000; n++ {
		rec, err := cr.Read()
		if err == io.EOF {
			break
		}
		if err != nil {
			skipped++
			continue
		}
		raw := get(rec, cURL)
		if raw != "" && !strings.Contains(raw, "://") {
			raw = "https://" + raw
		}
		o := Origin(raw)
		pass := ""
		if cPass < len(rec) {
			pass = rec[cPass] // not trimmed: spaces can be part of a password
		}
		if o == "" || pass == "" || len(pass) > 500 {
			skipped++
			continue
		}
		if v.put(Entry{Origin: o, Username: get(rec, cUser), Password: pass, Note: get(rec, cNote), Changed: now}) {
			added++
		}
	}
	if added > 0 {
		err = v.save()
	}
	return added, skipped, err
}

// ExportCSV writes every login in the common format (plain text!).
func (v *Vault) ExportCSV(w io.Writer) error {
	cw := csv.NewWriter(w)
	_ = cw.Write([]string{"name", "url", "username", "password", "note"})
	for _, e := range v.List() {
		name := strings.TrimPrefix(strings.TrimPrefix(e.Origin, "https://"), "http://")
		if err := cw.Write([]string{name, e.Origin + "/", e.Username, e.Password, e.Note}); err != nil {
			return err
		}
	}
	cw.Flush()
	return cw.Error()
}
