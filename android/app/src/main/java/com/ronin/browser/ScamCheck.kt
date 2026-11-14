package com.ronin.browser

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.regex.Pattern

/**
 * "Is this message a scam?" for SMS and app notifications: the same rules and scoring as the
 * page script (ui/tools/scamcheck.js) — one heavy sign (asking for the OTP / PIN, "pay first",
 * "scan to receive", "stay on the video call", a friend's sudden "need money, pay on this QR")
 * or two ordinary signs; advice lowers the score. Rules: rules.json "scamRules". On the device only.
 */
class ScamCheck(o: JSONObject?, private val links: SafeLinks) {
    class Result(val level: String, val score: Int, val ids: List<String>, val reasons: List<String>, val rumour: Boolean)
    private class Group(val id: String, val w: Int, val re: Pattern, val why: String)

    private val groups: List<Group>
    private val caps: Pattern?
    private val advice: Pattern?
    private val done: Pattern?
    private val normal: Pattern?
    private val pincode: Pattern?
    private val rumour: List<Pattern>
    private val requests: Set<String>

    init {
        val g = o?.optJSONArray("groups") ?: JSONArray()
        groups = (0 until g.length()).mapNotNull { i ->
            val a = g.optJSONArray(i) ?: return@mapNotNull null
            rx(a.optString(2))?.let { Group(a.optString(0), a.optInt(1), it, a.optString(3)) }
        }
        caps = o?.optString("caps")?.takeIf { it.isNotEmpty() }?.let { try { Pattern.compile(it) } catch (e: Exception) { null } }
        advice = rx(o?.optString("advice")); done = rx(o?.optString("done")); normal = rx(o?.optString("normal")); pincode = rx(o?.optString("pincode"))
        val r = o?.optJSONArray("rumour") ?: JSONArray()
        rumour = (0 until r.length()).mapNotNull { rx(r.optString(it)) }
        val q = o?.optJSONArray("requests") ?: JSONArray()
        requests = (0 until q.length()).map { q.optString(it) }.toSet()
        require(groups.isNotEmpty() || o == null) { "scam rules did not load" }
    }

    private fun rx(src: String?): Pattern? {
        if (src.isNullOrEmpty()) return null
        return try { Pattern.compile(norm(src).replace("\\s+", "\\s*"), Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE) } catch (e: Exception) { null }
    }

    private val urlRe = Pattern.compile("(https?://[^\\s<>\"')]+|www\\.[^\\s<>\"')]+|\\b[a-z0-9-]+(\\.[a-z0-9-]+)*\\.(web\\.app|netlify\\.app|vercel\\.app|xyz|top|click|link|online|site|live|shop|icu|sbs|cfd|buzz|me|ly|gd|gl|ee)\\b[^\\s<>\"')]*)", Pattern.CASE_INSENSITIVE)

    fun text(t0: String?): Result {
        val raw = t0 ?: ""
        val t = norm(raw).take(6000)
        if (t.length < 8) return Result("", 0, emptyList(), emptyList(), false)
        val tt = pincode?.matcher(t)?.replaceAll(" ") ?: t
        var score = 0; var heavy = false
        val ids = ArrayList<String>(); val reasons = ArrayList<String>()
        for (g in groups) {
            if (g.re.matcher(tt).find()) {
                score += g.w; ids.add(g.id)
                if (g.w >= 3) heavy = true
                if (g.id != "link" && g.id != "urgent") reasons.add(g.why)
            }
        }
        // a link Safe Social would never open is a heavy sign itself
        val m = urlRe.matcher(raw)
        var n = 0
        while (m.find() && n++ < 10) {
            val u = m.group(1) ?: continue
            if (links.check(if (u.startsWith("http", true)) u else "https://$u").isNotEmpty()) {
                score += 3; heavy = true; ids.add("badlink"); reasons.add("message में ऐसा link है जो Ronin कभी नहीं खोलता")
                break
            }
        }
        if ("auth" !in ids && caps?.matcher(raw)?.find() == true) { score += 2; ids.add("auth"); reasons.add("खुद को पुलिस / CBI / कोर्ट बताना") }
        if (ids.none { it != "link" && it != "urgent" }) score = 0
        // advice / news about scams with nothing asked of the reader is information, not a scam
        val isAdvice = advice?.matcher(t)?.find() == true
        if (isAdvice && ids.none { it in requests }) score = minOf(score, 2)
        if (isAdvice) score -= 2
        if (done?.matcher(t)?.find() == true && !heavy) score -= 1
        if (normal?.matcher(t)?.find() == true) score -= 1
        val level = when { score >= 5 || (heavy && score >= 4) -> "stop"; score >= 3 -> "warn"; else -> "" }
        val rum = level.isEmpty() && rumour.any { it.matcher(t).find() }
        return Result(level, score, ids, if (level.isEmpty()) emptyList() else reasons, rum)
    }

    companion object {
        /** The same clean-up as the page script: no nukta / joiners, one kind of anusvara, lower case. */
        fun norm(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFC).replace(Regex("[़​-‍﻿]"), "").replace('ँ', 'ं')
                .replace('‘', '\'').replace('’', '\'').replace('“', '"').replace('”', '"').lowercase().replace(Regex("\\s+"), " ")
    }
}
