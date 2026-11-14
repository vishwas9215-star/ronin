package com.ronin.browser

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * Links Safe Social never opens (Telegram, group invites, UPI apps, call-forwarding codes,
 * app files, short links, remote-control apps, user@host / IP / punycode tricks, fake
 * bank-KYC-reward sites). Same rules as Windows (internal/safelinks) and the page script
 * (scamcheck.js); the word lists come from rules.json ("safeLinks").
 */
class SafeLinks(private val o: JSONObject?) {
    private fun list(k: String): List<String> {
        val a: JSONArray = o?.optJSONArray(k) ?: return emptyList()
        return (0 until a.length()).map { a.getString(it) }
    }
    private val paySchemes = list("paySchemes"); private val badSchemes = list("badSchemes")
    private val telegram = list("telegram"); private val invites = list("invites"); private val short = list("short")
    private val remote = list("remote"); private val apkHosts = list("apkHosts"); private val apkWords = list("apkWords")
    private val fileExt = list("fileExt"); private val moneyWords = list("moneyWords"); private val freeHosts = list("freeHosts")
    private val lureWords = list("lureWords"); private val officialEnds = list("officialEnds")
    val json: String = o?.toString() ?: "{}"

    private fun hostIs(host: String, d: String) = host == d || host.endsWith(".$d")
    private fun hostPathIn(host: String, path: String, l: List<String>): Boolean {
        for (d in l) {
            val j = d.indexOf('/')
            if (j > 0) { if (hostIs(host, d.substring(0, j)) && path.startsWith(d.substring(j))) return true; continue }
            if (hostIs(host, d)) return true
        }
        return false
    }
    private fun isIP(h: String) = h.contains(':') || h.startsWith("[") || h.startsWith("0x") || (h.isNotEmpty() && h.all { it.isDigit() || it == '.' })
    private fun tokens(s: String) = s.split(Regex("[.\\-_0-9]+")).filter { it.isNotEmpty() }

    /** The reason this link must not open from Safe Social, or "" (see internal/safelinks Check). */
    fun check(raw0: String): String {
        val raw = raw0.trim(); val low = raw.lowercase()
        val i = low.indexOf(':')
        if (i <= 0) return ""
        val scheme = low.substring(0, i)
        when {
            scheme in paySchemes -> return "pay"
            scheme == "tel" -> return if (low.contains('*') || low.contains('#') || low.contains("%2a") || low.contains("%23")) "ussd" else ""
            scheme == "whatsapp" -> return if (low.contains("code=") || low.contains("invite")) "invite" else ""
            scheme in badSchemes -> return "scheme"
            scheme != "http" && scheme != "https" -> return ""
        }
        val u = try { URI(raw) } catch (e: Exception) { return "" }
        if (u.rawUserInfo != null) return "at"
        // java.net.URI gives no host for names with "_": take it from the authority then
        val host = (u.host ?: u.rawAuthority?.substringAfterLast('@')?.substringBefore(':') ?: return "").lowercase().trimEnd('.')
        if (host.isEmpty()) return ""
        if (isIP(host)) return "ip"
        if (host.startsWith("xn--") || host.contains(".xn--")) return "puny"
        val path = (u.rawPath ?: "").lowercase()
        when {
            hostPathIn(host, path, telegram) -> return "telegram"
            hostPathIn(host, path, invites) -> return "invite"
            (hostIs(host, "wa.me") || hostIs(host, "whatsapp.com")) && (path.contains("invite") || path.contains("/join")) -> return "invite"
            hostPathIn(host, path, remote) -> return "remote"
            hostPathIn(host, path, short) -> return "short"
            hostPathIn(host, path, apkHosts) -> return "apk"
        }
        for (e in fileExt) if (path.endsWith(e)) return if (e.contains("apk")) "apk" else "file"
        val tok = tokens(host)
        if (tok.any { it in apkWords }) return "apk"
        if (officialEnds.any { hostIs(host, it) }) return ""
        var name = if (tok.size > 1) tok.dropLast(1) else tok
        if (name.size > 1 && name.last() in listOf("co", "com", "org", "net", "gov", "ac", "edu")) name = name.dropLast(1)
        for (t in name) for (w in moneyWords) if (t == w || (w.length >= 5 && (t.startsWith(w) || t.endsWith(w)))) return "money"
        var free = false; var words = name
        for (d in freeHosts) {
            if (hostIs(host, d) && host != d) free = true
            else if (host == d && d == "sites.google.com") { free = true; words = tokens(path) }
        }
        if (free && words.any { it in lureWords }) return "fake"
        if (name.any { it in BRANDS } && name.any { it in VERBS }) return "fake"
        return ""
    }

    companion object {
        val BRANDS = setOf("sbi", "hdfc", "icici", "axis", "pnb", "kotak", "canara", "bob", "paytm", "phonepe", "gpay", "uidai", "aadhaar", "aadhar", "epfo", "incometax", "irctc", "uppcl", "npci", "rbi", "sebi")
        val VERBS = setOf("kyc", "ekyc", "login", "verify", "verification", "update", "secure", "reward", "rewards", "refund", "cashback", "bonus", "support", "care", "helpline", "otp", "pan", "link", "points", "redeem", "block", "unblock")
    }
}
