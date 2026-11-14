package com.ronin.browser

import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast

/**
 * The parts of Ronin the family chooses (on first start, and any time from the menu):
 * Ronin Mail, Ronin Messages, Ronin Calls — each its own icon. A tablet without a SIM is not
 * offered Messages / Calls. A part that is off is not there at all (no icon, no SMS / call parts).
 */
object Parts {
    const val MAIL = "mail"; const val MESSAGES = "messages"; const val CALLS = "calls"; const val SOS = "sos"
    private val parts = mapOf(
        MAIL to listOf("MailLauncher"),
        MESSAGES to listOf("MessagesActivity", "MessagesLauncher", "SmsDeliverReceiver", "MmsReceiver", "HeadlessSmsSendService"),
        CALLS to listOf("CallsActivity", "CallsLauncher", "InCallActivity", "RoninInCallService", "RoninCallScreen", "CallActionReceiver", "RecordService", "RecordAccess"),
        SOS to listOf("SosActivity", "SosService"),
    )
    private fun cn(ctx: Context, c: String) = ComponentName(ctx.packageName, "com.ronin.browser.$c")
    fun prefs(ctx: Context) = ctx.getSharedPreferences("ronin_parts", Context.MODE_PRIVATE)

    fun isOn(ctx: Context, part: String): Boolean =
        ctx.packageManager.getComponentEnabledSetting(cn(ctx, parts.getValue(part).first())) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    fun set(ctx: Context, part: String, on: Boolean) {
        for (c in parts.getValue(part)) ctx.packageManager.setComponentEnabledSetting(cn(ctx, c),
            if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
    }

    /** Can this device send SMS / make calls (a SIM phone)? */
    fun hasPhone(ctx: Context): Boolean {
        val pm = ctx.packageManager
        return if (Build.VERSION.SDK_INT >= 33) pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING) || pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_CALLING)
        else pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
    }

    // ---- adult / gambling sites on this device: fully off (children, elderly) or in a private tab
    fun adultBlocked(ctx: Context) = prefs(ctx).getBoolean("adultBlock", false)
    private fun pinHash(p: String) = java.security.MessageDigest.getInstance("SHA-256").digest(("ronin-adult:" + p).toByteArray()).joinToString("") { "%02x".format(it) }

    /** Google / Bing / DuckDuckGo search with SafeSearch forced on (only when adult sites are off). */
    fun safeSearch(url: String): String? {
        val u = try { java.net.URI(url) } catch (e: Exception) { return null }
        val h = u.host ?: return null
        val q = u.rawQuery ?: ""
        return when {
            Regex("(^|\\.)google\\.[a-z.]+$").containsMatchIn(h) && u.path == "/search" && !q.contains("safe=active") -> "$url&safe=active"
            (h == "www.bing.com" || h == "bing.com") && u.path == "/search" && !q.contains("adlt=strict") -> "$url&adlt=strict"
            h == "duckduckgo.com" && q.contains("q=") && !q.contains("kp=1") -> "$url&kp=1"
            else -> null
        }
    }

    fun chooseAdult(ctx: Context, first: Boolean) {
        val pinSet = prefs(ctx).getString("adultPin", "").orEmpty().isNotEmpty()
        if (!first && adultBlocked(ctx) && pinSet) {
            // turning it back on needs the PIN set by the parent
            val box = android.widget.EditText(ctx).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD; hint = "PIN" }
            AlertDialog.Builder(ctx).setTitle("🔒 PIN to change Family Filter").setView(box)
                .setPositiveButton("खोलें") { _, _ -> if (pinHash(box.text.toString()) == prefs(ctx).getString("adultPin", "")) askAdult(ctx, false) else Toast.makeText(ctx, "PIN ग़लत है", Toast.LENGTH_LONG).show() }
                .setNegativeButton("रद्द करें", null).show()
            return
        }
        askAdult(ctx, first)
    }

    private fun askAdult(ctx: Context, first: Boolean) {
        val items = arrayOf("🛡 Family Filter ON: restricted sites blocked (child / elderly device)", "🕶 Family Filter OFF: they open in Private (no history)")
        var pick = if (adultBlocked(ctx)) 0 else 1
        AlertDialog.Builder(ctx).setTitle("🛡 Family Filter for this device")
            .setSingleChoiceItems(items, pick) { _, i -> pick = i }
            .setPositiveButton("✔ ठीक है") { _, _ ->
                prefs(ctx).edit().putBoolean("adultAsked", true).putBoolean("adultBlock", pick == 0).apply()
                if (pick == 0) {
                    val box = android.widget.EditText(ctx).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD; hint = "4-digit PIN (empty = no PIN)" }
                    AlertDialog.Builder(ctx).setTitle("🔒 Set a PIN? (so nobody else can change it)").setView(box)
                        .setPositiveButton("✔ Set") { _, _ -> prefs(ctx).edit().putString("adultPin", box.text.toString().trim().let { if (it.isEmpty()) "" else pinHash(it) }).apply() }
                        .setNegativeButton("No PIN", null).show()
                } else prefs(ctx).edit().putString("adultPin", "").apply()
            }
            .setNegativeButton(if (first) "बाद में" else "रद्द करें") { _, _ -> if (first) prefs(ctx).edit().putBoolean("adultAsked", true).apply() }
            .setCancelable(!first).show()
    }

    private fun roleHeld(ctx: Context, role: String) = Build.VERSION.SDK_INT >= 29 && ctx.getSystemService(RoleManager::class.java)?.isRoleHeld(role) == true

    /** The choice: ticked = on. A part still in use as the phone's SMS / phone app is switched off only after the family picks another app. */
    fun choose(ctx: Context, first: Boolean) {
        val phone = hasPhone(ctx)
        val ids = if (phone) listOf(MAIL, SOS, MESSAGES, CALLS) else listOf(MAIL)
        val names = ids.map {
            when (it) { MAIL -> "📧 Ronin Mail (Gmail आदि, ठगी वाले email पर चेतावनी)"; SOS -> "🆘 Ronin SOS (मुसीबत में परिवार को जगह + battery)"; MESSAGES -> "📩 Ronin Messages (SMS app: ठगी / विज्ञापन अलग)"; else -> "📞 Ronin Calls (phone app: अनजान call पर चेतावनी)" }
        }.toTypedArray()
        // first time: Mail ticked; Messages / Calls are big changes, the family ticks them itself
        val checked = ids.map { if (first) it == MAIL || it == SOS else isOn(ctx, it) }.toBooleanArray()
        AlertDialog.Builder(ctx)
            .setTitle(if (first) "Ronin के कौन-से हिस्से चाहिए?" else "📱 Ronin के हिस्से")
            .setMultiChoiceItems(names, checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("✔ ठीक है") { _, _ ->
                prefs(ctx).edit().putBoolean("asked", true).apply()
                var blocked = ""
                ids.forEachIndexed { i, id ->
                    val want = checked[i]
                    if (!want && id == MESSAGES && roleHeld(ctx, RoleManager.ROLE_SMS)) { blocked = "SMS"; return@forEachIndexed }
                    if (!want && id == CALLS && roleHeld(ctx, RoleManager.ROLE_DIALER)) { blocked = "Phone"; return@forEachIndexed }
                    set(ctx, id, want)
                }
                if (blocked.isNotEmpty()) {
                    Toast.makeText(ctx, "पहले Default apps में दूसरी $blocked app चुनें, फिर यहाँ से हटाएँ", Toast.LENGTH_LONG).show()
                    try { ctx.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) } catch (e: Exception) { }
                } else Toast.makeText(ctx, "✔ Home screen पर चुने हुए icon आ गए (कुछ phone में 1-2 सेकंड लगते हैं)", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(if (first) "बाद में" else "रद्द करें") { _, _ -> if (first) prefs(ctx).edit().putBoolean("asked", true).apply() }
            .setOnDismissListener { if (first && !prefs(ctx).getBoolean("adultAsked", false)) chooseAdult(ctx, first = true) }
            .setCancelable(!first)
            .show()
        if (!phone) Toast.makeText(ctx, "इस device में SIM / call नहीं है: Messages और Calls नहीं दिखाए। SIM वाले phone पर Menu → 📱 Ronin के हिस्से", Toast.LENGTH_LONG).show()
    }
}
