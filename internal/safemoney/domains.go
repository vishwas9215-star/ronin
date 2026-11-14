// Package safemoney decides which web addresses belong to banks, trading
// sites and payment pages, and spots fake (lookalike) bank addresses.
// It is pure Go with no Windows calls, so every rule is unit tested.
package safemoney

// bankDomains are well-known Indian bank domains (old style). Any host that
// equals one of these, or is a sub-domain of one, is a bank. All *.bank.in
// hosts are banks too (RBI-controlled domain), so they are not listed here.
var bankDomains = []string{
	// State Bank of India
	"onlinesbi.sbi", "sbi.co.in", "onlinesbi.com", "yonobusiness.sbi", "yonosbi.sbi", "sbi",
	// Large private banks
	"hdfcbank.com", "icicibank.com", "axisbank.com", "kotak.com", "kotak811.com",
	"idfcfirstbank.com", "indusind.com", "yesbank.in", "rblbank.com", "bandhanbank.com",
	"federalbank.co.in", "southindianbank.com", "karurvysyabank.co.in", "aubank.in",
	"idbibank.in", "dcbbank.com", "csb.co.in", "kvb.co.in", "equitasbank.com",
	"ujjivansfb.in", "jkbank.com", "karnatakabank.com", "tmb.in", "cityunionbank.com",
	// Public sector banks
	"pnbindia.in", "netpnb.com", "bankofbaroda.in", "bankofbaroda.com", "bobibanking.com",
	"unionbankofindia.co.in", "unionbankonline.co.in", "canarabank.com", "canarabank.in",
	"bankofindia.co.in", "bankofindia.com", "starconnectcbs.com", "centralbankofindia.co.in",
	"indianbank.in", "indianbank.net.in", "iob.in", "iobnet.co.in", "ucobank.com",
	"bankofmaharashtra.in", "mahaconnect.in", "psbindia.com",
	// Foreign banks in India, payments bank
	"hsbc.co.in", "sc.com", "dbs.com", "ippbonline.com", "dhanbank.com",
}

// familyDomains are real companies of the bank groups (cards, insurance,
// mutual funds, broking). They are not banks, but they must never be
// flagged as fake bank sites.
var familyDomains = []string{
	"sbicard.com", "sbilife.co.in", "sbimf.com", "sbisecurities.in", "sbigeneral.in",
	"hdfclife.com", "hdfcergo.com", "hdfcfund.com", "hdfcsec.com", "hdfc.com",
	"icicidirect.com", "iciciprulife.com", "icicilombard.com", "icicipruamc.com",
	"kotaksecurities.com", "kotaklife.com", "kotakmf.com", "axismf.com", "axisdirect.in",
	"axismaxlife.com", "hdfcsky.com", "pnbhousing.com", "barodabnpparibasmf.in", "canarahsbclife.com",
	"yesinvest.in", "bobcard.co.in", "dhan.co",
}

// brands are the name-parts scammers copy into fake addresses.
var brands = []string{
	"sbi", "onlinesbi", "yonosbi", "hdfc", "hdfcbank", "icici", "icicibank", "axis",
	"axisbank", "kotak", "pnb", "pnbindia", "bankofbaroda", "baroda", "canara",
	"canarabank", "unionbank", "indusind", "yesbank", "idfc", "idfcfirst",
	"federalbank", "bandhan", "rblbank", "ippb", "bankofindia", "centralbank",
	"indianbank", "ucobank", "dhan",
}

// shortBrands are too short to match as "part of a word"; they only count
// as an exact word or next to a banking word (sbi-login, sbionline).
var shortBrands = map[string]bool{
	"sbi": true, "pnb": true, "ippb": true, "axis": true, "idfc": true, "dhan": true,
	"baroda": true, "bandhan": true, "kotak": true, "canara": true,
}

// bankingWords next to a short brand make it suspicious (sbi-kyc, pnbnetbanking).
var bankingWords = []string{
	"bank", "netbanking", "online", "login", "kyc", "yono", "update", "verify", "secure",
	"rewards", "reward", "points", "card", "account", "otp", "pan", "aadhar", "aadhaar",
	"customer", "care", "support", "help", "refund", "pay", "upi", "net", "ibanking",
}

// tradingHosts open in the TRADING container.
var tradingDomains = []string{
	// charts
	"tradingview.com", "tradingview-widget.com", "investing.com", "in.investing.com", "chartink.com",
	"stockedge.com", "tickertape.in", "sensibull.com", "opstra.definedge.com", "definedge.com",
	"moneycontrol.com", "nseindia.com", "bseindia.com", "mcxindia.com", "trendlyne.com",
	// brokers (web trading terminals)
	"dhan.co", "zerodha.com", "kite.zerodha.com", "upstox.com", "angelone.in", "angelbroking.com",
	"groww.in", "fyers.in", "5paisa.com", "icicidirect.com", "hdfcsec.com", "hdfcsky.com",
	"kotaksecurities.com", "kotakneo.com", "motilaloswal.com", "sharekhan.com", "paytmmoney.com",
	"aliceblueonline.com", "finvasia.com", "shoonya.com", "samco.in", "m.stock", "mstock.com",
	"indiainfoline.com", "iifl.com", "axisdirect.in", "sbisecurities.in", "edelweiss.in",
	"nuvamawealth.com", "choiceindia.com", "flattrade.in", "zebu.co.in",
}

// terminalPort is the Ronin Terminal running on this computer.
const terminalPort = "8791"

// paymentDomains are payment gateways and 3-D Secure (OTP) page providers.
// Pages here stay in the current tab, because moving them breaks a payment.
var paymentDomains = []string{
	"razorpay.com", "payu.in", "payumoney.com", "billdesk.com", "ccavenue.com",
	"cashfree.com", "juspay.in", "paytm.in", "paytm.com", "phonepe.com", "easebuzz.in",
	"wibmo.com", "enstage-sas.com", "enstage.com", "paypal.com", "stripe.com",
	"mastercard.com", "visa.com", "npci.org.in", "rupay.co.in",
}

// authDomains are sign-in pages that trading sites use ("Sign in with Google").
var authDomains = []string{"accounts.google.com", "appleid.apple.com", "login.microsoftonline.com"}
