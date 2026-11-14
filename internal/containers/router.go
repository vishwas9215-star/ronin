// Package containers decides, for every top-level page, which Ronin window
// (container) it belongs to: PERSONAL, BANKING, TRADING, KIDS or PRIVATE.
// Each container has its own separate browser data on disk.
package containers

import "roninbrowser/internal/safemoney"

// Container is one isolated browsing area.
type Container string

const (
	Personal Container = "PERSONAL"
	Banking  Container = "BANKING"
	Trading  Container = "TRADING"
	Kids     Container = "KIDS"
	Private  Container = "PRIVATE"
	Calls    Container = "CALLS" // video calls: no VPN, full call quality
)

// All lists the containers in display order.
var All = []Container{Personal, Banking, Trading, Kids, Private, Calls}

// Valid reports whether c is a known container.
func (c Container) Valid() bool {
	for _, x := range All {
		if x == c {
			return true
		}
	}
	return false
}

// Action is what the browser should do with a navigation.
type Action int

const (
	Stay  Action = iota // load it here
	Move                // cancel here, open it in Decision.Target
	Warn                // cancel here, show the red "fake bank site" page
	Block               // cancel here, show "not allowed in this mode"
)

func (a Action) String() string {
	return [...]string{"stay", "move", "warn", "block"}[a]
}

// Nav describes one top-level navigation.
type Nav struct {
	URL           string
	UserInitiated bool // the user clicked or typed (not a script or redirect)
	Redirected    bool // the server redirected here
	// ViaRedirector is true when this redirect chain started at a link
	// redirector (google.com/url, l.instagram.com ...) after a user click,
	// so it still counts as the user opening the site himself.
	ViaRedirector bool
}

// Decision is the router's answer.
type Decision struct {
	Action         Action
	Target         Container // for Move
	PaymentProtect bool      // Stay, but with AI/downloader off (payment/OTP page)
	Verdict        safemoney.Verdict
}

// Route decides what to do with a navigation in the current container.
//
// Rules:
//   - A fake (lookalike) bank address is always stopped with the red warning.
//   - A real bank that the user opens himself moves to BANKING (Ronin Safe Money).
//   - A bank page reached by a redirect (shopping payment -> bank OTP page)
//     stays in its tab with Payment Protect, otherwise the payment breaks.
//   - Payment gateways always stay with Payment Protect.
//   - BANKING only keeps bank and payment pages; anything else the user opens
//     goes back to PERSONAL.
//   - Dhan, TradingView and Ronin Terminal open in TRADING.
//   - KIDS never leaves KIDS and cannot open banking or trading sites.
func Route(cur Container, n Nav, l safemoney.Lists) Decision {
	v := safemoney.Classify(n.URL, l)
	d := Decision{Action: Stay, Target: cur, Verdict: v}
	byUser := n.UserInitiated && (!n.Redirected || n.ViaRedirector)

	if v.Lookalike {
		d.Action = Warn
		return d
	}
	if v.Kind == safemoney.Local {
		return d
	}

	switch cur {
	case Kids:
		if v.Kind == safemoney.Bank || v.Kind == safemoney.Trading || v.Kind == safemoney.Payment {
			d.Action = Block
		}
		return d

	case Banking:
		switch v.Kind {
		case safemoney.Bank:
			return d
		case safemoney.Payment:
			d.PaymentProtect = true
			return d
		}
		if byUser {
			return move(d, targetFor(v.Kind))
		}
		// a bank sending the user to a merchant after payment: keep the flow,
		// but treat the page as a payment page.
		d.PaymentProtect = true
		return d

	case Calls:
		switch v.Kind {
		case safemoney.Call, safemoney.Auth:
			return d
		case safemoney.Payment:
			d.PaymentProtect = true
			return d
		case safemoney.Bank:
			if byUser {
				return move(d, Banking)
			}
			d.PaymentProtect = true
			return d
		}
		if byUser {
			return move(d, targetFor(v.Kind))
		}
		return d

	case Trading:
		switch v.Kind {
		case safemoney.Trading, safemoney.Auth:
			return d
		case safemoney.Call:
			if byUser {
				return move(d, Calls)
			}
			return d
		case safemoney.Bank:
			if byUser {
				return move(d, Banking)
			}
			d.PaymentProtect = true
			return d
		case safemoney.Payment:
			d.PaymentProtect = true
			return d
		}
		if byUser {
			return move(d, Personal)
		}
		return d

	default: // Personal, Private
		switch v.Kind {
		case safemoney.Bank:
			if byUser {
				return move(d, Banking)
			}
			d.PaymentProtect = true
			return d
		case safemoney.Payment:
			d.PaymentProtect = true
			return d
		case safemoney.Trading:
			if byUser {
				return move(d, Trading)
			}
			return d
		case safemoney.Call:
			// Private windows stay private (always VPN); normal windows move
			// the call to the Video call window for full quality.
			if byUser && cur == Personal {
				return move(d, Calls)
			}
			return d
		}
		return d
	}
}

func targetFor(k safemoney.Kind) Container {
	if k == safemoney.Trading {
		return Trading
	}
	if k == safemoney.Call {
		return Calls
	}
	return Personal
}

func move(d Decision, to Container) Decision {
	if to == d.Target {
		d.Action = Stay
		return d
	}
	d.Action = Move
	d.Target = to
	return d
}
