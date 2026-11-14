package videodl

import (
	"strings"
	"testing"
)

func TestIsVideoPage(t *testing.T) {
	yes := []string{
		"https://www.youtube.com/watch?v=3_DQmfFjTIU", "https://m.youtube.com/watch?v=x",
		"https://www.youtube.com/shorts/abc", "https://youtu.be/abc",
		"https://www.instagram.com/reel/Cx1/", "https://x.com/u/status/123",
		"https://www.facebook.com/watch?v=1",
	}
	no := []string{
		"https://www.youtube.com/", "https://www.youtube.com/results?search_query=x",
		"https://www.instagram.com/someone/", "https://sbi.bank.in/", "https://youtu.be/",
		"javascript:alert(1)", "https://notyoutube.com/watch?v=1",
	}
	for _, u := range yes {
		if !IsVideoPage(u) {
			t.Errorf("%s should show Download", u)
		}
	}
	for _, u := range no {
		if IsVideoPage(u) {
			t.Errorf("%s should not show Download", u)
		}
	}
}

func TestArgs(t *testing.T) {
	a := Args("https://www.youtube.com/watch?v=abc&list=PL1&index=3&si=x", Q720, `C:\Out`, `C:\Tools`)
	s := strings.Join(a, " ")
	if a[len(a)-2] != "--" || a[len(a)-1] != "https://www.youtube.com/watch?v=abc" {
		t.Fatalf("url not cleaned or not last: %v", a[len(a)-2:])
	}
	for _, want := range []string{"--no-playlist", "height<=720", "--merge-output-format mp4", "--ffmpeg-location"} {
		if !strings.Contains(s, want) {
			t.Errorf("missing %q in %s", want, s)
		}
	}
	au := strings.Join(Args("https://youtu.be/abc", Audio, "o", "t"), " ")
	if !strings.Contains(au, "--audio-format mp3") || strings.Contains(au, "merge-output") {
		t.Errorf("audio args wrong: %s", au)
	}
	// a page address starting with "-" can never be read as an option
	b := Args("-exec=calc", Best, "o", "t")
	if b[len(b)-2] != "--" {
		t.Error("missing -- before url")
	}
}

func TestProgressAndFile(t *testing.T) {
	if p, ok := Progress("[download]  42.3% of ~ 10.00MiB at 1.2MiB/s ETA 00:05"); !ok || p != 42.3 {
		t.Errorf("progress = %v %v", p, ok)
	}
	if _, ok := Progress("[youtube] abc: Downloading webpage"); ok {
		t.Error("non-progress line parsed")
	}
	if f, ok := FinalFile(`RONIN_FILE:C:\Users\x\Downloads\Ronin Videos\Song [abc].mp4`); !ok || !strings.HasSuffix(f, ".mp4") {
		t.Error("final file not read")
	}
	if !Q1080.Valid() || Quality("4k").Valid() {
		t.Error("Valid wrong")
	}
	if FriendlyError("ERROR: [youtube] x: Private video") == "" {
		t.Error("no message")
	}
}

func TestMediaURLsAndDirect(t *testing.T) {
	got := MediaURLs(`["https://cdn.x.com/a.mp4?t=1","blob:https://x.com/1","javascript:alert(1)","https://cdn.x.com/a.mp4?t=1","http://y.com/b.m3u8"]`)
	if len(got) != 2 || got[0] != "https://cdn.x.com/a.mp4?t=1" || got[1] != "http://y.com/b.m3u8" {
		t.Fatalf("MediaURLs = %v", got)
	}
	if MediaURLs("null") != nil || MediaURLs("garbage") != nil {
		t.Fatal("bad input accepted")
	}
	a := DirectArgs("https://cdn.x.com/a.mp4", "https://site.com/v/1", `My: "Video" 100%`, Best, "out", "tools")
	n := len(a)
	if a[n-2] != "--" || a[n-1] != "https://cdn.x.com/a.mp4" {
		t.Fatalf("URL must stay last: %v", a)
	}
	j := strings.Join(a, " ")
	if !strings.Contains(j, "--referer https://site.com/v/1") || strings.Contains(j, "100%") || !strings.Contains(j, "My Video 100") {
		t.Fatalf("DirectArgs = %v", a)
	}
	raw := "https://cdn.x.com/v.mp4?z=2&si=abc&a=1"
	if b := DirectArgs(raw, "https://s.com/", "t", Best, "o", "t"); b[len(b)-1] != raw {
		t.Fatalf("signed address changed: %v", b[len(b)-1])
	}
	if SafeTitle("  ...  ") != "Video" {
		t.Fatal("empty title")
	}
}

const masterList = `#EXTM3U
#EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=541590,RESOLUTION=640x360,FRAME-RATE=25.000,CODECS="avc1.4d401f,mp4a.40.2"
https://cdn.example.com/m/808858_360p.mp4/index-v1-a1.m3u8?c=9&sign=a
#EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=773920,RESOLUTION=854x480,FRAME-RATE=25.000,CODECS="avc1.640029,mp4a.40.2"
https://cdn.example.com/m/808858.mp4/index-v1-a1.m3u8?c=9&sign=b
#EXT-X-STREAM-INF:PROGRAM-ID=1,AVERAGE-BANDWIDTH=9,BANDWIDTH=1442919,RESOLUTION=1280x720,FRAME-RATE=25.000
https://cdn.example.com/m/808858_720p.mp4/index-v1-a1.m3u8?c=9&sign=c
`

func TestPlaylist(t *testing.T) {
	if !IsPlaylist([]byte("\xef\xbb\xbf#EXTM3U\n")) || !IsPlaylist([]byte(" \n#EXTM3U")) || IsPlaylist([]byte("\x00\x00\x00 ftypmp42")) {
		t.Fatal("IsPlaylist wrong")
	}
	cases := map[Quality]string{Best: "sign=c", Q1080: "sign=c", Q720: "sign=c", Q480: "sign=b", Audio: "sign=c"}
	for q, want := range cases {
		if got := PickStream(masterList, "https://site.com/x.mp4", q); !strings.Contains(got, want) {
			t.Fatalf("%s: got %s", q, got)
		}
	}
	rel := "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1,RESOLUTION=1920x1080\nhi/index.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=1,RESOLUTION=640x360\nlo/index.m3u8\n"
	if got := PickStream(rel, "https://cdn.x.com/v/master.m3u8?t=1", Q480); got != "https://cdn.x.com/v/lo/index.m3u8" {
		t.Fatalf("relative: %s", got)
	}
	if got := PickStream(rel, "https://cdn.x.com/v/master.m3u8", Best); got != "https://cdn.x.com/v/hi/index.m3u8" {
		t.Fatalf("best relative: %s", got)
	}
	if PickStream("#EXTM3U\n#EXTINF:4,\nseg1.ts\n", "https://a.com/l.m3u8", Best) != "" {
		t.Fatal("single stream list must give empty")
	}
	if PickStream("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\njavascript:alert(1)\n", "", Best) != "" {
		t.Fatal("bad address accepted")
	}
	if u, ok := SourceURL("RONIN_SRC:https://a.com/v.mp4"); !ok || u != "https://a.com/v.mp4" {
		t.Fatal("SourceURL")
	}
	if _, ok := SourceURL("RONIN_SRC:NA"); ok {
		t.Fatal("SourceURL NA")
	}
}

func TestWithProxy(t *testing.T) {
	a := WithProxy(Args("https://a.com/v", Best, "o", "t"), "socks5://127.0.0.1:40877")
	n := len(a)
	if a[n-2] != "--" || a[n-1] != "https://a.com/v" || a[n-4] != "--proxy" || a[n-3] != "socks5://127.0.0.1:40877" {
		t.Fatalf("WithProxy = %v", a)
	}
	if b := WithProxy([]string{"x"}, "p"); len(b) != 1 {
		t.Fatal("changed bad args")
	}
}

func TestPageID(t *testing.T) {
	if got := PageID("https://www.pornhat.com/video/katrina-moreno-milks/"); got != "katrina-moreno-milks" {
		t.Fatal(got)
	}
	if got := PageID("https://site.com/"); got != "site.com" {
		t.Fatal(got)
	}
	a := DirectArgs("https://cdn.x/index-v1-a1.m3u8?c=9&exp=1", "https://s.com/v/my-clip", "Title", Best, "out", "t")
	if j := strings.Join(a, " "); strings.Contains(j, "%(id)s") || !strings.Contains(j, "Title [my-clip].%(ext)s") {
		t.Fatal(j)
	}
}
