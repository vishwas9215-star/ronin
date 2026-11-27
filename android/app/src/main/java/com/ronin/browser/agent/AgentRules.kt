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
    private val LOCAL_NAME = Regex("(^|\\.)(localhost|local|internal|lan|home|corp|localdomain|home\\.arpa)$", RegexOption.IGNORE_CASE)
    // "PIN code" in India is the postal code: not a secret. MPIN / UPI PIN / ATM PIN are.
    private val SECRET_LABEL = Regex("otp|one.?time|verification code|\\b[mt]?pin\\b(?!\\s*-?\\s*code)|passcode|password|ओटीपी|पासवर्ड|पिन(?!\\s*कोड)", RegexOption.IGNORE_CASE)
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

    /**
     * Home network / this machine / CGNAT, by the address itself (no DNS here; the driver also checks what a name
     * resolves to). Browsers accept odd spellings of an IPv4 address (2130706433, 0x7f.1, 0177.0.0.1, 127.1) and
     * IPv4 inside IPv6 (::ffff:127.0.0.1): all are read as the address they mean. Anything unreadable counts as private.
     */
    fun isPrivateHost(h0: String): Boolean {
        val h = h0.lowercase().trim().trim('[', ']').trimEnd('.')
        if (h.isEmpty()) return true
        if (LOCAL_NAME.containsMatchIn(h)) return true
        val v4 = ipv4(h)
        if (v4 != null) return privateV4(v4)
        if (h.contains(':')) {
            if (!Regex("^[0-9a-f:.%a-z]+$").matches(h)) return true
            val a = try { java.net.InetAddress.getByName(h.substringBefore('%')) } catch (e: Exception) { return true }
            return privateAddress(a.address)
        }
        if (Regex("^[0-9.]+$").matches(h) || Regex("^(0x[0-9a-f]+\\.?)+$").matches(h)) return true // digits we could not read: refuse
        return false
    }

    /** A host written as an IPv4 address in any form browsers accept (1-4 parts, decimal / 0octal / 0xhex). */
    fun ipv4(h: String): ByteArray? {
        val parts = h.split('.')
        if (parts.isEmpty() || parts.size > 4 || parts.any { it.isEmpty() }) return null
        val nums = parts.map { p ->
            try {
                when {
                    p.startsWith("0x") -> if (p.length == 2) 0L else java.lang.Long.parseLong(p.substring(2), 16)
                    p.length > 1 && p.startsWith("0") -> java.lang.Long.parseLong(p.substring(1), 8)
                    else -> java.lang.Long.parseLong(p)
                }
            } catch (e: NumberFormatException) { return null }
        }
        var v = 0L
        for (i in 0 until nums.size - 1) { if (nums[i] > 255) return null; v = v or (nums[i] shl (24 - 8 * i)) }
        val last = nums.last(); val room = 32 - 8 * (nums.size - 1)
        if (last >= (1L shl room)) return null
        v = v or last
        return byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    }

    private fun privateV4(b: ByteArray): Boolean {
        val a = b[0].toInt() and 0xff; val c = b[1].toInt() and 0xff
        return a == 0 || a == 10 || a == 127 || (a == 169 && c == 254) || (a == 172 && c in 16..31) || (a == 192 && c == 168) ||
            (a == 100 && c in 64..127) || a >= 224
    }

    /** Any address (4 or 16 bytes): loopback, private, link-local, unique-local, CGNAT, IPv4-mapped private, multicast. */
    fun privateAddress(b: ByteArray): Boolean {
        if (b.size == 4) return privateV4(b)
        if (b.size != 16) return true
        val first10Zero = (0 until 10).all { b[it].toInt() == 0 }
        if (first10Zero && (b[10].toInt() and 0xff) == 0xff && (b[11].toInt() and 0xff) == 0xff) return privateV4(b.copyOfRange(12, 16)) // ::ffff:a.b.c.d
        if ((0 until 12).all { b[it].toInt() == 0 }) return true // ::, ::1 and the old ::a.b.c.d form
        val b0 = b[0].toInt() and 0xff; val b1 = b[1].toInt() and 0xff
        return (b0 and 0xfe) == 0xfc || (b0 == 0xfe && (b1 and 0xc0) == 0x80) || b0 == 0xff ||
            (b0 == 0x00 && b1 == 0x64 && b[2].toInt() == 0xff.toByte().toInt() && b[3].toInt() == 0x9b.toByte().toInt()) // 64:ff9b:: (NAT64 of anything) refused
    }

    /** A number Claude may not type: 4-8 digits into a secret-looking box, or anywhere on a page that asks for an OTP. */
    fun numberNeedsOwner(label: String, text: String, otpPage: Boolean): Boolean = looksLikeOtp(text) && (secretLabel(label) || otpPage)

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
        val scheme = url.substringBefore(':').lowercase()
        if (scheme !in setOf("http", "https", "ws", "wss")) return null // data:, blob:, about: never leave the device
        val u = try { URI(url) } catch (e: Exception) { return "unreadable address" }
        val h = host(url)
        if (h.isEmpty()) return "address without a host"
        if (isPrivateHost(h)) return "private address $h"
        val path = u.rawPath ?: ""
        val q = u.rawQuery ?: ""
        val m = method.uppercase()
        // money sites: NOTHING but reading, whoever started it (a page script after Claude's tap, a timer...);
        // only while the owner holds the screen is the guard off (the driver does not call it then)
        if (m != "GET" && m != "HEAD" && m != "OPTIONS" && mode(h, isMoney) == Mode.MONEY) return "$m to money site $h"
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
