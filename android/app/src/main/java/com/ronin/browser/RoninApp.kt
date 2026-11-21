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
        // how many Ronin screens are open (the browser rests its pages' timers only when none is)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: android.app.Activity) { visibleScreens++ }
            override fun onActivityStopped(a: android.app.Activity) { visibleScreens = (visibleScreens - 1).coerceAtLeast(0) }
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityResumed(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
    }

    @Volatile var visibleScreens = 0
        private set
    @Volatile private var listsStarted = false

    /**
     * The big ad / danger lists are read when a browser screen opens, not on every start of the app
     * (an SMS or a call also starts it: that no longer reads lakhs of addresses - battery).
     */
    fun startLists() {
        if (listsStarted) return
        listsStarted = true
        thread(name = "ronin-lists") {
            loadSavedThreats() // saved lists first: protection and ad-block work at once
            loadAndRefreshLists()
            loadAndRefreshThreats()
        }
    }

    private val listFile get() = File(filesDir, "adblock-domains.txt.gz")
    private val threatFile get() = File(filesDir, "threat-domains.txt.gz")

    /** The saved phishing / malware list, at once (no internet needed). A damaged file is deleted. */
    @Synchronized
    private fun loadSavedThreats() {
        if (!threatFile.exists() || blocker.threatSize > 0) return
        try {
            val arr = GZIPInputStream(threatFile.inputStream()).bufferedReader().use { blocker.parseThreats(it) }
            if (arr.size > 1000) blocker.setThreats(arr) else threatFile.delete()
        } catch (e: Exception) {
            Log.w(TAG, "danger list file damaged, fetching again: $e")
            threatFile.delete()
        }
    }

    /** A fresh phishing / malware list every 12 hours. Only one download at a time. */
    @Synchronized
    fun loadAndRefreshThreats(force: Boolean = false) {
        loadSavedThreats()
        if (!force && System.currentTimeMillis() - store.threatsUpdated < 12 * 3600 * 1000L && threatFile.exists()) return
        var tmp: File? = null
        try {
            tmp = File.createTempFile("threats", ".gz", filesDir)
            val c = URL(THREATS_URL).openConnection() as HttpURLConnection
            c.connectTimeout = 20000; c.readTimeout = 60000
            c.instanceFollowRedirects = true
            if (c.responseCode != 200) return
            c.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
            val arr = GZIPInputStream(tmp.inputStream()).bufferedReader().use { blocker.parseThreats(it) }
            if (arr.size > 1000 && tmp.renameTo(threatFile)) { // only a complete list replaces the old one
                blocker.setThreats(arr)
                store.threatsUpdated = System.currentTimeMillis()
            }
            Log.i(TAG, "danger list updated: ${arr.size}")
        } catch (e: Exception) {
            Log.w(TAG, "danger list: $e")
        } finally {
            tmp?.let { if (it.exists()) it.delete() }
        }
    }

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
        const val THREATS_URL = "$REPO/releases/download/lists/threat-domains.txt.gz"
        const val UPDATE_URL = "$REPO/releases/latest/download/android.json"
        const val PHONE_UPDATE_URL = "$REPO/releases/latest/download/phone.json"
        lateinit var instance: RoninApp
    }
}
