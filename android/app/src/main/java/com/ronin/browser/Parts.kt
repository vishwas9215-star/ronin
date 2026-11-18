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
 * The phone parts of Ronin the family chooses (first start on a SIM phone, and any time from the menu):
 * Ronin Messages and Ronin Calls, each its own icon. A part that is off is not there at all.
 * (3.13: Mail / Social / SOS are gone; a tablet without a SIM is never offered these.)
 */
object Parts {
    const val MESSAGES = "messages"; const val CALLS = "calls"
    private val parts = mapOf(
        MESSAGES to listOf("MessagesActivity", "MessagesLauncher", "SmsDeliverReceiver", "MmsReceiver", "HeadlessSmsSendService"),
        CALLS to listOf("CallsActivity", "CallsLauncher", "InCallActivity", "RoninInCallService", "RoninCallScreen", "CallActionReceiver", "RecordService", "RecordAccess"),
    )
    private fun cn(ctx: Context, c: String) = ComponentName(ctx.packageName, "com.ronin.browser.$c")
    fun prefs(ctx: Context) = Family.prefs(ctx)

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

    private fun roleHeld(ctx: Context, role: String) = Build.VERSION.SDK_INT >= 29 && ctx.getSystemService(RoleManager::class.java)?.isRoleHeld(role) == true

    /** The choice: ticked = on. A part still in use as the phone's SMS / phone app is switched off only after the family picks another app. */
    fun choose(ctx: Context, first: Boolean) {
        if (!hasPhone(ctx)) {
            prefs(ctx).edit().putBoolean("asked", true).apply()
            if (!first) Toast.makeText(ctx, L("इस device में SIM / call नहीं है: Messages और Calls सिर्फ़ SIM वाले phone पर।", "This device has no SIM / calls: Messages and Calls are for a phone with a SIM."), Toast.LENGTH_LONG).show()
            return
        }
        val ids = listOf(MESSAGES, CALLS)
        val names = arrayOf(L("📩 Ronin Messages (SMS app: OTP बड़ा, ठगी / विज्ञापन अलग)", "📩 Ronin Messages (SMS app: big OTP, scams / ads kept apart)"),
            L("📞 Ronin Calls (phone app: अनजान call पर चेतावनी)", "📞 Ronin Calls (phone app: warns on unknown calls)"))
        val checked = ids.map { isOn(ctx, it) }.toBooleanArray()
        AlertDialog.Builder(ctx)
            .setTitle(L("📱 Ronin के हिस्से", "📱 Ronin parts"))
            .setMultiChoiceItems(names, checked) { _, i, on -> checked[i] = on }
            .setPositiveButton(L("✔ ठीक है", "✔ OK")) { _, _ ->
                prefs(ctx).edit().putBoolean("asked", true).apply()
                var blocked = ""
                ids.forEachIndexed { i, id ->
                    val want = checked[i]
                    if (!want && id == MESSAGES && roleHeld(ctx, RoleManager.ROLE_SMS)) { blocked = "SMS"; return@forEachIndexed }
                    if (!want && id == CALLS && roleHeld(ctx, RoleManager.ROLE_DIALER)) { blocked = "Phone"; return@forEachIndexed }
                    set(ctx, id, want)
                }
                if (blocked.isNotEmpty()) {
                    Toast.makeText(ctx, L("पहले Default apps में दूसरी $blocked app चुनें, फिर यहाँ से हटाएँ", "First choose another $blocked app in Default apps, then remove it here"), Toast.LENGTH_LONG).show()
                    try { ctx.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) } catch (e: Exception) { }
                } else if (checked.any { it }) Toast.makeText(ctx, L("✔ Home screen पर चुने हुए icon आ गए", "✔ The chosen icons are on the home screen"), Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(if (first) L("बाद में", "Later") else L("रद्द करें", "Cancel")) { _, _ -> prefs(ctx).edit().putBoolean("asked", true).apply() }
            .setCancelable(!first)
            .show()
    }
}
