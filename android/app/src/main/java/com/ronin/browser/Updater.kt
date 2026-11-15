package com.ronin.browser

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * Ronin's own update: the same GitHub release as the Windows version. Android
 * itself checks that a new APK is signed with Ronin's key before installing it.
 */
object Updater {
    data class Info(val code: Int, val name: String, val url: String, val sha256: String, val size: Long)

    fun parse(json: String): Info? = try {
        val o = JSONObject(json)
        val url = o.getString("url")
        val sha = o.getString("sha256").lowercase()
        if (!url.startsWith("https://") || sha.length != 64) null
        else Info(o.getInt("versionCode"), o.getString("versionName"), url, sha, o.optLong("size"))
    } catch (e: Exception) { null }

    fun check(a: Activity, quiet: Boolean) {
        val app = RoninApp.instance
        if (quiet && System.currentTimeMillis() - app.store.updateChecked < 12 * 3600 * 1000L) return
        thread(name = "ronin-update") {
            val info = try {
                val c = URL(RoninApp.UPDATE_URL).openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 20000
                if (c.responseCode == 200) parse(c.inputStream.bufferedReader().readText()) else null
            } catch (e: Exception) { null }
            app.store.updateChecked = System.currentTimeMillis()
            val current = BuildConfigCompat.versionCode(a)
            a.runOnUiThread {
                if (a.isFinishing) return@runOnUiThread
                if (info == null) {
                    if (!quiet) Toast.makeText(a, "Could not check for updates now.", Toast.LENGTH_SHORT).show()
                } else if (info.code <= current) {
                    if (!quiet) Toast.makeText(a, "Ronin is up to date (${info.name}).", Toast.LENGTH_SHORT).show()
                } else {
                    AlertDialog.Builder(a)
                        .setTitle("Ronin ${info.name} is ready")
                        .setMessage("A new version of Ronin is available. Download and install it now?")
                        .setPositiveButton("Update") { _, _ -> download(a, info) }
                        .setNegativeButton("Later", null)
                        .show()
                }
            }
        }
    }

    private fun download(a: Activity, info: Info) {
        Toast.makeText(a, "Downloading Ronin ${info.name}...", Toast.LENGTH_SHORT).show()
        thread(name = "ronin-update-dl") {
            val dir = File(a.cacheDir, "updates").apply { mkdirs() }
            val f = File(dir, "Ronin.apk")
            val ok = try {
                val c = URL(info.url).openConnection() as HttpURLConnection
                c.connectTimeout = 20000; c.readTimeout = 60000
                c.inputStream.use { inp -> f.outputStream().use { inp.copyTo(it) } }
                sha256(f) == info.sha256
            } catch (e: Exception) { false }
            a.runOnUiThread {
                if (!ok) {
                    f.delete()
                    Toast.makeText(a, "Update download failed. Ronin will try again later.", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                install(a, f)
            }
        }
    }

    private fun install(a: Activity, f: File) {
        if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(a, "Allow Ronin to install updates, then press Update again.", Toast.LENGTH_LONG).show()
            // some TVs have no such screen: then the general security settings
            try { a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName))) }
            catch (e: Exception) { try { a.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) } catch (e2: Exception) {} }
            return
        }
        val uri = FileProvider.getUriForFile(a, "com.ronin.browser.files", f)
        a.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { inp ->
            val buf = ByteArray(65536)
            while (true) { val n = inp.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

object BuildConfigCompat {
    @Suppress("DEPRECATION")
    fun versionCode(a: Activity): Int {
        val pi = a.packageManager.getPackageInfo(a.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else pi.versionCode
    }
    fun versionName(a: Activity): String = a.packageManager.getPackageInfo(a.packageName, 0).versionName ?: ""
}
