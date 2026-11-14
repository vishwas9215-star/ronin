package com.ronin.browser

import android.app.Application
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread

class RoninApp : Application() {
    lateinit var rules: Rules
    lateinit var blocker: AdBlocker
    lateinit var store: Store

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = Store(this)
        rules = Rules(assets.open("ronin/rules.json").bufferedReader().readText())
        blocker = AdBlocker(rules)
        thread(name = "ronin-lists") { loadAndRefreshLists() }
    }

    private val listFile get() = File(filesDir, "adblock-domains.txt.gz")

    /** The big ad list: last saved copy at once, a fresh one from the internet once a day. */
    private fun loadAndRefreshLists() {
        try {
            if (listFile.exists()) {
                val n = GZIPInputStream(listFile.inputStream()).bufferedReader().use { blocker.loadList(it) }
                Log.i(TAG, "ad list loaded: $n")
            }
            if (System.currentTimeMillis() - store.listsUpdated < 24 * 3600 * 1000L && listFile.exists()) return
            val tmp = File(filesDir, "adblock-domains.tmp")
            val c = URL(LISTS_URL).openConnection() as HttpURLConnection
            c.connectTimeout = 20000; c.readTimeout = 60000
            c.instanceFollowRedirects = true
            if (c.responseCode != 200) return
            c.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
            val n = GZIPInputStream(tmp.inputStream()).bufferedReader().use { blocker.loadList(it) }
            if (n > 50000) {
                tmp.renameTo(listFile)
                store.listsUpdated = System.currentTimeMillis()
            } else tmp.delete()
            Log.i(TAG, "ad list updated: $n")
        } catch (e: Exception) {
            Log.w(TAG, "ad list: $e")
        }
    }

    companion object {
        const val TAG = "Ronin"
        const val REPO = "https://github.com/vishwas9215-star/ronin"
        const val LISTS_URL = "$REPO/releases/download/lists/adblock-domains.txt.gz"
        const val UPDATE_URL = "$REPO/releases/latest/download/android.json"
        lateinit var instance: RoninApp
    }
}
