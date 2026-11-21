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
    data class Answer(val name: String?, val complaints: Boolean, val source: String)

    // phone-lookup sites: they show every number, they never name it
    private val lookupSites = Regex("whocalls|numtrace|whitepages|numlookup|getcontact|who-called|whocalled|tellows|storycheck|ipqualityscore|411\\.com|truecaller|sync\\.me|callerid|spokeo|reportedcalls|shouldianswer|callfilter|spamcalls|unknownphone|phonenumber|reverse-?phone|calleridtest|numberguru|everycaller|whoisthatnumber|scamdb|phndir|scannero|numbertracker|reversecaller|cellnumbers|cyberdecode|tracker|trace|scam")
    private val ratingSites = Regex("shouldianswer|callfilter|tellows|spamcalls|unknownphone")
    private val stop = ("the and for you your with from this that are was were has have had not but all any can our out who what when where how why which " +
        "customer care number numbers phone mobile call calls called calling caller service services centre center complaint complaints india indian toll " +
        "free helpline reviews review user users contact support request requests please team official site website online free lookup reverse search find " +
        "details information company limited ltd pvt private address map maps store office branch hours open today day days new more read posts post " +
        "linkedin facebook instagram twitter youtube reddit quora justdial infobel yellowpages yellow pages sulekha indiamart tradeindia google " +
        "bengaluru bangalore delhi new mumbai kolkata chennai hyderabad lucknow pune jaipur noida gurgaon gurugram ahmedabad siliguri panipat karnal kanpur " +
        "varanasi prayagraj sultanpur uttar pradesh bihar haryana karnataka maharashtra gujarat rajasthan state city district " +
        "help need seeking guide thinking going hi hello dear sir madam mr ms shri thanks thank sms whatsapp message messages days ago hours minutes registered regarding issue issues problem " +
        "delay delayed refund cancellation port shifting shift installation connection broadband wifi request raised fed following follow " +
        "january february march april may june july august september october november december monday tuesday wednesday thursday friday saturday sunday").split(" ").toSet()

    /** The results of DuckDuckGo's plain HTML page. */
    fun parse(html: String): List<Result> {
        val out = ArrayList<Result>()
        val blocks = html.split("class=\"result results_links").drop(1)
        for (b in blocks) {
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

    /** The number as pages write it: 9560911911, 95609 11911, 95609-11911, +91 9560911911, 080 6746 6729 … */
    fun numberRe(number: String): Regex {
        val d = number.filter { it.isDigit() }.let { if (it.length > 10) it.takeLast(10) else it }
        return Regex(d.toCharArray().joinToString("[\\s.-]?"))
    }

    /**
     * The name the pages agree on: a page that carries this number counts 1, another page of the same search ½ (Swiggy's
     * line is named on one post, the brand on two more); the name must be on a page with the number, total ≥ 2.
     */
    fun decide(number: String, results: List<Result>): Answer {
        val nre = numberRe(number)
        val score = HashMap<String, Double>(); val withNumber = HashSet<String>(); val shown = HashMap<String, String>()
        var complaints = false; var source = ""
        for (r in results) {
            val host = Regex("^(?:https?:)?//(?:www\\.)?([^/]+)").find(r.url)?.groupValues?.get(1) ?: ""
            val has = nre.containsMatchIn(r.title + " " + r.snippet + " " + r.url)
            if (has && ratingSites.containsMatchIn(host) && Regex("negative|spam|fraud|scam|harass|threat", RegexOption.IGNORE_CASE).containsMatchIn(r.snippet + r.title)) complaints = true
            if (lookupSites.containsMatchIn(host)) continue
            // each page counts a word once; a word must be written Capitalised somewhere (a name, not "delivery")
            val seen = HashSet<String>()
            val words = Regex("[#@]?([A-Za-z][A-Za-z&]{2,24})").findAll(r.title + " " + r.snippet).map { it.groupValues[1] } +
                host.split('.').dropLast(1).filter { it.length >= 3 }
            for (w in words) {
                val k = w.lowercase()
                if (k in stop || k in seen) continue
                seen.add(k)
                score[k] = (score[k] ?: 0.0) + if (has) 1.0 else 0.5
                if (has) withNumber.add(k)
                if (w[0].isUpperCase() && w.drop(1).any { it.isLowerCase() }) shown.putIfAbsent(k, w)
            }
            if (has && source.isEmpty()) source = host
        }
        val best = score.filter { it.value >= 2.0 && it.key in withNumber && shown.containsKey(it.key) }.maxByOrNull { it.value }
        return Answer(best?.let { shown[it.key] }, complaints, source)
    }

    /** One search for one number (only when the owner taps 🔍). Null when the internet / the search did not answer. */
    fun lookUp(number: String): Answer? = try {
        val d = number.filter { it.isDigit() }
        val q = if (d.length > 10 && d.startsWith("91")) d.takeLast(10) else d
        val c = URL("https://html.duckduckgo.com/html/").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 10_000; c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36")
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.setRequestProperty("Accept-Language", "en-IN,en;q=0.9")
        c.outputStream.use { it.write(("q=" + URLEncoder.encode(q, "UTF-8") + "&kl=in-en").toByteArray()) }
        val html = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        decide(q, parse(html))
    } catch (e: Exception) { null }
}
