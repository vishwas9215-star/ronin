package com.ronin.browser

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.Telephony
import org.json.JSONObject

/**
 * "Report on Chakshu" (owner, 21 Nov 2026, both hands fractured): Ronin Phone opens Ronin Browser right on the
 * government's complaint form with the scam SMS / call, ui/chakshu.js fills every box, and the Sanchar Saathi OTP comes
 * from Ronin Phone by itself. The owner only types the CAPTCHA and presses Submit.
 */
object Chakshu {
    const val FORM = "https://sancharsaathi.gov.in/sfc/Home/sfc-complaint.jsp"
    const val EXTRA = "com.ronin.chakshu"
    private const val OTP_URI = "content://com.ronin.phone.otp/latest"

    fun isForm(url: String?): Boolean = url != null && url.startsWith(FORM)

    fun hasBrowser(ctx: Context): Boolean = try { ctx.packageManager.getPackageInfo("com.ronin.browser", 0); true } catch (e: Exception) { false }

    /** The message for chakshu.js: medium "sms" / "call", sender, text, time. */
    fun data(medium: String, address: String, body: String, date: Long): String =
        JSONObject().put("medium", if (medium == "call") "call" else "sms").put("address", address.take(40))
            .put("body", body.take(2000)).put("date", if (date > 0) date else System.currentTimeMillis()).toString()

    /** Ronin Phone → Ronin Browser on the form (only Ronin Browser fills it; another browser just shows the form). */
    fun open(ctx: Context, data: String) {
        val u = Uri.parse(FORM)
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, u).setPackage("com.ronin.browser").putExtra(EXTRA, data).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            try { ctx.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e2: Exception) { }
        }
    }

    /**
     * Only the Sanchar Saathi OTP may go into the form, never a bank's: the SMS names Sanchar Saathi / Chakshu / DoT,
     * or its sender is a government header (DLT "-G" ending, or DoT / Sanchar / NIC in the name).
     */
    fun isSancharOtp(address: String, body: String): Boolean {
        val a = address.uppercase().trim()
        if (Regex("sanchar|saathi|chakshu|department of telecom|\\bdot\\b|दूरसंचार|संचार साथी", RegexOption.IGNORE_CASE).containsMatchIn(body)) return true
        return Regex("^[A-Z]{2}-[A-Z0-9]{3,9}-G$").matches(a) || Regex("^[A-Z]{2}-[A-Z0-9]*(DOT|SNCHR|SANCHR|SSAATH|SNSAAT|NIC|CEIR|GOV)[A-Z0-9]*(-[A-Z])?$").matches(a)
    }

    /** Ronin Browser asks Ronin Phone for the Sanchar Saathi OTP that came after [since] (signature permission only). */
    fun otpFromPhone(ctx: Context, since: Long): String? = try {
        ctx.contentResolver.query(Uri.parse("$OTP_URI?since=$since"), null, null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (e: Exception) { null }
}

/** In Ronin Phone: the newest Sanchar Saathi OTP of the last few minutes, for Ronin Browser's Chakshu form. */
class ChakshuOtpProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val c = MatrixCursor(arrayOf("otp"))
        val ctx = context ?: return c
        val now = System.currentTimeMillis()
        val since = maxOf(uri.getQueryParameter("since")?.toLongOrNull() ?: 0L, now - 10 * 60_000L)
        try {
            ctx.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf("address", "body", "date"), "date > ?", arrayOf(since.toString()), "date DESC")?.use { r ->
                var n = 0
                while (r.moveToNext() && n++ < 10) {
                    val address = r.getString(0) ?: ""; val body = r.getString(1) ?: ""
                    if (!Chakshu.isSancharOtp(address, body)) continue
                    val code = Otp.find(body) ?: continue
                    c.addRow(arrayOf<Any>(code)); break
                }
            }
        } catch (e: Exception) { } // no SMS permission yet: the owner types the OTP himself
        return c
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}
