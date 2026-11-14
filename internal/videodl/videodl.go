// Package videodl builds yt-dlp commands for Ronin's video downloader and
// reads its progress output.
package videodl

import (
	"bufio"
	"bytes"
	"encoding/json"
	"net/url"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
)

// Quality is one of the choices in the Download panel.
type Quality string

const (
	Best  Quality = "best"
	Q1080 Quality = "1080"
	Q720  Quality = "720"
	Q480  Quality = "480"
	Audio Quality = "mp3"
)

// Valid reports whether q is a known choice.
func (q Quality) Valid() bool {
	switch q {
	case Best, Q1080, Q720, Q480, Audio:
		return true
	}
	return false
}

// videoSites are sites where Ronin shows the Download button.
var videoSites = []string{
	"youtube.com", "youtu.be", "instagram.com", "facebook.com", "fb.watch", "x.com",
	"twitter.com", "dailymotion.com", "vimeo.com", "sharechat.com", "mxplayer.in",
}

// IsVideoPage reports whether the Download button should be shown for a page.
func IsVideoPage(raw string) bool {
	u, err := url.Parse(raw)
	if err != nil || (u.Scheme != "https" && u.Scheme != "http") {
		return false
	}
	h := strings.TrimPrefix(strings.ToLower(u.Hostname()), "www.")
	h = strings.TrimPrefix(h, "m.")
	for _, s := range videoSites {
		if h == s || strings.HasSuffix(h, "."+s) {
			switch s {
			case "youtube.com":
				return strings.HasPrefix(u.Path, "/watch") || strings.HasPrefix(u.Path, "/shorts/") || strings.HasPrefix(u.Path, "/live/")
			case "instagram.com":
				return strings.HasPrefix(u.Path, "/reel") || strings.HasPrefix(u.Path, "/p/") || strings.HasPrefix(u.Path, "/tv/")
			case "facebook.com":
				return strings.Contains(u.Path, "/videos/") || strings.HasPrefix(u.Path, "/reel") || strings.HasPrefix(u.Path, "/watch")
			case "x.com", "twitter.com":
				return strings.Contains(u.Path, "/status/")
			}
			return u.Path != "" && u.Path != "/"
		}
	}
	return false
}

// CleanURL removes playlist and tracking parts so only the one video is fetched.
func CleanURL(raw string) string {
	u, err := url.Parse(raw)
	if err != nil {
		return raw
	}
	q := u.Query()
	for _, k := range []string{"list", "index", "start_radio", "pp", "si", "feature", "igsh", "utm_source", "utm_medium", "utm_campaign"} {
		q.Del(k)
	}
	u.RawQuery = q.Encode()
	u.Fragment = ""
	return u.String()
}

// Args returns the yt-dlp arguments for one download.
// The final file path is printed on its own line after the download.
func Args(pageURL string, q Quality, outDir, toolsDir string) []string {
	out := filepath.Join(outDir, "%(title).120B [%(id)s].%(ext)s")
	args := []string{
		"--newline", "--progress", "--no-playlist", "--windows-filenames", "--no-mtime",
		"--no-colors", "--ffmpeg-location", toolsDir,
		"--print", "after_move:RONIN_FILE:%(filepath)s",
		"--print", "after_move:RONIN_SRC:%(url)s",
		"-o", out,
	}
	switch q {
	case Audio:
		args = append(args, "-f", "ba/b", "-x", "--audio-format", "mp3", "--audio-quality", "0")
	case Q1080, Q720, Q480:
		h := string(q)
		args = append(args, "-f", "bv*[height<="+h+"][ext=mp4]+ba[ext=m4a]/bv*[height<="+h+"]+ba/b[height<="+h+"]/b",
			"--merge-output-format", "mp4")
	default:
		args = append(args, "-f", "bv*[ext=mp4]+ba[ext=m4a]/bv*+ba/b", "--merge-output-format", "mp4")
	}
	return append(args, "--", CleanURL(pageURL))
}

var progressRe = regexp.MustCompile(`^\[download\]\s+([0-9]+(?:\.[0-9]+)?)%`)

// Progress reads a percentage from one yt-dlp output line.
func Progress(line string) (float64, bool) {
	m := progressRe.FindStringSubmatch(strings.TrimSpace(line))
	if m == nil {
		return 0, false
	}
	p, err := strconv.ParseFloat(m[1], 64)
	if err != nil || p < 0 || p > 100 {
		return 0, false
	}
	return p, true
}

// FinalFile reads the saved file path printed by --print after_move.
func FinalFile(line string) (string, bool) {
	line = strings.TrimSpace(line)
	if p, ok := strings.CutPrefix(line, "RONIN_FILE:"); ok && p != "" {
		return p, true
	}
	return "", false
}

// FriendlyError turns a yt-dlp error line into a short English message.
func FriendlyError(line string) string {
	l := strings.ToLower(line)
	switch {
	case strings.Contains(l, "sign in to confirm") || strings.Contains(l, "age"):
		return "This video needs a signed-in YouTube account (age-restricted or private)."
	case strings.Contains(l, "private video") || strings.Contains(l, "video unavailable"):
		return "This video is private or no longer available."
	case strings.Contains(l, "unable to download") || strings.Contains(l, "http error"):
		return "The site refused the download. Ronin will update its downloader; try again later."
	case strings.Contains(l, "ffmpeg"):
		return "The video tools are not ready yet. Please try again in a minute."
	}
	return "The download did not work. Please try again."
}

// MediaJS lists the addresses of the videos playing on a page (as a JSON array).
const MediaJS = `(function(){var o=[];function add(u){if(u&&/^https?:/i.test(u)&&o.indexOf(u)<0)o.push(u)}` +
	`document.querySelectorAll("video").forEach(function(v){add(v.currentSrc);add(v.src);v.querySelectorAll("source").forEach(function(s){add(s.src)})});` +
	`try{performance.getEntriesByType("resource").forEach(function(e){if(/\.(m3u8|mp4|webm)(\?|$)/i.test(e.name))add(e.name)})}catch(e){}return o.slice(0,6)})()`

// MediaURLs reads MediaJS's answer and keeps only real web addresses
// (at most 4), so they can be tried when the normal way does not work.
func MediaURLs(js string) []string {
	var list []string
	if err := json.Unmarshal([]byte(js), &list); err != nil {
		return nil
	}
	var out []string
	seen := map[string]bool{}
	for _, u := range list {
		p, err := url.Parse(u)
		if err != nil || (p.Scheme != "https" && p.Scheme != "http") || p.Host == "" || seen[u] || len(u) > 4000 {
			continue
		}
		seen[u] = true
		out = append(out, u)
		if len(out) == 4 {
			break
		}
	}
	return out
}

// DirectArgs downloads a video file address found on a page. The page is sent
// as the referer because many sites refuse the file without it. The file is
// named after the page title.
func DirectArgs(mediaURL, pageURL, title string, q Quality, outDir, toolsDir string) []string {
	a := Args(mediaURL, q, outDir, toolsDir)
	name := SafeTitle(title)
	for i := range a {
		if a[i] == "-o" && i+1 < len(a) {
			a[i+1] = filepath.Join(outDir, name+" ["+PageID(pageURL)+"].%(ext)s")
		}
	}
	// keep the URL itself last, after "--", and put the extra options before it
	n := len(a)
	extra := []string{"--referer", pageURL}
	out := append([]string{}, a[:n-2]...)
	out = append(out, extra...)
	// the file address is used exactly as found: signed addresses break if
	// their parameters are changed or re-ordered
	return append(out, "--", mediaURL)
}

// SafeTitle makes a page title usable as a file name (and as yt-dlp output
// template text, where "%" has a meaning).
func SafeTitle(t string) string {
	var b strings.Builder
	for _, r := range t {
		switch {
		case r < 32 || strings.ContainsRune(`<>:"/\|?*%`, r):
			b.WriteRune(' ')
		default:
			b.WriteRune(r)
		}
	}
	s := strings.Join(strings.Fields(b.String()), " ")
	if len([]rune(s)) > 80 {
		s = string([]rune(s)[:80])
	}
	s = strings.Trim(s, ". ")
	if s == "" {
		s = "Video"
	}
	return s
}

// SourceURL reads the address the file was taken from (printed after the download).
func SourceURL(line string) (string, bool) {
	line = strings.TrimSpace(line)
	if p, ok := strings.CutPrefix(line, "RONIN_SRC:"); ok && strings.HasPrefix(p, "http") {
		return p, true
	}
	return "", false
}

// IsPlaylist reports whether a saved "video" is really a stream list
// (.m3u8 text). Some sites name their stream list ".mp4", so the downloader
// saves the 1 KB list instead of the video.
func IsPlaylist(head []byte) bool {
	head = bytes.TrimPrefix(head, []byte("\xef\xbb\xbf"))
	return bytes.HasPrefix(bytes.TrimSpace(head), []byte("#EXTM3U"))
}

// PickStream chooses the stream to download from a stream list: the best one
// that is not bigger than the chosen quality. base is the list's own address
// (for streams given as relative addresses). It returns "" when the list is
// already a single stream (then the list address itself is used).
func PickStream(list, base string, q Quality) string {
	maxH := 1 << 30
	switch q {
	case Q1080:
		maxH = 1080
	case Q720:
		maxH = 720
	case Q480:
		maxH = 480
	}
	type stream struct {
		url   string
		h, bw int
	}
	var all []stream
	sc := bufio.NewScanner(strings.NewReader(list))
	pending := (*stream)(nil)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if strings.HasPrefix(line, "#EXT-X-STREAM-INF:") {
			st := stream{}
			attrs := line[len("#EXT-X-STREAM-INF:"):]
			if m := resRe.FindStringSubmatch(attrs); m != nil {
				st.h, _ = strconv.Atoi(m[1])
			}
			if m := bwRe.FindStringSubmatch(attrs); m != nil {
				st.bw, _ = strconv.Atoi(m[1])
			}
			pending = &st
			continue
		}
		if pending != nil && line != "" && !strings.HasPrefix(line, "#") {
			u := line
			if b, err := url.Parse(base); err == nil && base != "" {
				if r, err := b.Parse(line); err == nil {
					u = r.String()
				}
			}
			if p, err := url.Parse(u); err == nil && (p.Scheme == "https" || p.Scheme == "http") {
				pending.url = u
				all = append(all, *pending)
			}
			pending = nil
		}
	}
	best, bestScore := "", -1
	smallest, smallScore := "", 1<<62
	for _, s := range all {
		score := s.h*100000000 + s.bw
		if score < smallScore {
			smallest, smallScore = s.url, score
		}
		if s.h <= maxH && score > bestScore {
			best, bestScore = s.url, score
		}
	}
	if best == "" {
		return smallest // every stream is bigger than asked: take the smallest
	}
	return best
}

var (
	resRe = regexp.MustCompile(`RESOLUTION=\d+x(\d+)`)
	bwRe  = regexp.MustCompile(`(?:^|,)BANDWIDTH=(\d+)`)
)

// WithProxy makes one download go through a proxy (the VPN), keeping the
// address last.
func WithProxy(args []string, proxy string) []string {
	if proxy == "" || len(args) < 2 || args[len(args)-2] != "--" {
		return args
	}
	out := append([]string{}, args[:len(args)-2]...)
	out = append(out, "--proxy", proxy)
	return append(out, args[len(args)-2:]...)
}

// PageID is a short name for a video page (the last part of its address),
// used in the file name instead of the long stream address.
func PageID(pageURL string) string {
	u, err := url.Parse(pageURL)
	if err != nil {
		return "video"
	}
	parts := strings.Split(strings.Trim(u.Path, "/"), "/")
	id := SafeTitle(parts[len(parts)-1])
	if id == "Video" || id == "" {
		id = SafeTitle(u.Hostname())
	}
	if r := []rune(id); len(r) > 40 {
		id = string(r[:40])
	}
	return strings.ReplaceAll(id, " ", "-")
}
