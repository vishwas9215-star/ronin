package com.ronin.browser

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Ronin YouTube / Ronin Music screen: the page fills the phone exactly (inside the status and gesture bars). */
class SiteActivity : AppCompatActivity() {
    private lateinit var box: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        box = FrameLayout(this).apply { setBackgroundColor(0xFF0F0F0F.toInt()) }
        setContentView(box)
        // auto-fit on every phone: the page gets exactly the free space between the system bars
        ViewCompat.setOnApplyWindowInsetsListener(box) { v, ins ->
            val b = ins.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom); ins
        }
        val w = SitePlayer.page(this)
        (w.parent as? ViewGroup)?.removeView(w)
        box.addView(w, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        w.onResume(); w.resumeTimers()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        // Back = the page's own back; at the start page the app goes behind (the song keeps playing)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (w.canGoBack()) w.goBack() else moveTaskToBack(true) }
        })
    }

    override fun onDestroy() {
        // the page is kept (it may be playing); only the screen goes
        val w = SitePlayer.page(this)
        if (w.parent == box) box.removeView(w)
        super.onDestroy()
    }
}
