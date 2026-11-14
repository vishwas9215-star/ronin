package vpn

import (
	"bufio"
	"io"
	"sort"
	"strings"
)

// Smart VPN: well-known, trusted sites go directly (fast, no VPN); unknown
// sites and sensitive sites (adult, betting, piracy ...) go through the VPN.
//
// The engine decides with a proxy auto-config (PAC) script, so the choice is
// made for every connection without restarting anything.

// builtinTrusted are sites that never need the VPN. Their sub-domains are
// included (google.com covers mail.google.com).
var builtinTrusted = []string{
	// Google, Microsoft, Apple and their content servers
	"google.com", "google.co.in", "gstatic.com", "googleapis.com", "googleusercontent.com",
	"googlevideo.com", "ytimg.com", "ggpht.com", "youtube.com", "youtu.be", "youtube-nocookie.com",
	"gmail.com", "googlesyndication.com", "withgoogle.com", "android.com", "gvt1.com", "gvt2.com",
	"microsoft.com", "microsoftonline.com", "live.com", "office.com", "office.net", "outlook.com",
	"bing.com", "msn.com", "windows.com", "windowsupdate.com", "msftconnecttest.com", "skype.com",
	"azureedge.net", "azure.com", "sharepoint.com", "onedrive.com", "msedge.net", "linkedin.com",
	"apple.com", "icloud.com", "mzstatic.com",
	// big platforms
	"facebook.com", "fbcdn.net", "instagram.com", "cdninstagram.com", "whatsapp.com", "whatsapp.net",
	"x.com", "twitter.com", "twimg.com", "wikipedia.org", "wikimedia.org", "amazon.in", "amazon.com",
	"media-amazon.com", "ssl-images-amazon.com", "flipkart.com", "flixcart.com", "meesho.com",
	"myntra.com", "netflix.com", "nflxvideo.net", "hotstar.com", "jiocinema.com", "jio.com",
	"airtel.in", "spotify.com", "zoom.us", "telegram.org", "github.com", "githubusercontent.com",
	"stackoverflow.com", "claude.ai", "anthropic.com", "openai.com", "chatgpt.com",
	"cloudflare.com", "akamaized.net", "akamaihd.net", "jsdelivr.net", "cdnjs.cloudflare.com",
	"proton.me", "protonvpn.com",
	// India: government, education, news, money
	"gov.in", "nic.in", "india.gov.in", "uidai.gov.in", "incometax.gov.in", "irctc.co.in",
	"digilocker.gov.in", "upsc.gov.in", "ac.in", "edu.in", "ncert.nic.in", "diksha.gov.in",
	"timesofindia.com", "indiatimes.com", "hindustantimes.com", "ndtv.com", "thehindu.com",
	"indianexpress.com", "aajtak.in", "bhaskar.com", "jagran.com", "amarujala.com", "livehindustan.com",
	"abplive.com", "zeenews.india.com", "news18.com", "livemint.com", "moneycontrol.com",
	"economictimes.com", "business-standard.com", "bbc.com", "bbc.co.uk", "reuters.com",
	"sbi", "bank.in", "npci.org.in", "rbi.org.in", "sebi.gov.in", "nseindia.com", "bseindia.com",
	"paytm.com", "phonepe.com", "razorpay.com", "billdesk.com", "payu.in", "cashfree.com",
	"zerodha.com", "kite.trade", "upstox.com", "groww.in", "angelone.in", "dhan.co",
	"tradingview.com", "tradingview-widget.com", "fyers.in", "icicidirect.com",
}

// sensitiveWords mark sites that always use the VPN even if they are popular.
var sensitiveWords = []string{
	"porn", "xxx", "sex", "xvideo", "xhamster", "hentai", "nude", "nsfw", "escort", "adult",
	"cam4", "chaturbate", "stripchat", "onlyfans", "torrent", "pirate", "1337x", "yts", "rarbg",
	"betting", "casino", "bet365", "1xbet", "stake", "parimatch", "dafabet", "satta", "rummy",
	"proxy", "unblock", "mirror",
}

// Smart decides, for one host, whether it goes through the VPN.
type Smart struct {
	trusted   map[string]struct{}
	sensitive func(host string) bool
}

// NewSmart builds the decision table. extra are more trusted sites (the most
// visited sites in the world, downloaded weekly); a site that is sensitive
// (by its name or by the sensitive check, e.g. the adult-site list) is never
// trusted, so it goes through the VPN. sensitive may be nil.
func NewSmart(extra []string, sensitive func(host string) bool) *Smart {
	s := &Smart{trusted: map[string]struct{}{}, sensitive: sensitive}
	add := func(d string) {
		d = strings.ToLower(strings.TrimSpace(d))
		if d == "" || strings.ContainsAny(d, `"'\ `) || s.isSensitive(d) {
			return
		}
		s.trusted[d] = struct{}{}
	}
	for _, d := range builtinTrusted {
		add(d)
	}
	for _, d := range extra {
		add(d)
	}
	return s
}

func (s *Smart) isSensitive(host string) bool {
	h := strings.ToLower(host)
	for _, w := range sensitiveWords {
		if strings.Contains(h, w) {
			return true
		}
	}
	return s.sensitive != nil && s.sensitive(h)
}

// UsesVPN reports whether a connection to host goes through the VPN.
func (s *Smart) UsesVPN(host string) bool {
	h := strings.ToLower(strings.TrimSuffix(strings.Trim(host, "[]"), "."))
	if h == "" || h == "localhost" || strings.HasPrefix(h, "127.") || h == "::1" {
		return false
	}
	for _, w := range sensitiveWords { // the same rules as the PAC script
		if strings.Contains(h, w) {
			return true
		}
	}
	for {
		if _, ok := s.trusted[h]; ok {
			return false
		}
		i := strings.IndexByte(h, '.')
		if i < 0 {
			return true
		}
		h = h[i+1:]
	}
}

// Size is the number of trusted sites (for the health page).
func (s *Smart) Size() int { return len(s.trusted) }

// PAC is the proxy auto-config script for the engine. It follows exactly the
// same rules as UsesVPN.
func (s *Smart) PAC() string {
	keys := make([]string, 0, len(s.trusted))
	for k := range s.trusted {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	var b strings.Builder
	b.WriteString("var T={")
	for i, k := range keys {
		if i > 0 {
			b.WriteByte(',')
		}
		b.WriteString(`"` + k + `":1`)
	}
	b.WriteString("};\nvar W=[")
	for i, w := range sensitiveWords {
		if i > 0 {
			b.WriteByte(',')
		}
		b.WriteString(`"` + w + `"`)
	}
	b.WriteString(`];
var V="SOCKS5 127.0.0.1:` + itoa(Port) + `";
function FindProxyForURL(url, host) {
  var h = host.toLowerCase();
  if (h.charAt(0) == "[") h = h.substring(1, h.length - 1);
  if (h == "localhost" || h == "::1" || h.substring(0, 4) == "127.") return "DIRECT";
  for (var i = 0; i < W.length; i++) if (h.indexOf(W[i]) >= 0) return V;
  var x = h;
  while (true) {
    if (T.hasOwnProperty(x)) return "DIRECT";
    var k = x.indexOf(".");
    if (k < 0) return V;
    x = x.substring(k + 1);
  }
}
`)
	return b.String()
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	var d []byte
	for n > 0 {
		d = append([]byte{byte('0' + n%10)}, d...)
		n /= 10
	}
	return string(d)
}

// ParseTopList reads the "rank,domain" list of the most visited sites and
// returns at most max domains.
func ParseTopList(r io.Reader, max int) []string {
	var out []string
	sc := bufio.NewScanner(r)
	for sc.Scan() && len(out) < max {
		line := strings.TrimSpace(sc.Text())
		_, d, ok := strings.Cut(line, ",")
		if !ok {
			continue
		}
		d = strings.ToLower(strings.TrimSpace(d))
		if d == "" || !strings.Contains(d, ".") || strings.ContainsAny(d, `"'\ /:`) {
			continue
		}
		out = append(out, d)
	}
	return out
}
