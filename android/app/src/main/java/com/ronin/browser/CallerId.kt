package com.ronin.browser

import android.content.Context
import android.provider.CallLog
import android.provider.Telephony

/**
 * Who an unknown caller probably is, only from what is on this phone (owner, 21 Nov 2026: "जो कांटेक्ट में नहीं है उन
 * सभी को रेड कर रहा है... Airtel broadband वाला, Swiggy order वाला... इसे स्मार्ट बनाओ"). Nothing leaves the phone, no
 * lookup service. Red only for a real risk (hidden, foreign, a number from a scam SMS); everyone else gets a calm hint:
 * a bank's 1600 line, "the number was in your Airtel SMS", "you called them", "came while your Swiggy order was on".
 */
object CallerId {
    /** risk = red row, ok = a known service line, known = you dealt with them / your order, info = calm grey hint. */
    data class Who(val level: String, val text: String)

    /** What this phone knows: numbers you called / talked to, numbers inside company SMS, scam numbers, recent orders. */
    class Known(
        val called: Set<String> = emptySet(),
        val service: Map<String, String> = emptyMap(),
        val scam: Set<String> = emptySet(),
        val orders: List<Pair<Long, String>> = emptyList(),
    )

    fun key(n: String): String = n.filter { it.isDigit() }.takeLast(10)

    private val numRe = Regex("(?<![\\d])(?:\\+?91[\\s-]?|0)?([6-9]\\d{4}[\\s-]?\\d{5})(?![\\d])|(?<![\\d])(0\\d{2,4}[\\s-]?\\d{6,8})(?![\\d])|(?<![\\d])(1600\\d{6})(?![\\d])")
    fun numbersIn(body: String): List<String> = numRe.findAll(body).map { m -> key(m.value) }.filter { it.length == 10 }.distinct().toList()

    /** A DLT company header ("VM-AIRTEL-S"): only these can vouch for a number (a number can send any text). */
    fun companyHeader(address: String): Boolean = Regex("^[A-Z]{2}-[A-Z0-9]{3,9}(-[A-Z])?$").matches(address.uppercase().trim())
    fun promo(address: String): Boolean = address.uppercase().trim().endsWith("-P")

    private val orderRe = Regex("\\b(order|delivery|deliver(ed|ing)?|out for delivery|rider|delivery (partner|executive|agent|boy)|technician|engineer|installation|install|visit|booking|appointment|executive|field agent|our (agent|team) will call|will call you|pickup|courier|shipment|shipped|dispatched|service request|complaint no)\\b", RegexOption.IGNORE_CASE)
    fun orderLike(body: String): Boolean = orderRe.containsMatchIn(body)

    /** "AIRTEL", "SWIGGY", "AIRBRD" → "Airtel", "Swiggy", "Airbrd". */
    fun brand(address: String): String = address.trim().uppercase().replace(Regex("^[A-Z]{2}-"), "").replace(Regex("-[A-Z]$"), "")
        .lowercase().replaceFirstChar { it.uppercase() }

    fun judge(number: String, contact: Boolean, cnap: String, k: Known, at: Long = System.currentTimeMillis()): Who? {
        val d = CallInfo.digits(number)
        if (d.isEmpty()) return Who("risk", L("छुपा हुआ नंबर (private): सावधान", "Hidden number (private): be careful"))
        if (CallInfo.isEmergency(d) || contact) return null
        val n = key(d)
        val sim = if (cnap.isNotEmpty()) L("📛 SIM पर नाम: ", "📛 Name on the SIM: ") + cnap + " · " else ""
        if (d.startsWith("+") && !d.startsWith("+91")) return Who("risk", L("🌍 विदेश का नंबर: अनजान हो तो न उठाएँ, पैसे / OTP न दें", "🌍 Foreign number: if you do not know them, do not answer; never give money / OTP"))
        if (n in k.scam) return Who("risk", sim + L("⛔ यह नंबर ठगी वाले SMS में आया था: पैसे / OTP न दें", "⛔ This number was in a scam SMS: never give money / OTP"))
        if (d.removePrefix("+91").removePrefix("0").startsWith("1600")) return Who("ok", L("🏦 बैंक / सरकारी दफ़्तर की असली service line (1600) · फिर भी OTP न बताएँ", "🏦 A real bank / government service line (1600) · still never tell an OTP"))
        k.service[n]?.let { return Who("ok", sim + L("🛠 आपके $it वाले SMS में यही नंबर था", "🛠 This number was in your $it SMS")) }
        if (n in k.called) return Who("known", sim + L("📞 आपने इन्हें पहले call किया था / बात हुई थी", "📞 You called / talked to them before"))
        if (d.removePrefix("+91").startsWith("140")) return Who("info", L("📢 Telemarketing (140 वाला नंबर)", "📢 Telemarketing (a 140 number)"))
        k.orders.lastOrNull { at - it.first in 0..6 * 3600_000L }?.let { return Who("known", sim + L("🛵 ${it.second} के order / service के समय आई call: शायद वही हों · OTP / पैसे न दें", "🛵 Came while your ${it.second} order / service was on: maybe them · never give OTP / money")) }
        if (sim.isNotEmpty()) return Who("info", sim.removeSuffix(" · ") + L(" · अनजान नंबर", " · not in contacts"))
        return Who("info", L("👤 अनजान नंबर", "👤 Not in contacts"))
    }

    @Volatile private var cache: Pair<Long, Known>? = null

    /** Read once a minute at most: the last 500 calls and the last 30 days of SMS (on the phone only). */
    fun known(ctx: Context): Known {
        cache?.let { (at, k) -> if (System.currentTimeMillis() - at < 60_000) return k }
        return read(ctx).also { cache = System.currentTimeMillis() to it }
    }

    private fun read(ctx: Context): Known {
        val called = HashSet<String>(); val service = HashMap<String, String>(); val scam = HashSet<String>(); val orders = ArrayList<Pair<Long, String>>()
        try {
            ctx.contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DURATION), null, null, CallLog.Calls.DATE + " DESC LIMIT 500")?.use { c ->
                while (c.moveToNext()) {
                    val t = c.getInt(1); val dur = c.getLong(2)
                    if (t == CallLog.Calls.OUTGOING_TYPE || (t == CallLog.Calls.INCOMING_TYPE && dur >= 30)) key(c.getString(0) ?: "").takeIf { it.length == 10 }?.let { called.add(it) }
                }
            }
        } catch (e: Exception) { }
        try {
            val now = System.currentTimeMillis()
            val scamRules = RoninApp.instance.rules.scam
            ctx.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf("address", "body", "date"), "date > ?", arrayOf((now - 30 * 86_400_000L).toString()), "date DESC LIMIT 1500")?.use { c ->
                while (c.moveToNext()) {
                    val a = c.getString(0) ?: ""; val b = c.getString(1) ?: ""; val at = c.getLong(2)
                    val nums = numbersIn(b)
                    val header = companyHeader(a)
                    // the scam check only where it can mark a number: numbers in the text, or a number as the sender
                    val bad = (nums.isNotEmpty() || !header) && scamRules.text(b).level == "stop"
                    if (bad) { scam.addAll(nums); key(a).takeIf { it.length == 10 && !header }?.let { scam.add(it) }; continue }
                    if (!header || promo(a)) continue
                    val name = brand(a)
                    if (now - at < 14 * 86_400_000L) nums.forEach { service.putIfAbsent(it, name) }
                    if (now - at < 24 * 3600_000L && orderLike(b)) orders.add(at to name)
                }
            }
        } catch (e: Exception) { }
        orders.sortBy { it.first }
        return Known(called, service, scam, orders)
    }

    /** For call silencing: a number this phone has reason to trust is never silenced as "unknown". */
    fun trusted(w: Who?): Boolean = w != null && (w.level == "ok" || w.level == "known")
}
