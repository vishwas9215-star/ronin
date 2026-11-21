package com.ronin.browser

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * "🔍 कौन?" (owner, 21 Nov 2026: "बस मैं एक टैप करूँ कौन वाले पे और वो अपने आप रिसर्च करके उसका नाम सही से रख दे"):
 * one tap sends ONLY that number to a DuckDuckGo web search (no contacts, no call log), reads the results on the phone
 * and keeps the name the pages agree on ("9560911911" → Airtel: Airtel's broadband line in many posts). A private
 * person's mobile is usually on no page: then it says so honestly instead of guessing. No paid service, no API key.
 */
object WhoIs {
    data class Result(val title: String, val url: String, val snippet: String)
    /** name: the name the pages agree on; complaints: people rate it badly; fraud: pages call it a fraud / fake caller. */
    data class Answer(val name: String?, val complaints: Boolean, val source: String, val fraud: Boolean = false)

    // phone-lookup sites: they show every number, they never name it
    private val lookupSites = Regex("whocalls|numtrace|whitepages|numlookup|getcontact|who-called|whocalled|tellows|storycheck|ipqualityscore|411\\.com|truecaller|sync\\.me|callerid|spokeo|reportedcalls|shouldianswer|callfilter|spamcalls|unknownphone|phonenumber|reverse-?phone|calleridtest|numberguru|everycaller|whoisthatnumber|scamdb|phndir|scannero|numbertracker|reversecaller|cellnumbers|cyberdecode|tracker|trace|track|scam|lookup|locat|finder|numberinfo|mobilenumber|complaint|consumer|duckduckgo")
    // a page that says a scammer used this number (QA 21 Nov: "called from 98… saying my Airtel KYC expires" must not
    // name the scammer "Airtel"): no name then, a warning instead
    private val fraudRe = Regex("fraud|scam|fake|cheat|pretend|impersonat|beware|digital arrest|claiming to be|posing as|asked (for )?(my )?(otp|pin|cvv)|kyc (update|expir|block)", RegexOption.IGNORE_CASE)
    // operator / circle pages name the network of any mobile, never its owner
    private val circleRe = Regex("telecom circle|circle:|operator:|belongs to|network operator|mobile operator", RegexOption.IGNORE_CASE)
    private val ratingSites = Regex("shouldianswer|callfilter|tellows|spamcalls|unknownphone")
    private val stop = ("the and for you your with from this that are was were has have had not but all any can our out who what when where how why which " +
        "customer care number numbers phone mobile call calls called calling caller service services centre center complaint complaints india indian toll " +
        "free helpline reviews review user users contact support request requests please team official site website online free lookup reverse search find " +
        "details information company limited ltd pvt private address map maps store office branch hours open today day days new more read posts post " +
        "linkedin facebook instagram twitter youtube reddit quora justdial infobel yellowpages yellow pages sulekha indiamart tradeindia google " +
        "bengaluru bangalore delhi new mumbai kolkata chennai hyderabad lucknow pune jaipur noida gurgaon gurugram ahmedabad siliguri panipat karnal kanpur " +
        "varanasi prayagraj sultanpur uttar pradesh bihar haryana karnataka maharashtra gujarat rajasthan state city district " +
        "got received pending spam fraud scam fake alert beware reported report asked saying said still ticket otp kyc sim reliance vodafone idea bsnl " +
        "telecom operator circle location east west north south truecaller duckduckgo unknown genuine app bank " +
        "help need seeking guide thinking going hi hello dear sir madam mr ms shri thanks thank sms whatsapp message messages days ago hours minutes registered regarding issue issues problem " +
        "delay delayed refund cancellation port shifting shift installation connection broadband wifi request raised fed following follow " +
        "january february march april may june july august september october november december monday tuesday wednesday thursday friday saturday sunday").split(" ").toSet()

    /** The results of DuckDuckGo's plain HTML page. */
    fun parse(html: String): List<Result> {
        val out = ArrayList<Result>()
        val blocks = html.split("class=\"result results_links").drop(1)
        for (b in blocks) {
            if (b.substringBefore(">").contains("result--ad")) continue // DuckDuckGo's own ads
            val a = Regex("class=\"result__a\"[^>]*href=\"([^\"]*)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).find(b) ?: continue
            val sn = Regex("class=\"result__snippet\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).find(b)?.groupValues?.get(1) ?: ""
            var url = a.groupValues[1]
            Regex("uddg=([^&]+)").find(url)?.let { url = try { URLDecoder.decode(it.groupValues[1], "UTF-8") } catch (e: Exception) { url } }
            out.add(Result(text(a.groupValues[2]), url, text(sn)))
        }
        return out
    }

    private fun text(h: String): String = h.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'")
        .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace(Regex("\\s+"), " ").trim()

    /** The number without the country / trunk prefix: +91 95609 11911 → 9560911911, 080… → 80…, 1800… stays. */
    fun local(number: String): String {
        val d = number.filter { it.isDigit() }
        return when {
            d.length == 12 && d.startsWith("91") -> d.drop(2)
            d.length == 11 && d.startsWith("0") -> d.drop(1)
            d.length == 13 && d.startsWith("91") && d[2] == '0' -> d.drop(3)
            else -> d
        }
    }

    /** The number as pages write it: 9560911911, 95609 11911, 95609-11911, +91 9560911911, 080 6746 6729, (080) 6746-6729 … */
    fun numberRe(number: String): Regex =
        Regex("(?<!\\d)(?:\\+?91[\\s-]?|\\(?0)?" + local(number).toCharArray().joinToString("[\\s.()-]{0,2}") + "(?!\\d)")

    /**
     * The name the pages agree on: a page that carries this number counts 1, another page of the same search ½ (Swiggy's
     * line is named on one post, the brand on two more); the name must be on a page with the number, total ≥ 2.
     */
    fun decide(number: String, results: List<Result>): Answer {
        val nre = numberRe(number)
        val score = HashMap<String, Double>(); val withNumber = HashSet<String>(); val shown = HashMap<String, String>()
        val hosts = HashMap<String, HashSet<String>>(); val inHost = HashSet<String>()
        var complaints = false; var fraud = false; var source = ""
        for (r in results) {
            val host = Regex("^(?:https?:)?//(?:www\\.)?([^/]+)").find(r.url)?.groupValues?.get(1) ?: ""
            val body = r.title + " " + r.snippet
            val has = nre.containsMatchIn(body + " " + r.url)
            if (has && ratingSites.containsMatchIn(host) && Regex("negative|spam|fraud|scam|harass|threat", RegexOption.IGNORE_CASE).containsMatchIn(body)) complaints = true
            if (has && fraudRe.containsMatchIn(body)) fraud = true
            if (lookupSites.containsMatchIn(host) || circleRe.containsMatchIn(body)) continue
            // each page counts a word once; a word must be written Capitalised somewhere (a name, not "delivery")
            val seen = HashSet<String>()
            val labels = host.split('.').dropLast(1).filter { it.length >= 3 }
            val words = Regex("[#@]?([A-Za-z][A-Za-z&]{2,24})").findAll(body).map { it.groupValues[1] } + labels
            for (w in words) {
                val k = w.lowercase()
                if (k in stop || k in seen) continue
                seen.add(k)
                score[k] = (score[k] ?: 0.0) + if (has) 1.0 else 0.5
                hosts.getOrPut(k) { HashSet() }.add(host)
                if (has) withNumber.add(k)
                if (k in labels) inHost.add(k)
                if (w[0].isUpperCase() && w.drop(1).any { it.isLowerCase() }) shown.putIfAbsent(k, w)
            }
            if (has && source.isEmpty()) source = host
        }
        if (fraud) return Answer(null, true, source, fraud = true)
        // on two different sites at least (two tweets saying "Pending" are one site), on a page with the number, score >= 2;
        // ties: the one that is also a site's own name (airtel.in), then a-z (always the same answer)
        val best = score.filter { it.value >= 2.0 && it.key in withNumber && shown.containsKey(it.key) && (hosts[it.key]?.size ?: 0) >= 2 }
            .entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenByDescending { it.key in inHost }.thenBy { it.key }).firstOrNull()
        return Answer(best?.let { shown[it.key] }, complaints, source)
    }

    /** One search for one number (only when the owner taps 🔍). Null when the internet / the search did not answer. */
    fun lookUp(number: String): Answer? {
        val d = number.filter { it.isDigit() }
        if (d.length < 8) return null // 121, 199, short codes: every page has them
        val q = local(d)
        var c: HttpURLConnection? = null
        return try {
            c = URL("https://html.duckduckgo.com/html/").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 10_000; c.readTimeout = 15_000; c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36")
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.setRequestProperty("Accept-Language", "en-IN,en;q=0.9")
            c.outputStream.use { it.write(("q=" + URLEncoder.encode(q, "UTF-8") + "&kl=in-en").toByteArray()) }
            // a "are you a robot?" page (202) or a redirect: "try again", never a saved "no name"
            if (c.responseCode != 200) return null
            val buf = CharArray(8192); val sb = StringBuilder()
            c.inputStream.bufferedReader().use { r -> while (sb.length < 600_000) { val n = r.read(buf); if (n < 0) break; sb.append(buf, 0, n) } }
            val html = sb.toString()
            val results = parse(html)
            when {
                results.isNotEmpty() -> decide(q, results)
                html.contains("result--no-result") || html.contains("No results") -> Answer(null, false, "")
                else -> null // an unexpected page: try again later
            }
        } catch (t: Throwable) { null } finally { try { c?.disconnect() } catch (e: Exception) { } }
    }
}
