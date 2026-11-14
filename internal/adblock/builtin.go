package adblock

// builtinBlock works even before the full lists are downloaded the first time.
var builtinBlock = []string{
	// Google ad and tracking servers
	"doubleclick.net", "googlesyndication.com", "googleadservices.com", "googletagservices.com",
	"adservice.google.com", "adservice.google.co.in", "pagead2.googlesyndication.com",
	"googletagmanager.com", "google-analytics.com", "imasdk.googleapis.com", "partnerad.l.google.com",
	"ade.googlesyndication.com", "adtrafficquality.google",
	// other big ad networks and trackers
	"adnxs.com", "adsrvr.org", "criteo.com", "criteo.net", "taboola.com", "outbrain.com",
	"amazon-adsystem.com", "media.net", "pubmatic.com", "rubiconproject.com", "openx.net",
	"casalemedia.com", "indexww.com", "smartadserver.com", "advertising.com", "adform.net",
	"moatads.com", "scorecardresearch.com", "quantserve.com", "zedo.com", "yieldmo.com",
	"sharethrough.com", "teads.tv", "33across.com", "adcolony.com", "applovin.com",
	"inmobi.com", "unityads.unity3d.com", "chartboost.com", "vungle.com", "mopub.com",
	"popads.net", "popcash.net", "propellerads.com", "propellerclick.com", "adsterra.com",
	"exoclick.com", "juicyads.com", "trafficjunky.net", "hilltopads.net", "clickadu.com",
	"mgid.com", "revcontent.com", "contentad.net", "zergnet.com", "adskeeper.com",
	"onclickads.net", "onclkds.com", "adcash.com", "admaven.com", "monetag.com",
	"bidvertiser.com", "infolinks.com", "vidoomy.com", "spotxchange.com", "springserve.com",
	"lijit.com", "sovrn.com", "33across.com", "gumgum.com", "undertone.com", "connatix.com",
	"primis.tech", "ad.doubleclick.net", "adsafeprotected.com", "doubleverify.com",
	"adlightning.com", "confiant-integrations.net", "bounceexchange.com", "hotjar.com",
	"mouseflow.com", "crazyegg.com", "fullstory.com", "clarity.ms", "newrelic.com",
	"nr-data.net", "segment.io", "mixpanel.com", "amplitude.com", "branch.io",
	"app-measurement.com", "adjust.com", "appsflyer.com", "kochava.com",
	// ad and pop-up networks used on video and adult sites
	"pemsrv.com", "magsrv.com", "tsyndicate.com", "tsyndicate.net", "exosrv.com", "exdynsrv.com",
	"realsrv.com", "wpadmngr.com", "a-ads.com", "adxpansion.com", "plugrush.com", "ero-advertising.com",
	"trafficstars.com", "tsyndicate.org", "adspyglass.com", "bongacash.com",
	"stripcash.com", "awempire.com",
	"crakrevenue.com", "tubecorporate.com",
	"popmyads.com", "poptm.com", "hilltopads.com", "galaksion.com", "rtmark.net", "zeropark.com",
	"adtng.com", "brazzersnetwork.com", "trafficfactory.biz",
	"orbsrv.com", "syndication.realsrv.com", "contentabc.com", "dtscout.com", "kimberlite.io",
	"trafficjunky.net", "trafficjunky.com", "juicyads.com", "adsterra.com", "admaven.com",
	"propellerads.com", "propu.sh", "clickadu.com", "adcash.com", "onclickads.net", "popcash.net",
	"popads.net", "monetag.com", "aclib.net", "adsco.re", "twinrdsrv.com", "xlivrdr.com",
	"chaturbate-ads.com", "imglnkd.com", "imglnke.com", "a.realsrv.com", "mnaspm.com",
	// Indian ad networks often seen on news sites
	"vdo.ai", "colombiaonline.com", "colombia.com", "izooto.com", "affinity.com", "adgebra.co.in",
	"tyroo.com", "komli.com", "vserv.com", "truvidplayer.com", "adpushup.com",
	// Facebook / Microsoft / others tracking pixels
	"connect.facebook.net", "pixel.facebook.com", "an.facebook.com", "bat.bing.com",
	"ads.linkedin.com", "analytics.tiktok.com", "ads-api.tiktok.com", "ads.twitter.com",
	"static.ads-twitter.com", "analytics.twitter.com", "sc-static.net", "tr.snapchat.com",
}

// builtinAllow keeps important sites working even if a downloaded list is
// too strict.
var builtinAllow = []string{
	"youtube.com", "youtu.be", "googlevideo.com", "ytimg.com", "ggpht.com",
	"google.com", "gstatic.com", "googleapis.com", "accounts.google.com",
	"tradingview.com", "tradingview-widget.com", "dhan.co", "upstox.com", "zerodha.com",
	"nseindia.com", "bseindia.com", "sebi.gov.in", "npci.org.in",
	"razorpay.com", "payu.in", "billdesk.com", "paytm.in", "phonepe.com", "cashfree.com",
	"bank.in", "sbi", "onlinesbi.sbi", "hdfcbank.com", "icicibank.com", "axisbank.com",
	"kotak.com", "pnbindia.in", "bankofbaroda.in", "microsoft.com", "live.com", "office.com",
	"whatsapp.com", "whatsapp.net", "telegram.org", "web.telegram.org", "instagram.com",
	"cdninstagram.com", "fbcdn.net", "facebook.com", "diksha.gov.in", "ncert.nic.in",
	"wikipedia.org", "wikimedia.org", "github.com", "githubusercontent.com",
}

// thirdPartyBlock are sites people may visit on purpose, but which are also
// shown as adverts inside other sites. They are blocked only when another
// site loads them.
var thirdPartyBlock = []string{
	"chaturbate.com", "stripchat.com", "livejasmin.com", "cam4.com", "camsoda.com", "bongacams.com",
	"jerkmate.com", "fuckbook.com", "adultfriendfinder.com", "xhamsterlive.com", "cams.com",
	"dream11.com", "1xbet.com", "stake.com", "parimatch.in", "rummycircle.com",
}
