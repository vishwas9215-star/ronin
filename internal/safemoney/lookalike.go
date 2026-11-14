package safemoney

import (
	"strings"
	"unicode/utf8"
)

// lookalikeBrand reports whether host imitates a bank brand, for example
// "onlinesbi-kyc.xyz", "hdfcbamk.com" (one wrong letter), "1cici-login.in"
// (digit for letter) or "xn--sb-...". The caller has already ruled out real
// bank and family domains.
func lookalikeBrand(host string) (string, bool) {
	labels := strings.Split(host, ".")
	if len(labels) < 2 {
		return "", false
	}
	// Drop the ending (".com", ".co.in") so "in" or "co" never count as words.
	labels = labels[:len(labels)-1]
	if n := len(labels); n >= 2 && isSecondLevel(labels[n-1]) {
		labels = labels[:n-1]
	}
	var tokens []string
	for _, lab := range labels {
		if strings.HasPrefix(lab, "xn--") {
			if dec, ok := punyDecode(lab[4:]); ok {
				lab = dec
			}
		}
		lab = skeleton(lab)
		for _, t := range strings.FieldsFunc(lab, func(r rune) bool { return r == '-' || r == '_' }) {
			if t != "" {
				tokens = append(tokens, t)
			}
		}
	}
	context := false
	for _, t := range tokens {
		if isBankingWord(t) {
			context = true
		}
	}
	for _, t := range tokens {
		n := fold(t)
		for _, b := range brands {
			fb := fold(b)
			if shortBrands[b] {
				if n == fb && context {
					return b, true
				}
				if rest, ok := strings.CutPrefix(n, fb); ok && rest != "" && isBankingWord(rest) {
					return b, true
				}
				if rest, ok := strings.CutSuffix(n, fb); ok && rest != "" && isBankingWord(rest) {
					return b, true
				}
				continue
			}
			if strings.Contains(n, fb) {
				return b, true
			}
			if len(fb) >= 5 && oneEditAway(n, fb) {
				return b, true
			}
		}
	}
	return "", false
}

func isSecondLevel(s string) bool {
	switch s {
	case "co", "net", "org", "gov", "ac", "edu", "gen", "firm", "ind", "res", "com":
		return true
	}
	return false
}

func isBankingWord(t string) bool {
	t = fold(t)
	for _, w := range bankingWords {
		if t == fold(w) {
			return true
		}
	}
	// two banking words glued together: "netbankinglogin", "kycupdate"
	for _, w := range bankingWords {
		if rest, ok := strings.CutPrefix(t, fold(w)); ok && rest != "" {
			for _, w2 := range bankingWords {
				if rest == fold(w2) {
					return true
				}
			}
		}
	}
	return false
}

// fold maps characters scammers swap for each other onto one form,
// so "1cici", "lcici" and "icici" compare equal.
var folder = strings.NewReplacer("0", "o", "1", "i", "l", "i", "3", "e", "5", "s", "4", "a", "@", "a", "$", "s", "rn", "m", "vv", "w")

func fold(s string) string { return folder.Replace(strings.ToLower(s)) }

// confusables maps common non-Latin letters that look Latin to the Latin letter.
var confusables = map[rune]rune{
	'а': 'a', 'е': 'e', 'о': 'o', 'р': 'p', 'с': 'c', 'у': 'y', 'х': 'x', 'і': 'i', 'ј': 'j',
	'ѕ': 's', 'һ': 'h', 'ԁ': 'd', 'ӏ': 'l', 'к': 'k', 'м': 'm', 'н': 'h', 'т': 't', 'в': 'b',
	'α': 'a', 'ο': 'o', 'ρ': 'p', 'ν': 'v', 'κ': 'k', 'ι': 'i', 'τ': 't', 'υ': 'u', 'β': 'b',
	'ı': 'i', 'ɡ': 'g', 'ɑ': 'a', 'ⅰ': 'i', 'ｓ': 's', 'ｂ': 'b', 'ｉ': 'i',
	'á': 'a', 'à': 'a', 'â': 'a', 'ä': 'a', 'ã': 'a', 'å': 'a', 'é': 'e', 'è': 'e', 'ê': 'e',
	'ë': 'e', 'í': 'i', 'ì': 'i', 'î': 'i', 'ï': 'i', 'ó': 'o', 'ò': 'o', 'ô': 'o', 'ö': 'o',
	'õ': 'o', 'ú': 'u', 'ù': 'u', 'û': 'u', 'ü': 'u', 'ç': 'c', 'ñ': 'n', 'ý': 'y',
}

func skeleton(s string) string {
	if isASCII(s) {
		return s
	}
	var b strings.Builder
	for _, r := range s {
		if m, ok := confusables[r]; ok {
			b.WriteRune(m)
		} else {
			b.WriteRune(r)
		}
	}
	return b.String()
}

func isASCII(s string) bool {
	for i := 0; i < len(s); i++ {
		if s[i] >= utf8.RuneSelf {
			return false
		}
	}
	return true
}

// oneEditAway is true when a and b differ by exactly one letter
// (changed, added or removed).
func oneEditAway(a, b string) bool {
	if a == b {
		return false
	}
	ra, rb := []rune(a), []rune(b)
	la, lb := len(ra), len(rb)
	if la-lb > 1 || lb-la > 1 {
		return false
	}
	i, j, edits := 0, 0, 0
	for i < la && j < lb {
		if ra[i] == rb[j] {
			i++
			j++
			continue
		}
		edits++
		if edits > 1 {
			return false
		}
		switch {
		case la > lb:
			i++
		case lb > la:
			j++
		default:
			i++
			j++
		}
	}
	edits += (la - i) + (lb - j)
	return edits == 1
}

// punyDecode decodes one Punycode label body (RFC 3492, without "xn--").
func punyDecode(in string) (string, bool) {
	const (
		base        = 36
		tmin        = 1
		tmax        = 26
		skew        = 38
		damp        = 700
		initialBias = 72
		initialN    = 128
	)
	var out []rune
	pos := strings.LastIndexByte(in, '-')
	if pos > 0 {
		for _, r := range in[:pos] {
			if r >= 0x80 {
				return "", false
			}
			out = append(out, r)
		}
		in = in[pos+1:]
	} else if pos == 0 {
		in = in[1:]
	}
	n, bias, i := initialN, initialBias, 0
	adapt := func(delta, numPoints int, first bool) int {
		if first {
			delta /= damp
		} else {
			delta /= 2
		}
		delta += delta / numPoints
		k := 0
		for delta > ((base-tmin)*tmax)/2 {
			delta /= base - tmin
			k += base
		}
		return k + (base-tmin+1)*delta/(delta+skew)
	}
	for p := 0; p < len(in); {
		oldi, w := i, 1
		for k := base; ; k += base {
			if p >= len(in) {
				return "", false
			}
			c := in[p]
			p++
			var digit int
			switch {
			case c >= 'a' && c <= 'z':
				digit = int(c - 'a')
			case c >= 'A' && c <= 'Z':
				digit = int(c - 'A')
			case c >= '0' && c <= '9':
				digit = int(c-'0') + 26
			default:
				return "", false
			}
			if digit > (1<<31-1-i)/w {
				return "", false
			}
			i += digit * w
			t := k - bias
			if t < tmin {
				t = tmin
			} else if t > tmax {
				t = tmax
			}
			if digit < t {
				break
			}
			if w > (1<<31-1)/(base-t) {
				return "", false
			}
			w *= base - t
		}
		bias = adapt(i-oldi, len(out)+1, oldi == 0)
		n += i / (len(out) + 1)
		if n > utf8.MaxRune {
			return "", false
		}
		i %= len(out) + 1
		out = append(out, 0)
		copy(out[i+1:], out[i:])
		out[i] = rune(n)
		i++
	}
	return string(out), true
}
