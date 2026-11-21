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
    /** risk = red row, ok = a known service line, known = you called and talked, maybe = came in an order's time, info = grey. */
    data class Who(val level: String, val text: String)

    /** What this phone knows: numbers you called / talked to, numbers inside company SMS, scam numbers, recent orders. */
    class Known(
        val called: Set<String> = emptySet(),
        val service: Map<String, String> = emptyMap(),
        val scam: Set<String> = emptySet(),
        val orders: List<Pair<Long, String>> = emptyList(),
        val people: Map<String, String> = emptyMap(), // number → "Ramesh (Airtel)": the name written next to it in a company SMS
        val dialed: Set<String> = emptySet(), // you called, nobody answered: a hint, not trust (a missed-call scam asks for a call back)
    )

    fun key(n: String): String = n.filter { it.isDigit() }.takeLast(10)

    private val numRe = Regex("(?<![\\d])(?:\\+?91[\\s-]?|0)?([6-9]\\d{4}[\\s-]?\\d{5})(?![\\d@])|(?<![\\d])(0\\d{2,4}[\\s-]?\\d{6,8})(?![\\d@])|(?<![\\d])(1600\\d{6})(?![\\d@])")
    fun numbersIn(body: String): List<String> = numRe.findAll(body).map { m -> key(m.value) }.filter { it.length == 10 }.distinct().toList()

    /** A DLT company header ("VM-AIRTEL-S"): only these can vouch for a number (a number can send any text). */
    fun companyHeader(address: String): Boolean = Regex("^[A-Z]{2}-[A-Z0-9]{3,9}(-[A-Z])?$").matches(address.uppercase().trim())
    fun promo(address: String): Boolean = address.uppercase().trim().endsWith("-P")

    // only delivery / technician visits (QA: "visit airtel.in", "install the app" are not an order)
    private val orderRe = Regex("\\b(out for delivery|delivery (partner|executive|agent|boy)|rider|technician|engineer|installation (is )?(scheduled|today|tomorrow)|courier|will (call|visit) you|arriving (today|soon)|picked up|on the way)\\b", RegexOption.IGNORE_CASE)
    fun orderLike(body: String): Boolean = orderRe.containsMatchIn(body)
    // a bank / UPI SMS names the other party's number ("₹1 credited from 98…@ybl", "money requested by 98…"): never a vouch
    private val moneyRe = Regex("\\b(credited|debited|upi|a/c|acct|txn|refund|collect|requested|paid|received|sent|transfer)\\b|rs\\.?\\s?\\d|₹|inr\\s?\\d", RegexOption.IGNORE_CASE)
    fun moneySms(body: String): Boolean = moneyRe.containsMatchIn(body)

    // "Technician Ramesh Kumar (9876501234)", "delivery partner Suresh, 98765 01234", "Ramesh - 9876501234 will visit"
    private val roleWord = "(?:technician|engineer|executive|delivery (?:partner|executive|agent|boy)|rider|agent|associate|partner|representative|installer|courier boy|driver|captain)"
    private val nameRe = Regex("(?i:\\b$roleWord\\s*(?:name)?\\s*(?:is|:|-)?\\s*(?:mr\\.?|ms\\.?|shri)?)\\s*([A-Z][a-z]{2,15}(?:\\s[A-Z][a-z]{2,15})?)")
    private val nameBeforeNum = Regex("\\b([A-Z][a-z]{2,15}(?:\\s[A-Z][a-z]{2,15})?)\\s*[(:,-]?\\s*(?:\\+?91[\\s-]?)?[6-9]\\d{4}[\\s-]?\\d{5}\\b")
    private val notNames = setOf("Helpline", "Scheduled", "Whatsapp", "App", "Bank", "Id", "Today", "Tomorrow", "Soon", "Dear", "Your", "Call", "Contact", "Mobile", "Phone", "Number", "Customer", "Order", "Delivery", "Technician", "Engineer", "The", "From", "Team", "Thank", "Thanks", "Please", "Visit", "Will", "Airtel", "Jio", "Swiggy", "Zomato", "Amazon", "Flipkart", "Mr", "Ms", "Shri", "Partner", "Agent", "Rider", "Executive", "Help", "Support", "Care", "Toll", "Free", "Hi", "Hello")

    /** The person's name a company SMS writes next to a number (or null). */
    fun personIn(body: String): String? {
        val m = nameRe.find(body) ?: nameBeforeNum.find(body) ?: return null
        val raw = m.groupValues[1].trim()
        val stop = notNames.map { it.lowercase() }.toSet()
        val words = raw.split(" ").filter { it.length >= 3 && it[0].isUpperCase() && it.drop(1).all { ch -> ch.isLowerCase() } && it.lowercase() !in stop }
        return words.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    /** "AIRTEL", "SWIGGY", "AIRBRD" → "Airtel", "Swiggy", "Airbrd". */
    fun brand(address: String): String = address.trim().uppercase().replace(Regex("^[A-Z]{2}-"), "").replace(Regex("-[A-Z]$"), "")
        .lowercase().replaceFirstChar { it.uppercase() }

    fun judge(number: String, contact: Boolean, cnap: String, k: Known, at: Long = System.currentTimeMillis()): Who? {
        val d = CallInfo.digits(number)
        if (d.isEmpty()) return Who("risk", L("छुपा हुआ नंबर (private): सावधान", "Hidden number (private): be careful"))
        if (CallInfo.isEmergency(d) || contact) return null
        val n = key(d)
        if (d.startsWith("+") && !d.startsWith("+91")) return Who("risk", L("🌍 विदेश का नंबर: अनजान हो तो न उठाएँ, पैसे / OTP न दें", "🌍 Foreign number: if you do not know them, do not answer; never give money / OTP"))
        if (n in k.scam) return Who("risk", L("⛔ यह नंबर ठगी वाले SMS में आया था: पैसे / OTP न दें", "⛔ This number was in a scam SMS: never give money / OTP"))
        if (d.removePrefix("+91").removePrefix("0").startsWith("1600")) return Who("ok", L("🏦 बैंक / सरकारी दफ़्तर की असली service line (1600) · फिर भी OTP न बताएँ", "🏦 A real bank / government service line (1600) · still never tell an OTP"))
        k.service[n]?.let { b -> val p = k.people[n]; return Who("ok", if (p != null) L("🛠 आपके $b SMS से: $p", "🛠 From your $b SMS: $p") else L("🛠 आपके $b वाले SMS में यही नंबर था", "🛠 This number was in your $b SMS")) }
        if (n in k.called) return Who("known", L("📞 आपने इन्हें call करके बात की थी", "📞 You called them and talked before"))
        if (n in k.dialed) return Who("info", L("📞 आपने इन्हें call किया था (बात नहीं हुई)", "📞 You called them (no answer)"))
        if (d.removePrefix("+91").startsWith("140")) return Who("info", L("📢 Telemarketing (140 वाला नंबर)", "📢 Telemarketing (a 140 number)"))
        // only a hint: never trusted (a fake courier SMS could open this window)
        k.orders.lastOrNull { at - it.first in 0..6 * 3600_000L }?.let { return Who("maybe", L("🛵 ${it.second} के order / service के समय आई call: शायद वही हों · OTP / पैसे न दें", "🛵 Came while your ${it.second} order / service was on: maybe them · never give OTP / money")) }
        if (cnap.isNotEmpty()) return Who("info", L("📛 SIM पर यही नाम है · contact में नहीं", "📛 Name from the SIM · not in contacts"))
        return Who("info", L("👤 अनजान नंबर · 🔍 से खोजें", "👤 Not in contacts · tap 🔍 to look up"))
    }

    @Volatile private var cache: Pair<Long, Known>? = null

    /** Read once a minute at most: the last 500 calls and the last 30 days of SMS (on the phone only). */
    @Volatile private var reading = false
    fun known(ctx: Context): Known {
        val c = cache
        if (c != null && System.currentTimeMillis() - c.first < 60_000) return c.second
        // on the main thread (ring screen, call screen, notices) never read the phone: the last answer, fresh soon
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) { warm(ctx); return c?.second ?: Known() }
        return read(ctx.applicationContext).also { cache = System.currentTimeMillis() to it }
    }

    /** Read in the background (a call came in, an SMS arrived): the ring / call screens then have it at once. */
    fun warm(ctx: Context, afterMs: Long = 0) {
        if (reading) return
        reading = true
        val app = ctx.applicationContext
        Thread { try { if (afterMs > 0) Thread.sleep(afterMs); cache = System.currentTimeMillis() to read(app) } catch (e: Throwable) { } finally { reading = false } }.start()
    }

    private fun read(ctx: Context): Known {
        val called = HashSet<String>(); val service = HashMap<String, String>(); val scam = HashSet<String>(); val orders = ArrayList<Pair<Long, String>>()
        val people = HashMap<String, String>(); val dialed = HashSet<String>()
        try {
            ctx.contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DURATION), null, null, CallLog.Calls.DATE + " DESC LIMIT 500")?.use { c ->
                while (c.moveToNext()) {
                    val t = c.getInt(1); val dur = c.getLong(2)
                    // only your own answered call vouches (a long incoming scam call must not make the scammer "known")
                    if (t == CallLog.Calls.OUTGOING_TYPE) key(c.getString(0) ?: "").takeIf { it.length == 10 }?.let { if (dur > 0) called.add(it) else dialed.add(it) }
                }
            }
        } catch (e: Exception) { }
        try {
            val now = System.currentTimeMillis()
            val scamRules = RoninApp.instance.rules.scam
            ctx.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf("address", "body", "date"), "date > ?", arrayOf((now - 30 * 86_400_000L).toString()), "date DESC")?.use { c ->
                var rows = 0
                while (c.moveToNext() && rows++ < 1500) {
                    val a = c.getString(0) ?: ""; val b = c.getString(1) ?: ""; val at = c.getLong(2)
                    val nums = numbersIn(b)
                    val header = companyHeader(a)
                    // the scam check only where it can mark a number: numbers in the text, or a number as the sender
                    val order = now - at < 24 * 3600_000L && orderLike(b)
                    // the scam check only where it can matter: numbers in the text, a number as the sender, an order hint
                    val level = if (nums.isNotEmpty() || !header || order) scamRules.text(b).level else ""
                    if (level == "stop") { scam.addAll(nums); key(a).takeIf { it.length == 10 && !header }?.let { scam.add(it) }; continue }
                    // only a clean company SMS vouches (not promo, not "warn", not a bank / UPI SMS naming the other party)
                    if (!header || promo(a) || level.isNotEmpty()) continue
                    val name = brand(a)
                    if (order) orders.add(at to name)
                    if (moneySms(b)) continue
                    if (now - at < 14 * 86_400_000L) {
                        nums.forEach { service.putIfAbsent(it, name) }
                        if (nums.size == 1) personIn(b)?.let { p -> people.putIfAbsent(nums[0], "$p ($name)") }
                    }
                }
            }
        } catch (e: Exception) { }
        orders.sortBy { it.first }
        return Known(called, service, scam, orders, people, dialed)
    }

    /** For call silencing: a number this phone has reason to trust is never silenced as "unknown". */
    fun trusted(w: Who?): Boolean = w != null && (w.level == "ok" || w.level == "known")
}
