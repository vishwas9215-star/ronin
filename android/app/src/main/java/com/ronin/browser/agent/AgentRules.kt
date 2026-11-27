package com.ronin.browser.agent

import java.net.URI

/**
 * Ronin Agent: the rules that decide what Claude may do in the owner's own Ronin.
 * Pure logic (no Android), so it is unit-tested on every build. The owner's choices (27 Nov 2026):
 *  - no time limit, no taps: Claude works as long as he says, on any normal site, at the pace he asks;
 *  - never: passwords / OTPs, human checks, orders / payments, money sites (read only), home-network addresses;
 *  - where the old design asked the owner, the action is now simply refused and Claude tells him in chat.
 */
object AgentRules {
    /** Broker / bank / payment sites (plus Ronin's own bank list via [isMoney]): Claude may only read. */
    val FINANCE = Regex("(^|\\.)(dhan\\.co|zerodha\\.com|fyers\\.in|upstox\\.com|angelone\\.in|groww\\.in|5paisa\\.com|icicidirect\\.com|hdfcsec\\.com|sharekhan\\.com|kotaksecurities\\.com|motilaloswal\\.com|paytm\\.com|phonepe\\.com|onlinesbi\\.sbi|sbi\\.co\\.in|hdfcbank\\.com|icicibank\\.com|axisbank\\.com|kotak\\.com|pnbindia\\.in|bankofbaroda\\.in|razorpay\\.com|payu\\.in|billdesk\\.com|ccavenue\\.com|paypal\\.com|npci\\.org\\.in)$", RegexOption.IGNORE_CASE)
    val GOV = Regex("(\\.gov\\.in|\\.nic\\.in)$", RegexOption.IGNORE_CASE)
    val MONEY_PATH = Regex("order|payment|\\bpay|transfer|withdraw|checkout|fund|trade|neft|imps|upi", RegexOption.IGNORE_CASE)
    val CONFIRM_QUERY = Regex("confirm|place|submit|execute|pay", RegexOption.IGNORE_CASE)
    val ACTION_WORD = Regex("delete|remove|unsubscribe|confirm|cancel|logout|log-out|signout|approve|order|pay|transfer|vote", RegexOption.IGNORE_CASE)
    val ACTION_KEY = Regex("action|confirm|^do$|cmd|^op$|delete|remove|unsub|approve|vote|pay|order|token", RegexOption.IGNORE_CASE)
    private val PRIVATE = Regex("^(localhost|127\\.|10\\.|192\\.168\\.|172\\.(1[6-9]|2\\d|3[01])\\.|169\\.254\\.|0\\.|100\\.(6[4-9]|[7-9]\\d|1[01]\\d|12[0-7])\\.|::1$|\\[::1]$|f[cd][0-9a-f]{2}:|fe80:)", RegexOption.IGNORE_CASE)
    private val SECRET_LABEL = Regex("otp|one.?time|verification code|pin\\b|passcode|password|ओटीपी|पासवर्ड|पिन", RegexOption.IGNORE_CASE)
    private val ID_PATTERN = Regex("\\b\\d{4}\\s?\\d{4}\\s?\\d{4}\\b|\\b[A-Z]{5}\\d{4}[A-Z]\\b|\\b\\d{9,18}\\b")

    enum class Mode { MONEY, NORMAL }

    fun host(url: String): String = try { (URI(url).host ?: "").lowercase().trim('[', ']').trimEnd('.') } catch (e: Exception) { "" }

    /** a.b.co.in -> b.co.in, x.y.com -> y.com (enough for "is it another site?") */
    fun registrable(h: String): String {
        val p = h.split('.').filter { it.isNotEmpty() }
        if (p.size <= 2) return h
        return if (p[p.size - 2] in setOf("co", "gov", "nic", "org", "net", "ac", "edu", "res") && p.size > 2) p.takeLast(3).joinToString(".") else p.takeLast(2).joinToString(".")
    }

    fun mode(h: String, isMoney: (String) -> Boolean = { false }): Mode = if (FINANCE.containsMatchIn(h) || isMoney(h)) Mode.MONEY else Mode.NORMAL

    fun isPrivateHost(h: String): Boolean = PRIVATE.containsMatchIn(h)

    fun sensitive(h: String, isMoney: (String) -> Boolean, idHosts: Set<String>): Boolean =
        mode(h, isMoney) == Mode.MONEY || GOV.containsMatchIn(h) || h in idHosts

    /** Why Claude may not open this address (null = fine). [readPages] = pages the owner listed for money sites. */
    fun openRefusal(url: String, isMoney: (String) -> Boolean, readPages: Map<String, Set<String>>, taint: String?): String? {
        val u = try { URI(url) } catch (e: Exception) { return "not a web address" }
        val scheme = (u.scheme ?: "").lowercase()
        if (scheme != "http" && scheme != "https") return "$scheme: addresses are not allowed (only http / https)"
        val h = host(url)
        if (h.isEmpty()) return "not a web address"
        if (isPrivateHost(h)) return "home-network / server addresses are never opened by Claude"
        if (mode(h, isMoney) == Mode.MONEY) {
            val path = (u.rawPath ?: "").ifEmpty { "/" }
            if (u.rawQuery != null) return "money site: no query strings for Claude"
            if (path != "/" && path !in (readPages[h] ?: emptySet())) return "$h$path is not one of the owner's read pages for this money site"
        }
        // data leaving: after Claude read a sensitive page, another site may not receive data in its address
        if (taint != null && registrable(h) != registrable(taint)) {
            val payload = (u.rawQuery ?: "").length + (u.rawFragment ?: "").length
            if (payload > 30) return "Claude read $taint (sensitive); sending data to $h in the address is refused"
        }
        val payload = (u.rawQuery ?: "").length + (u.rawFragment ?: "").length
        if (payload > 300) return "too much data in the address"
        return null
    }

    /** A link Claude wants to follow that looks like it DOES something (heuristic). */
    fun actionLink(href: String, base: String): Boolean {
        if (Regex("^\\s*(javascript|vbscript):", RegexOption.IGNORE_CASE).containsMatchIn(href)) return true
        return try {
            val u = URI(base).resolve(href.trim())
            val last = (u.path ?: "").split('/').lastOrNull { it.isNotEmpty() } ?: ""
            val keys = (u.rawQuery ?: "").split('&').map { it.substringBefore('=') }
            ACTION_WORD.containsMatchIn(last) || (u.path ?: "").contains("/api/", true) || keys.any { it.isNotEmpty() && ACTION_KEY.containsMatchIn(it) }
        } catch (e: Exception) { true }
    }

    fun scriptLink(href: String) = Regex("^\\s*(javascript|vbscript):", RegexOption.IGNORE_CASE).containsMatchIn(href)

    /** Text that looks like an OTP / PIN (4-8 digits, spaces / dashes ignored). */
    fun looksLikeOtp(text: String): Boolean = Regex("^\\d{4,8}$").matches(text.replace(Regex("[\\s\\-.,_]"), ""))

    fun secretLabel(label: String) = SECRET_LABEL.containsMatchIn(label)

    /** Native check: what the engine told the keyboard about the focused box (EditorInfo.inputType). */
    fun secretInputType(inputType: Int): Boolean {
        val cls = inputType and 0x0000000f            // InputType.TYPE_MASK_CLASS
        val variation = inputType and 0x00000ff0      // InputType.TYPE_MASK_VARIATION
        return (cls == 0x00000001 && (variation == 0x00000080 || variation == 0x000000e0 || variation == 0x00000090)) || // text: PASSWORD, WEB_PASSWORD, VISIBLE_PASSWORD
            (cls == 0x00000002 && variation == 0x00000010)                                                                // number: NUMBER_VARIATION_PASSWORD
    }

    fun hasIdNumbers(text: String) = ID_PATTERN.containsMatchIn(text)

    /**
     * The network guard. Runs for every request of the Claude tab while the owner is NOT holding the screen.
     * claudeInput = Claude's own tap / key happened in the last 2 s.  Returns the reason to block, or null.
     */
    fun guard(method: String, url: String, mainFrame: Boolean, claudeInput: Boolean, isMoney: (String) -> Boolean, taint: String?, currentHost: String): String? {
        val h = host(url)
        if (h.isEmpty()) return null
        if (isPrivateHost(h)) return "private address $h"
        val u = try { URI(url) } catch (e: Exception) { return null }
        val path = u.rawPath ?: ""
        val q = u.rawQuery ?: ""
        val m = method.uppercase()
        if (claudeInput && m != "GET" && mode(h, isMoney) == Mode.MONEY) return "$m to money site $h"
        if (m != "GET" && MONEY_PATH.containsMatchIn("$path?$q")) return "$m $path (money / order request)"
        if (m == "GET" && MONEY_PATH.containsMatchIn(path) && CONFIRM_QUERY.containsMatchIn(q)) return "GET $path?$q (money / order request)"
        if (claudeInput && mainFrame && taint != null && h != currentHost && registrable(h) != registrable(taint) && (q.length + (u.rawFragment ?: "").length) > 30)
            return "leaving $taint (sensitive data was read) for $h with data in the address"
        return null
    }

    /** Hide ID numbers from Claude (Aadhaar-like, PAN, long account numbers). */
    fun redact(s: String): String = s
        .replace(Regex("\\b\\d{4}\\s?\\d{4}\\s?\\d{4}\\b"), "[aadhaar-like hidden]")
        .replace(Regex("\\b[A-Z]{5}\\d{4}[A-Z]\\b"), "[PAN hidden]")
        .replace(Regex("\\b\\d{9,18}\\b"), "[number hidden]")
}
