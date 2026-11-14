package com.ronin.browser

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat

/**
 * Watches the notifications of WhatsApp, other chat apps, SMS apps and Gmail: a message that looks
 * like a scam — above all a hacked friend's "I need money, pay on this QR" — gets Ronin's warning at
 * once. The family turns it on (Ronin Messages -> Settings). The text is checked on this phone and
 * forgotten; nothing is stored or sent.
 */
class NotifyWatch : NotificationListenerService() {
    private val recent = ArrayDeque<Int>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val app = APPS[sbn.packageName] ?: return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val ex = n.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
        if (text.length < 12) return
        val key = (sbn.packageName + title + text).hashCode()
        if (key in recent) return
        recent.addLast(key); if (recent.size > 50) recent.removeFirst()
        val r = try { RoninApp.instance.rules.scam.text(text) } catch (e: Exception) { return }
        if (r.level.isEmpty()) return
        val friend = "need" in r.ids
        val head = if (friend) "⛔ $app: \"$title\" के नाम से पैसे माँगे गए" else "⛔ $app: \"$title\" का संदेश ठगी जैसा है"
        val body = (r.reasons.firstOrNull() ?: "ठगी के संकेत") + "\n" +
            (if (friend) "पैसे भेजने से पहले उन्हें सीधे फ़ोन करके पक्का करें: उनका WhatsApp hack हो सकता है।" else "पैसे, OTP, PIN, फ़ोटो न दें, link न खोलें।")
        val open = Intent(this, MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        SmsStore.notify(this, key, SmsStore.CH_WARN, head, body, open)
    }

    companion object {
        /** apps whose messages are checked, with the name shown in the warning */
        val APPS = mapOf(
            "com.whatsapp" to "WhatsApp", "com.whatsapp.w4b" to "WhatsApp Business", "com.google.android.apps.messaging" to "Messages",
            "com.samsung.android.messaging" to "Messages", "com.android.mms" to "Messages", "com.instagram.android" to "Instagram",
            "com.facebook.orca" to "Messenger", "com.facebook.katana" to "Facebook", "org.telegram.messenger" to "Telegram",
            "org.thoughtcrime.securesms" to "Signal", "com.google.android.gm" to "Gmail", "com.truecaller" to "Truecaller",
            "com.snapchat.android" to "Snapchat", "com.sharechat.app" to "ShareChat", "in.mohalla.sharechat" to "ShareChat")
        fun enabled(ctx: Context) = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    }
}
