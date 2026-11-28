package com.ronin.census

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * The owner turns the helper on by hand: pull down the notification shade over the Census app and tap
 * "जनगणना सहायक". Tap again (or ⏻ on the panel) to turn it off; it also goes off by itself when he leaves that app.
 */
class CensusTile : TileService() {
    companion object {
        fun refresh(c: Context) { try { requestListeningState(c, ComponentName(c, CensusTile::class.java)) } catch (_: Exception) {} }
    }

    override fun onStartListening() { draw() }

    override fun onClick() {
        val s = CensusService.instance
        if (s == null) { // Accessibility not switched on yet: open the setup screen
            val i = Intent(this, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (android.os.Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(android.app.PendingIntent.getActivity(this, 0, i, android.app.PendingIntent.FLAG_IMMUTABLE))
            else @Suppress("DEPRECATION") startActivityAndCollapse(i)
            return
        }
        s.setOn(!s.isOn)
        draw(!s.isOn)
    }

    private fun draw(on: Boolean = CensusService.instance?.isOn == true) {
        val t = qsTile ?: return
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "जनगणना सहायक"
        t.updateTile()
    }
}
