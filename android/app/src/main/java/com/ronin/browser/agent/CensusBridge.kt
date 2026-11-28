package com.ronin.browser.agent

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * C's steps for the Census app (app_screen, app_fill, app_choose, app_press, app_scroll) go to the census helper on
 * this phone ("जनगणना सहायक", Accessibility), which reads / fills the official app. Owner, 28 Nov: Claude live, the data
 * only passes through (nothing is stored; only tool names are logged). Called from the link's worker thread.
 */
class CensusBridge(private val ctx: Context) {
    companion object { val TOOLS = setOf("app_screen", "app_fill", "app_choose", "app_press", "app_scroll", "app_listen", "app_say") }

    private val replies = HandlerThread("census-replies").apply { start() }
    @Volatile private var remote: Messenger? = null
    private var bound = false
    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName?, b: IBinder?) { remote = b?.let { Messenger(it) } }
        override fun onServiceDisconnected(n: ComponentName?) { remote = null }
    }

    private fun ensure(): Messenger? {
        remote?.let { return it }
        if (!bound) {
            val i = Intent().setComponent(ComponentName("com.ronin.census", "com.ronin.census.CensusBridgeService"))
            bound = try { ctx.applicationContext.bindService(i, conn, Context.BIND_AUTO_CREATE) } catch (e: SecurityException) { false }
            if (!bound) return null
        }
        var w = 0
        while (remote == null && w < 30) { Thread.sleep(100); w++ }
        return remote
    }

    fun run(tool: String, args: JSONObject): JSONObject {
        val m = ensure() ?: return JSONObject().put("error", "जनगणना सहायक app इस phone पर नहीं मिला (या Ronin Agent को उसकी अनुमति नहीं): सहायक लगाकर Ronin Agent फिर से लगाएँ")
        val latch = CountDownLatch(1)
        var out: String? = null
        val replyTo = Messenger(Handler(replies.looper) { r -> out = r.data.getString("out"); latch.countDown(); true })
        val msg = Message.obtain().apply { data = Bundle().apply { putString("tool", tool); putString("args", args.toString()) }; this.replyTo = replyTo }
        try { m.send(msg) } catch (e: Exception) { remote = null; return JSONObject().put("error", "सहायक से जुड़ाव टूटा: फिर से कोशिश करें") }
        val wait = if (tool == "app_listen") 50L else 15L // listening answers when something is said (at most 40 s)
        if (!latch.await(wait, TimeUnit.SECONDS)) return JSONObject().put("error", "सहायक ने $wait सेकंड में जवाब नहीं दिया")
        return try { JSONObject(out ?: "{}") } catch (e: Exception) { JSONObject().put("error", "सहायक का जवाब पढ़ा नहीं गया") }
    }
}
