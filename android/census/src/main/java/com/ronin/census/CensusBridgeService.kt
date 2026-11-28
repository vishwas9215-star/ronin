package com.ronin.census

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import org.json.JSONObject

/**
 * Ronin Agent (same signing key only: signature permission) hands C's steps to the helper:
 *   app_screen · app_fill(label, value) · app_choose(text) · app_press(text, owner_said_yes) · app_scroll(direction)
 * The answer is JSON. Nothing is kept here: values pass through to the Census app's boxes and are not logged.
 */
class CensusBridgeService : Service() {
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { m ->
        val req = m.data
        val tool = req.getString("tool").orEmpty()
        val args = try { JSONObject(req.getString("args") ?: "{}") } catch (e: Exception) { JSONObject() }
        val out = CensusService.instance?.bridge(tool, args)
            ?: JSONObject().put("error", "जनगणना सहायक चालू नहीं है: phone पर Settings → Accessibility → जनगणना सहायक चालू करें")
        try { m.replyTo?.send(Message.obtain().apply { data = Bundle().apply { putString("out", out.toString()) } }) } catch (_: Exception) {}
        true
    })

    override fun onBind(intent: Intent?): IBinder = messenger.binder
}
