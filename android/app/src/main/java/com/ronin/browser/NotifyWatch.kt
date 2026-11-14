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
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val app = APPS[sbn.packageName] ?: return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val ex = n.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
        if (sbn.packageName in CHATS) io.execute { keep(sbn, n, app, title, text) }
        if (text.length < 12) return
        val key = (sbn.packageName + title + text).hashCode()
        if (key in recent) return
        recent.addLast(key); if (recent.size > 50) recent.removeFirst()
        val r = try { RoninApp.instance.rules.scam.text(text) } catch (e: Exception) { return }
        if (r.level.isEmpty()) return
        val friend = "need" in r.ids
        val head = if (friend) L("⛔ $app: \"$title\" के नाम से पैसे माँगे गए", "⛔ $app: money asked in the name of \"$title\"") else L("⛔ $app: \"$title\" का संदेश ठगी जैसा है", "⛔ $app: the message from \"$title\" looks like a scam")
        val body = (r.reasons.firstOrNull() ?: L("ठगी के संकेत", "Signs of a scam")) + "\n" +
            (if (friend) L("पैसे भेजने से पहले उन्हें सीधे फ़ोन करके पक्का करें: उनका WhatsApp hack हो सकता है।", "Before paying, phone them yourself to check: their WhatsApp may be hacked.") else L("पैसे, OTP, PIN, फ़ोटो न दें, link न खोलें।", "Never give money, OTP, PIN or photos; do not open the link."))
        val open = Intent(this, MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        SmsStore.notify(this, key, SmsStore.CH_WARN, head, body, open)
    }

    /** Every chat message of the notification goes to the 7-day log (deleted ones can be read later). */
    private fun keep(sbn: StatusBarNotification, n: Notification, app: String, title: String, text: String) {
        try {
            val style = androidx.core.app.NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
            val chat = style?.conversationTitle?.toString() ?: title
            val msgs = style?.messages ?: emptyList()
            if (msgs.isEmpty()) {
                if (DELETED.containsMatchIn(text)) ChatLog.markDeleted(this, chat, "") else ChatLog.add(this, app, chat, title, text, sbn.postTime, sbn.key)
                return
            }
            for (m in msgs) {
                val body = m.text?.toString() ?: continue
                val who = m.person?.name?.toString() ?: title
                if (DELETED.containsMatchIn(body)) ChatLog.markDeleted(this, chat, who)
                else ChatLog.add(this, app, chat, who, body, m.timestamp, sbn.key)
            }
        } catch (e: Exception) { }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        // the app removed it itself (not the user tapping or swiping): maybe the message was deleted
        if (sbn.packageName in CHATS && reason == REASON_APP_CANCEL) io.execute { try { ChatLog.markGone(this, sbn.key) } catch (e: Exception) { } }
    }

    companion object {
        val CHATS = setOf("com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "com.instagram.android", "com.facebook.orca", "org.thoughtcrime.securesms")
        val DELETED = Regex("(this message was deleted|you deleted this message|यह संदेश हटा दिया गया|यह मैसेज डिलीट|संदेश हटा दिया)", RegexOption.IGNORE_CASE)
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
