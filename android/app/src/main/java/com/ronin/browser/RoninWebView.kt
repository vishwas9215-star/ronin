package com.ronin.browser

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.webkit.WebView

/**
 * A WebView that can keep playing while the app is in the background: when
 * keepPlaying is on, the engine is never told that the window went away.
 */
@SuppressLint("ViewConstructor")
class RoninWebView(context: Context) : WebView(context) {
    @Volatile var keepPlaying = false

    override fun onWindowVisibilityChanged(visibility: Int) {
        if (keepPlaying && visibility != View.VISIBLE) {
            super.onWindowVisibilityChanged(View.VISIBLE)
        } else {
            super.onWindowVisibilityChanged(visibility)
        }
    }

    // When the app goes to the background Android also hides the view itself;
    // the engine then holds back the next song until the app is opened again.
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        if (keepPlaying && visibility != View.VISIBLE) {
            super.onVisibilityChanged(changedView, View.VISIBLE)
        } else {
            super.onVisibilityChanged(changedView, visibility)
        }
    }
}
